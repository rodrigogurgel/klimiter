package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitResponse
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitServiceGrpcKt
import io.grpc.StatusException
import io.grpc.Status as GrpcStatus

/**
 * Adapter de entrada gRPC (§7): handler `suspend` (modelo corrotina-por-request) que delega ao
 * [EvaluateUseCase]. O cancelamento do RPC propaga para as reservas pela estrutura concurrency
 * (§7.5). A tradução proto↔domínio fica no [Mapping] (R2). É registrado como bean no wiring (Fase 7);
 * o Spring gRPC associa cada bean `BindableService` ao servidor.
 *
 * Entrada inválida — lote maior que [maxBatchSize] ou descriptor que viola os invariantes dos Value
 * Objects (ex.: `key` vazia) — é rejeitada aqui com `INVALID_ARGUMENT`, antes de tocar o core.
 */
class RateLimitService(private val useCase: EvaluateUseCase, private val maxBatchSize: Int) :
    RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {
    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        if (request.descriptorsCount > maxBatchSize) {
            throw invalidArgument("lote com ${request.descriptorsCount} descriptors excede o máximo de $maxBatchSize")
        }
        val batch = try {
            Mapping.toBatch(request)
        } catch (invalid: IllegalArgumentException) {
            throw invalidArgument(invalid.message ?: "descriptor inválido", cause = invalid)
        }
        return Mapping.toResponse(useCase.evaluate(batch))
    }

    private fun invalidArgument(description: String, cause: Throwable? = null): StatusException =
        StatusException(GrpcStatus.INVALID_ARGUMENT.withDescription(description).withCause(cause))
}
