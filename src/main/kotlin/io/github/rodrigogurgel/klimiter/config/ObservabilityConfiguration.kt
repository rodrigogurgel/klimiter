package io.github.rodrigogurgel.klimiter.config

import io.micrometer.core.instrument.binder.grpc.GrpcServerObservationContext
import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ThreadLocalRandom

/**
 * Amostragem da observação por-RPC do servidor gRPC. O `ObservationGrpcServerInterceptor` cria uma
 * `Observation` (timer + span + contexto) **por chamada** — o maior overhead da telemetria no hot
 * path (OBSERVABILIDADE.md §1.2 / SATURACAO.md §5.2). Este predicado mantém só uma fração das
 * observações `grpc.server`; as demais viram NOOP (custo ~zero). Outras observações passam sempre.
 *
 * O Spring Boot aplica os `ObservationPredicate` do contexto ao `ObservationRegistry`.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
    @Bean
    fun grpcServerObservationSampler(properties: ObservabilityProperties): ObservationPredicate =
        ObservationPredicate { _, context ->
            shouldObserve(context is GrpcServerObservationContext, properties.grpcSampleRate) {
                ThreadLocalRandom.current().nextDouble()
            }
        }
}

/**
 * Decide se uma observação deve ser registrada. Observações que não são do servidor gRPC passam
 * sempre; as do gRPC são amostradas por [sampleRate] (1.0 = todas, 0.0 = nenhuma) usando [random]
 * (`[0,1)`). Extraído para teste sem instanciar o contexto do Micrometer.
 */
internal fun shouldObserve(isGrpcServer: Boolean, sampleRate: Double, random: () -> Double): Boolean = when {
    !isGrpcServer -> true
    sampleRate >= 1.0 -> true
    sampleRate <= 0.0 -> false
    else -> random() < sampleRate
}
