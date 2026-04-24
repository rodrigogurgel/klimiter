package io.klimiter.klimiterservice.adapter.output.klimiter

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.klimiterservice.config.KLimiterProperties
import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus
import io.klimiter.klimiterservice.domain.port.output.RateLimitEnforcerPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import io.klimiter.core.api.rls.RateLimitDescriptor as RlsDescriptor

@Component
class KLimiterCoreAdapter(
    private val properties: KLimiterProperties,
) : RateLimitEnforcerPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val limiters = ConcurrentHashMap<String, KLimiter>()

    override suspend fun enforce(key: RateLimitKey): RateLimitKeyStatus {
        val now = Instant.now()
        return runCatching {
            val limiter = limiters.computeIfAbsent(key.key, ::buildLimiter)
            val response = limiter.shouldRateLimit(toRequest(key))
            response.toDomain(
                key = key,
                fallbackLimit = properties.default.limit,
                fallbackUnit = properties.default.unit,
                now = now,
            )
        }.getOrElse { cause ->
            logger.error("Falha ao aplicar rate limit em key='{}' value='{}'", key.key, key.value, cause)
            errorStatus(key, now)
        }
    }

    private fun toRequest(key: RateLimitKey): RateLimitRequest {
        val descriptor = RlsDescriptor(
            entries = listOf(RateLimitDescriptorEntry(key.key, key.value)),
            hitsAddend = key.cost.takeIf { it > 0L },
        )
        return RateLimitRequest(
            domain = properties.domainId,
            descriptors = listOf(descriptor),
        )
    }

    private fun buildLimiter(keyName: String): KLimiter {
        val domain = RateLimitDomain(
            id = properties.domainId,
            descriptors = listOf(
                RateLimitDescriptor(
                    key = keyName,
                    rule = RateLimitRule(
                        unit = properties.default.unit,
                        requestsPerUnit = properties.default.limit,
                    ),
                ),
            ),
        )
        return KLimiterBuilder.create()
            .addDomain(domain)
            .lockStripes(properties.stripesCount)
            .build()
    }

    private fun errorStatus(key: RateLimitKey, now: Instant): RateLimitKeyStatus {
        val windowSeconds = properties.default.unit.toSeconds()
        val resetAt = now.epochSecond + windowSeconds
        return RateLimitKeyStatus(
            key = key.key,
            value = key.value,
            decision = io.klimiter.klimiterservice.domain.model.RateLimitDecision.ERROR,
            limit = properties.default.limit.toLong(),
            remaining = 0L,
            windowSeconds = windowSeconds,
            windowStartEpochSeconds = resetAt - windowSeconds,
            resetAtEpochSeconds = resetAt,
        )
    }
}
