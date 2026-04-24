package io.klimiter.klimiterservice.domain.model

data class RateLimitKeyStatus(
    val key: String,
    val value: String,
    val decision: RateLimitDecision,
    val limit: Long,
    val remaining: Long,
    val windowSeconds: Long,
    val windowStartEpochSeconds: Long,
    val resetAtEpochSeconds: Long,
)
