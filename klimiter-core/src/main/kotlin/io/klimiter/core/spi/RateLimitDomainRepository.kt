package io.klimiter.core.spi

import io.klimiter.core.api.config.RateLimitDomain

/**
 * Resolves a [RateLimitDomain] by its ID on every request. The default implementation is
 * [StaticRateLimitDomainRepository] (immutable map). Custom implementations can load domains
 * from files, databases, or remote configuration — they are responsible for any caching needed
 * to keep the hot path fast, since [findById] is called on every [io.klimiter.core.api.KLimiter.shouldRateLimit]
 * invocation.
 */
interface RateLimitDomainRepository {
    fun findById(id: String): RateLimitDomain?
}
