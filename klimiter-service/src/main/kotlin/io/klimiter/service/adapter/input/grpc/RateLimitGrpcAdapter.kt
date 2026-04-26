package io.klimiter.service.adapter.input.grpc

import io.klimiter.generated.service.proto.RateLimitRequest
import io.klimiter.generated.service.proto.RateLimitResponse
import io.klimiter.generated.service.proto.RateLimitServiceGrpcKt
import io.klimiter.service.domain.port.input.CheckRateLimitUseCase
import org.slf4j.LoggerFactory
import org.springframework.grpc.server.service.GrpcService

@GrpcService
class RateLimitGrpcAdapter(private val checkRateLimitUseCase: CheckRateLimitUseCase) :
    RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {

    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        if (logger.isDebugEnabled) {
            logger.debug(
                "ShouldRateLimit domain='{}' descriptors={}",
                request.domain,
                request.descriptorsCount,
            )
        }
        return runCatching {
            val coreRequest = request.toCoreRequest()
            val coreResponse = checkRateLimitUseCase.check(coreRequest)
            coreResponse.toProtoResponse()
        }.getOrElse { ex ->
            logger.error(
                "Unexpected error in ShouldRateLimit domain='{}' descriptors={}",
                request.domain,
                request.descriptorsCount,
                ex,
            )
            RateLimitResponse.newBuilder()
                .setOverallCode(RateLimitResponse.Code.UNKNOWN)
                .build()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RateLimitGrpcAdapter::class.java)
    }
}
