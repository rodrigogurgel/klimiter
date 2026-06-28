package io.github.rodrigogurgel.klimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ajustes de observabilidade do klimiter.
 *
 * @property grpcSampleRate fração das observações por-RPC do servidor gRPC (`grpc.server`) a registrar:
 *   `1.0` = todas (default), `0.0` = nenhuma. As não-amostradas viram NOOP (custo ~zero). Sob CPU
 *   apertada, reduzir recupera throughput ao custo de telemetria amostrada (ver `docs/SATURACAO.md`
 *   §5.2). Não afeta os contadores de negócio `klimiter.*` nem o export. Env:
 *   `KLIMITER_OBSERVABILITY_GRPC_SAMPLE_RATE`.
 */
@ConfigurationProperties(prefix = "klimiter.observability")
data class ObservabilityProperties(val grpcSampleRate: Double = 1.0) {
    init {
        require(grpcSampleRate in 0.0..1.0) {
            "grpc-sample-rate deve estar entre 0.0 e 1.0, recebido $grpcSampleRate"
        }
    }
}
