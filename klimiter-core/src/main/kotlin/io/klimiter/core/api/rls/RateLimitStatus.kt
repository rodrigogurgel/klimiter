package io.klimiter.core.api.rls

import kotlin.time.Duration

data class RateLimitStatus(
    val code: RateLimitCode,
    val currentLimit: RateLimit? = null,
    val limitRemaining: Int = 0,
    val durationUntilReset: Duration? = null,
)
