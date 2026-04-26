package io.klimiter.core.spi

import io.klimiter.core.api.rls.RateLimitDescriptorEntry

/**
 * Builds the cache/store key for a rate-limit bucket. Implementations must produce a key that
 * uniquely identifies (domain, descriptor-entries, time-window) so that separate windows never
 * collide and parallel requests for the same (domain, entries) within the same window share
 * the same counter.
 *
 * Default: [CompositeKeyGenerator].
 */
interface KeyGenerator {
    fun generate(
        domain: String,
        entries: List<RateLimitDescriptorEntry>,
        windowDivider: Long,
        timeProvider: TimeProvider,
    ): String
}
