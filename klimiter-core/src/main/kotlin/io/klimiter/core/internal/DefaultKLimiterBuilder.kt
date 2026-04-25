package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.spi.CompositeKeyGenerator
import io.klimiter.core.api.spi.KeyGenerator
import io.klimiter.core.api.spi.RateLimitDomainRepository
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.core.api.spi.SystemTimeProvider
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import io.klimiter.core.internal.operation.DefaultRateLimitOperationFactory
import org.slf4j.LoggerFactory
import kotlin.time.Duration

internal class DefaultKLimiterBuilder : KLimiterBuilder {
    private var domainRepository: RateLimitDomainRepository? = null
    private var maxCacheSize: Long? = null
    private var gracePeriod: Duration = InMemoryRateLimitStore.DEFAULT_GRACE_PERIOD
    private var keyGenerator: KeyGenerator = CompositeKeyGenerator
    private var customFactory: RateLimitOperationFactory? = null

    override fun domainRepository(repository: RateLimitDomainRepository): KLimiterBuilder = apply {
        domainRepository = repository
    }

    override fun maxCacheSize(size: Long): KLimiterBuilder = apply {
        require(size > 0) { "maxCacheSize must be > 0" }
        maxCacheSize = size
    }

    override fun gracePeriod(duration: Duration): KLimiterBuilder = apply {
        require(!duration.isNegative()) { "gracePeriod must not be negative" }
        gracePeriod = duration
    }

    override fun keyGenerator(generator: KeyGenerator): KLimiterBuilder = apply {
        keyGenerator = generator
    }

    override fun operationFactory(factory: RateLimitOperationFactory): KLimiterBuilder = apply {
        customFactory = factory
    }

    override fun build(): KLimiter {
        val custom = customFactory
        if (custom != null) {
            check(domainRepository == null) {
                "domainRepository must not be used with operationFactory — the custom factory owns domain matching"
            }
            check(maxCacheSize == null) {
                "maxCacheSize applies to the default in-memory backend only"
            }
            check(gracePeriod == InMemoryRateLimitStore.DEFAULT_GRACE_PERIOD) {
                "gracePeriod applies to the default in-memory backend only"
            }
            check(keyGenerator === CompositeKeyGenerator) {
                "keyGenerator applies to the default in-memory backend only"
            }
            logger.info("KLimiter built backend={}", custom::class.simpleName)
            return DefaultKLimiter(custom)
        }
        val repo = checkNotNull(domainRepository) { "domainRepository must be configured" }
        val store = InMemoryRateLimitStore(
            maxCacheSize = maxCacheSize,
            gracePeriod = gracePeriod,
        )
        val factory = DefaultRateLimitOperationFactory(
            domainRepository = repo,
            store = store,
            keyGenerator = keyGenerator,
            timeProvider = SystemTimeProvider,
        )
        logger.info(
            "KLimiter built backend=in-memory maxCacheSize={} gracePeriod={}",
            maxCacheSize ?: "unbounded",
            gracePeriod,
        )
        return DefaultKLimiter(factory)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DefaultKLimiterBuilder::class.java)
    }
}
