package io.klimiter.core.api.rls

data class RateLimitHeader(
    val key: String,
    val value: String
)