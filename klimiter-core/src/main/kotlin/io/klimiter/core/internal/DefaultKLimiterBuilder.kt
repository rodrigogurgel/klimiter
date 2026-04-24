package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.internal.infra.key.CompositeKeyGenerator
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import io.klimiter.core.internal.infra.time.SystemTimeProvider
import io.klimiter.core.internal.operation.DefaultRateLimitOperationFactory
import org.slf4j.LoggerFactory
import kotlin.time.Duration

internal class DefaultKLimiterBuilder : KLimiterBuilder {
    private val domains: MutableMap<String, RateLimitDomain> = mutableMapOf()
    private var maxCacheSize: Long? = null
    private var gracePeriod: Duration = InMemoryRateLimitStore.DEFAULT_GRACE_PERIOD

    override fun addDomain(domain: RateLimitDomain): KLimiterBuilder = apply {
        require(domains.put(domain.id, domain) == null) {
            "domain '${domain.id}' already registered"
        }
    }

    override fun addDomains(domains: Collection<RateLimitDomain>): KLimiterBuilder = apply {
        domains.forEach { addDomain(it) }
    }

    override fun maxCacheSize(size: Long): KLimiterBuilder = apply {
        require(size > 0) { "maxCacheSize must be > 0" }
        maxCacheSize = size
    }

    override fun gracePeriod(duration: Duration): KLimiterBuilder = apply {
        require(!duration.isNegative()) { "gracePeriod must not be negative" }
        gracePeriod = duration
    }

    override fun build(): KLimiter {
        check(domains.isNotEmpty()) { "at least one domain must be registered" }

        val store = InMemoryRateLimitStore(
            maxCacheSize = maxCacheSize,
            gracePeriod = gracePeriod,
        )
        val factory = DefaultRateLimitOperationFactory(
            domains = domains.toMap(),
            store = store,
            keyGenerator = CompositeKeyGenerator,
            timeProvider = SystemTimeProvider,
        )
        logger.info(
            "KLimiter built domains={} maxCacheSize={} gracePeriod={}",
            domains.keys,
            maxCacheSize ?: "unbounded",
            gracePeriod,
        )
        return DefaultKLimiter(factory)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultKLimiterBuilder::class.java)
    }
}
