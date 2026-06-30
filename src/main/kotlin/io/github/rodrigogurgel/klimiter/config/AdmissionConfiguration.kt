package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.ConcurrencyLimit
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.ConcurrencyLimitInterceptor
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.FixedConcurrencyLimit
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.GradientConcurrencyLimit
import io.grpc.ServerInterceptor
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor

/**
 * Registra o controle de admissão como interceptor global do gRPC conforme `klimiter.admission.mode`:
 * `fixed` → [FixedConcurrencyLimit], `adaptive` → [GradientConcurrencyLimit]. Ausente/`off` ⟹ nenhum
 * bean (interceptor fora da cadeia, overhead zero — default). `HIGHEST_PRECEDENCE`: roda antes de
 * tudo (inclusive da observação por-RPC), para um shed nem pagar a telemetria.
 */
@Configuration(proxyBeanMethods = false)
class AdmissionConfiguration {
    @Bean
    @GlobalServerInterceptor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(prefix = "klimiter.admission", name = ["mode"], havingValue = "fixed")
    fun fixedAdmissionInterceptor(properties: AdmissionProperties, registry: MeterRegistry): ServerInterceptor =
        register(FixedConcurrencyLimit(properties.maxInflight), registry)

    @Bean
    @GlobalServerInterceptor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(prefix = "klimiter.admission", name = ["mode"], havingValue = "adaptive")
    fun adaptiveAdmissionInterceptor(properties: AdmissionProperties, registry: MeterRegistry): ServerInterceptor {
        val a = properties.adaptive
        val limit = GradientConcurrencyLimit(
            a.initialLimit,
            a.minLimit,
            a.maxLimit,
            a.window.toMillis(),
            a.target.toMillis(),
        )
        return register(limit, registry)
    }

    /** Monta o interceptor sobre a [limit] e registra os gauges de teto e in-flight. */
    private fun register(limit: ConcurrencyLimit, registry: MeterRegistry): ConcurrencyLimitInterceptor {
        val interceptor = ConcurrencyLimitInterceptor(limit, registry.counter("klimiter.admission.shed"))
        Gauge.builder("klimiter.admission.limit", limit) { it.current.toDouble() }.register(registry)
        Gauge.builder("klimiter.admission.inflight", interceptor) { it.inFlight().toDouble() }.register(registry)
        return interceptor
    }
}
