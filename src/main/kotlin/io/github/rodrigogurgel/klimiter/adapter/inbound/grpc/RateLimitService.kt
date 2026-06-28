package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitResponse
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitServiceGrpcKt

/**
 * Adapter de entrada gRPC (§7): handler `suspend` (modelo corrotina-por-request) que delega ao
 * [EvaluateUseCase]. O cancelamento do RPC propaga para as reservas pela estrutura concurrency
 * (§7.4). A tradução proto↔domínio fica no [Mapping] (R2). É registrado como bean no wiring (Fase 7);
 * o Spring gRPC associa todo bean `BindableService` ao servidor.
 */
class RateLimitService(private val useCase: EvaluateUseCase) :
    RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {
    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse =
        Mapping.toResponse(useCase.evaluate(Mapping.toBatch(request)))
}
