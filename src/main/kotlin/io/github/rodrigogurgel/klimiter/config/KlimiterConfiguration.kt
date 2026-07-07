package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.GrpcProperties
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.RateLimitService
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LettuceGlobalCounter
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.MeteredGlobalCounter
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.RedisProperties
import io.github.rodrigogurgel.klimiter.core.application.BatchEvaluator
import io.github.rodrigogurgel.klimiter.core.application.LocalState
import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.PolicyRepository
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Composition root (regra de dependência §2): só aqui os adapters são instanciados e injetados no
 * core pelas portas. O ciclo de vida do Redis fica a cargo do container (`destroyMethod`).
 * `proxyBeanMethods = false`: não há chamadas inter-`@Bean`, então dispensa o proxy CGLIB.
 */
@Configuration(proxyBeanMethods = false)
class KlimiterConfiguration {
    /** Contador global metrificado (`klimiter.central.roundtrip`) sobre o pool Lettuce (§4), standalone
     *  ou cluster conforme `klimiter.redis.cluster`. O `close` encerra conexões e client (§4). Tipo
     *  concreto p/ o `destroyMethod = "close"` ser resolvível; injetável como [GlobalCounter]. */
    @Bean(destroyMethod = "close")
    fun globalCounter(redis: RedisProperties, registry: MeterRegistry): MeteredGlobalCounter {
        val lettuce = if (redis.cluster) {
            LettuceGlobalCounter.cluster(redis.uri, redis.poolSize, redis.commandTimeout)
        } else {
            LettuceGlobalCounter.standalone(redis.uri, redis.poolSize, redis.commandTimeout)
        }
        return MeteredGlobalCounter(lettuce, registry)
    }

    /** Estado local do V2 (§5) + gauge `klimiter.bucket.index.size` (OBSERVABILIDADE.md §1.2). */
    @Bean
    fun localState(
        globalCounter: GlobalCounter,
        redis: RedisProperties,
        metrics: RateLimitMetrics,
        registry: MeterRegistry,
    ): LocalState {
        val state = LocalState(globalCounter, redis.keyPrefix, metrics)
        Gauge.builder("klimiter.bucket.index.size", state) { it.size().toDouble() }.register(registry)
        return state
    }

    @Bean
    fun evaluateUseCase(
        state: LocalState,
        policies: PolicyRepository,
        clock: Clock,
        metrics: RateLimitMetrics,
    ): EvaluateUseCase = BatchEvaluator(state, policies, clock, metrics)

    /** Registrado como `BindableService` — o Spring gRPC o associa ao servidor (§7). */
    @Bean
    fun rateLimitService(useCase: EvaluateUseCase, grpc: GrpcProperties): RateLimitService =
        RateLimitService(useCase, grpc.maxBatchSize)
}
