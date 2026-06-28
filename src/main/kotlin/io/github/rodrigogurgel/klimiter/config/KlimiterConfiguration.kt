package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.RateLimitService
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LettuceGlobalCounter
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.MeteredGlobalCounter
import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.RedisProperties
import io.github.rodrigogurgel.klimiter.core.application.BatchEvaluator
import io.github.rodrigogurgel.klimiter.core.application.LocalBudget
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
            LettuceGlobalCounter.cluster(redis.uri, redis.poolSize)
        } else {
            LettuceGlobalCounter.standalone(redis.uri, redis.poolSize)
        }
        return MeteredGlobalCounter(lettuce, registry)
    }

    /** Índice local de buckets + gauge `klimiter.bucket.index.size` (OBSERVABILIDADE.md §1.2). */
    @Bean
    fun localBudget(
        globalCounter: GlobalCounter,
        redis: RedisProperties,
        metrics: RateLimitMetrics,
        registry: MeterRegistry,
    ): LocalBudget {
        val budget = LocalBudget(globalCounter, redis.keyPrefix, metrics)
        Gauge.builder("klimiter.bucket.index.size", budget) { it.size().toDouble() }.register(registry)
        return budget
    }

    @Bean
    fun evaluateUseCase(
        budget: LocalBudget,
        policies: PolicyRepository,
        clock: Clock,
        metrics: RateLimitMetrics,
    ): EvaluateUseCase = BatchEvaluator(budget, policies, clock, metrics)

    /** Registrado como `BindableService` — o Spring gRPC o associa ao servidor (§7). */
    @Bean
    fun rateLimitService(useCase: EvaluateUseCase): RateLimitService = RateLimitService(useCase)
}
