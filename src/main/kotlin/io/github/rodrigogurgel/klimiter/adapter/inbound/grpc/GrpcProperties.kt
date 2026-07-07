package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuração da borda gRPC de entrada.
 *
 * @property maxBatchSize nº máximo de descriptors por `RateLimitRequest`. Lote acima disso é
 *   rejeitado com `INVALID_ARGUMENT` antes de tocar o core — cada item pode virar corrotina +
 *   round-trip ao central (§7), então um lote ilimitado é superfície de abuso. Sobrescrevível por
 *   `KLIMITER_GRPC_MAX_BATCH_SIZE`.
 */
@ConfigurationProperties(prefix = "klimiter.grpc")
data class GrpcProperties(val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE) {
    init {
        require(maxBatchSize > 0) { "max-batch-size deve ser > 0, recebido $maxBatchSize" }
    }

    private companion object {
        const val DEFAULT_MAX_BATCH_SIZE = 100
    }
}
