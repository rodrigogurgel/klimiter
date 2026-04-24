package io.klimiter.core.api.rls

import java.time.Duration

data class RateLimitStatus(
    val code: RateLimitCode,
    val currentLimit: RateLimit? = null,
    val limitRemaining: Int = 0,
    val durationUntilReset: Duration? = null
)