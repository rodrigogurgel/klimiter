package io.klimiter.klimiterservice.adapter.output.klimiter

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.klimiterservice.config.KLimiterProperties
import io.klimiter.klimiterservice.domain.model.RateLimitDecision
import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus
import io.klimiter.klimiterservice.domain.port.output.RateLimitEnforcerPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class KLimiterCoreAdapter(private val properties: KLimiterProperties, private val limiter: KLimiter) :
    RateLimitEnforcerPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun enforce(key: RateLimitKey): RateLimitKeyStatus {
        val now = Instant.now()
        return runCatching {
            val response = limiter.shouldRateLimit(toRequest(key))
            response.toDomain(
                key = key,
                fallbackLimit = properties.default.limit,
                fallbackUnit = properties.default.unit,
                now = now,
            )
        }.getOrElse { cause ->
            logger.error("Failed to apply rate limit on key='{}' value='{}'", key.key, key.value, cause)
            errorStatus(key, now)
        }
    }

    private fun toRequest(key: RateLimitKey): RateLimitRequest {
        val descriptor = RateLimitRequestDescriptor(
            entries = listOf(RateLimitDescriptorEntry(key.key, key.value)),
            hitsAddend = key.cost.takeIf { it > 0L },
        )
        return RateLimitRequest(
            domain = properties.domainId,
            descriptors = listOf(descriptor),
        )
    }

    private fun errorStatus(key: RateLimitKey, now: Instant): RateLimitKeyStatus {
        val windowSeconds = properties.default.unit.windowSeconds()
        val resetAt = now.epochSecond + windowSeconds
        return RateLimitKeyStatus(
            key = key.key,
            value = key.value,
            decision = RateLimitDecision.ERROR,
            limit = properties.default.limit.toLong(),
            remaining = 0L,
            windowSeconds = windowSeconds,
            windowStartEpochSeconds = resetAt - windowSeconds,
            resetAtEpochSeconds = resetAt,
        )
    }
}
