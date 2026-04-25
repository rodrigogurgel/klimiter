package io.klimiter.klimiterservice.adapter.input.grpc

import io.klimiter.klimiterservice.domain.port.input.CheckRateLimitUseCase
import io.klimiter.klimiterservice.proto.KLimiterServiceGrpcKt
import io.klimiter.klimiterservice.proto.ShouldRateLimitRequest
import io.klimiter.klimiterservice.proto.ShouldRateLimitResponse
import org.springframework.stereotype.Service

@Service
class RateLimitGrpcAdapter(private val useCase: CheckRateLimitUseCase) :
    KLimiterServiceGrpcKt.KLimiterServiceCoroutineImplBase() {

    override suspend fun shouldRateLimit(request: ShouldRateLimitRequest): ShouldRateLimitResponse {
        val keys = request.toDomain()
        val result = useCase.check(keys)
        return result.toProto()
    }
}
