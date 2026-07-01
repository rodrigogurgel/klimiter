package io.github.rodrigogurgel.klimiter.config

import com.netflix.concurrency.limits.grpc.server.ConcurrencyLimitServerInterceptor
import com.netflix.concurrency.limits.grpc.server.GrpcServerLimiterBuilder
import com.netflix.concurrency.limits.limit.Gradient2Limit
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.CpuShedServerInterceptor
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.MicrometerLimitMetricRegistry
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor

/**
 * Wiring do *load shedding* (§ short-circuit): registra os interceptors de proteção como globais e
 * opt-in por [ShedProperties]. Ambos rejeitam com `UNAVAILABLE` (o cliente aplica retry/backoff) e
 * contabilizam os cortes em `klimiter.shed.count{reason}`, para se comparar o quanto cada mecanismo
 * derruba. Ordenados com precedência alta (`@Order` baixo → interceptor mais externo): cortam cedo,
 * antes do handler e da observação por-RPC, mantendo o corte barato.
 *
 * Só o composition root instancia adapters (regra de dependência §2). `proxyBeanMethods = false`:
 * sem chamadas inter-`@Bean`.
 */
@Configuration(proxyBeanMethods = false)
class ShedConfiguration {
    /** Amostrador de CPU + gauge `klimiter.shed.cpu.load` (última carga do processo, `-1` normalizado a 0). */
    @Bean
    @ConditionalOnProperty(prefix = "klimiter.shed.cpu", name = ["enabled"], havingValue = "true")
    fun cpuLoadSampler(properties: ShedProperties, registry: MeterRegistry): CpuLoadSampler {
        val sampler = CpuLoadSampler(properties.cpu.sampleInterval.toMillis())
        Gauge.builder("klimiter.shed.cpu.load", sampler) { it().coerceAtLeast(0.0) }.register(registry)
        return sampler
    }

    /** Portão por CPU: corta acima do limiar, contando em `klimiter.shed.count{reason=cpu}`. */
    @Bean
    @GlobalServerInterceptor
    @Order(SHED_CPU_ORDER)
    @ConditionalOnProperty(prefix = "klimiter.shed.cpu", name = ["enabled"], havingValue = "true")
    fun cpuShedServerInterceptor(
        properties: ShedProperties,
        sampler: CpuLoadSampler,
        registry: MeterRegistry,
    ): ServerInterceptor {
        val shed = registry.counter("klimiter.shed.count", "reason", "cpu")
        return CpuShedServerInterceptor(properties.cpu.threshold, sampler, onShed = shed::increment)
    }

    /**
     * Limitador de concorrência adaptativo (Gradient2): teto de chamadas em voo guiado pelo RTT.
     * O corte contabiliza `klimiter.shed.count{reason=concurrency}` via `statusSupplier` (chamado a
     * cada drop); o teto/RTT internos saem em `klimiter.shed.concurrency.*` pela ponte Micrometer.
     */
    @Bean
    @GlobalServerInterceptor
    @Order(SHED_CONCURRENCY_ORDER)
    @ConditionalOnProperty(prefix = "klimiter.shed.concurrency", name = ["enabled"], havingValue = "true")
    fun concurrencyLimitServerInterceptor(properties: ShedProperties, registry: MeterRegistry): ServerInterceptor {
        val config = properties.concurrency
        val metrics = MicrometerLimitMetricRegistry(registry)
        val limit = Gradient2Limit.newBuilder()
            .initialLimit(config.initialLimit)
            .minLimit(config.minLimit)
            .maxConcurrency(config.maxConcurrency)
            .rttTolerance(config.rttTolerance)
            .metricRegistry(metrics)
            .build()
        // Sem partições → SimpleLimiter com o teto cheio (um só limitador global para o serviço).
        val limiter = GrpcServerLimiterBuilder()
            .limit(limit)
            .metricRegistry(metrics)
            .build()
        val shed = registry.counter("klimiter.shed.count", "reason", "concurrency")
        return ConcurrencyLimitServerInterceptor.newBuilder(limiter)
            .statusSupplier {
                shed.increment()
                Status.UNAVAILABLE.withDescription("concurrency limit")
            }
            .build()
    }

    private companion object {
        // Precedência alta e determinística: CPU antes do limitador adaptativo (o gate mais barato
        // primeiro). Valores pequenos = mais externos no encadeamento `interceptForward`.
        const val SHED_CPU_ORDER = 10
        const val SHED_CONCURRENCY_ORDER = 20
    }
}
