package io.klimiter.core.internal.port

import io.klimiter.core.api.rls.RateLimitDescriptorEntry

internal interface KeyGenerator {
    fun generate(
        domain: String,
        entries: List<RateLimitDescriptorEntry>,
        windowDivider: Long,
        timeProvider: TimeProvider
    ): String
}
