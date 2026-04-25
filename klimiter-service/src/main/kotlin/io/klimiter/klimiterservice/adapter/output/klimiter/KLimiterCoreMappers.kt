package io.klimiter.klimiterservice.adapter.output.klimiter

import io.klimiter.core.api.common.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.klimiterservice.domain.model.RateLimitDecision
import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus
import java.time.Duration
import java.time.Instant

internal fun RateLimitResponse.toDomain(
    key: RateLimitKey,
    fallbackLimit: Int,
    fallbackUnit: RateLimitTimeUnit,
    now: Instant,
): RateLimitKeyStatus {
    val status = statuses.firstOrNull()
    return if (status == null) {
        unlimitedStatus(key, fallbackLimit, fallbackUnit, now)
    } else {
        status.toDomain(key, fallbackLimit, fallbackUnit, now)
    }
}

private fun RateLimitStatus.toDomain(
    key: RateLimitKey,
    fallbackLimit: Int,
    fallbackUnit: RateLimitTimeUnit,
    now: Instant,
): RateLimitKeyStatus {
    val unit = currentLimit?.unit ?: fallbackUnit
    val windowSeconds = unit.windowSeconds()
    val limit = currentLimit?.requestsPerUnit?.toLong() ?: fallbackLimit.toLong()
    val resetDuration = durationUntilReset ?: Duration.ofSeconds(windowSeconds)
    val resetAt = now.plus(resetDuration).epochSecond
    return RateLimitKeyStatus(
        key = key.key,
        value = key.value,
        decision = code.toDecision(),
        limit = limit,
        remaining = limitRemaining.toLong(),
        windowSeconds = windowSeconds,
        windowStartEpochSeconds = resetAt - windowSeconds,
        resetAtEpochSeconds = resetAt,
    )
}

private fun unlimitedStatus(
    key: RateLimitKey,
    fallbackLimit: Int,
    fallbackUnit: RateLimitTimeUnit,
    now: Instant,
): RateLimitKeyStatus {
    val windowSeconds = fallbackUnit.windowSeconds()
    val resetAt = now.epochSecond + windowSeconds
    return RateLimitKeyStatus(
        key = key.key,
        value = key.value,
        decision = RateLimitDecision.OK,
        limit = fallbackLimit.toLong(),
        remaining = fallbackLimit.toLong(),
        windowSeconds = windowSeconds,
        windowStartEpochSeconds = resetAt - windowSeconds,
        resetAtEpochSeconds = resetAt,
    )
}

private fun RateLimitCode.toDecision(): RateLimitDecision = when (this) {
    RateLimitCode.OK -> RateLimitDecision.OK
    RateLimitCode.OVER_LIMIT -> RateLimitDecision.OVER_LIMIT
    RateLimitCode.UNKNOWN -> RateLimitDecision.ERROR
}
