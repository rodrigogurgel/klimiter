package io.klimiter.core.api

import io.klimiter.core.api.spi.KeyGenerator
import io.klimiter.core.api.spi.RateLimitDomainRepository
import kotlin.time.Duration

object KLimiterFactory {

    /**
     * In-memory backend backed by Caffeine. No external dependency; suitable for single-node
     * deployments or local development.
     *
     * @param domainRepository source of rate-limit domain configurations.
     * @param maxCacheSize maximum number of active buckets; null = unbounded.
     * @param gracePeriod extra TTL beyond the window to absorb GC/scheduler jitter.
     */
    fun inMemory(
        domainRepository: RateLimitDomainRepository,
        maxCacheSize: Long? = null,
        gracePeriod: Duration? = null,
        keyGenerator: KeyGenerator? = null,
    ): KLimiter {
        val builder = KLimiterBuilder.create()
            .domainRepository(domainRepository)

        maxCacheSize?.let(builder::maxCacheSize)
        gracePeriod?.let(builder::gracePeriod)
        keyGenerator?.let(builder::keyGenerator)

        return builder.build()
    }
}
