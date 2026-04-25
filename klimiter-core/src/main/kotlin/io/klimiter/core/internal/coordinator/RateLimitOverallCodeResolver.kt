package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus

internal object RateLimitOverallCodeResolver {
    fun resolve(statuses: List<RateLimitStatus>): RateLimitCode = when {
        statuses.any { it.code == RateLimitCode.OVER_LIMIT } -> RateLimitCode.OVER_LIMIT
        statuses.all { it.code == RateLimitCode.OK } -> RateLimitCode.OK
        else -> RateLimitCode.UNKNOWN
    }
}
