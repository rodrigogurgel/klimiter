package io.klimiter.core.api.rls

data class RateLimitDescriptorEntry(
    val key: String,
    val value: String = ""            // blank = wildcard
)