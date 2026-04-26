package io.klimiter.service.adapter.input.grpc

import io.klimiter.generated.service.proto.RateLimitRequest
import io.klimiter.generated.service.proto.RateLimitResponse
import io.klimiter.generated.service.proto.RateLimitServiceGrpcKt
import io.klimiter.service.domain.port.input.CheckRateLimitUseCase
import org.springframework.grpc.server.service.GrpcService

@GrpcService
class RateLimitGrpcAdapter(private val checkRateLimitUseCase: CheckRateLimitUseCase) :
    RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {

    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        val coreRequest = request.toCoreRequest()
        val coreResponse = checkRateLimitUseCase.check(coreRequest)
        return coreResponse.toProtoResponse()
    }
}
