package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.spi.CompositeKeyGenerator
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.core.api.spi.SystemTimeProvider
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import io.klimiter.core.internal.operation.DefaultRateLimitOperationFactory
import org.slf4j.LoggerFactory
import kotlin.time.Duration

internal class DefaultKLimiterBuilder : KLimiterBuilder {
    private val domains: MutableMap<String, RateLimitDomain> = mutableMapOf()
    private var maxCacheSize: Long? = null
    private var gracePeriod: Duration = InMemoryRateLimitStore.DEFAULT_GRACE_PERIOD
    private var customFactory: RateLimitOperationFactory? = null

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

    override fun operationFactory(factory: RateLimitOperationFactory): KLimiterBuilder = apply {
        customFactory = factory
    }

    override fun build(): KLimiter {
        val custom = customFactory
        if (custom != null) {
            // In-memory-only knobs do not apply to a user-supplied factory; silently
            // ignoring them would mask misconfigured deployments (e.g. a Redis factory
            // combined with a maxCacheSize the user expects to take effect).
            check(domains.isEmpty()) {
                "addDomain/addDomains must not be used with operationFactory — the custom " +
                    "factory owns domain matching"
            }
            check(maxCacheSize == null) {
                "maxCacheSize applies to the default in-memory backend only"
            }
            check(gracePeriod == InMemoryRateLimitStore.DEFAULT_GRACE_PERIOD) {
                "gracePeriod applies to the default in-memory backend only"
            }
            logger.info("KLimiter built backend={}", custom::class.simpleName)
            return DefaultKLimiter(custom)
        }
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
            "KLimiter built backend=in-memory domains={} maxCacheSize={} gracePeriod={}",
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
