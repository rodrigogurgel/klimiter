package io.klimiter.service.adapter.output.klimiter

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.service.domain.port.output.RateLimitEnforcerPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class KLimiterCoreAdapter(private val kLimiter: KLimiter) : RateLimitEnforcerPort {

    override suspend fun enforce(request: RateLimitRequest): RateLimitResponse =
        runCatching { kLimiter.shouldRateLimit(request) }
            .getOrElse { ex ->
                logger.error("Rate limit check failed for domain '{}'", request.domain, ex)
                RateLimitResponse(overallCode = RateLimitCode.UNKNOWN)
            }

    private companion object {
        private val logger = LoggerFactory.getLogger(KLimiterCoreAdapter::class.java)
    }
}
