package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.internal.infra.key.CompositeKeyGenerator
import io.klimiter.core.internal.infra.lock.StripedLockManager
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import io.klimiter.core.internal.infra.time.SystemTimeProvider
import io.klimiter.core.internal.participant.DefaultRateLimitParticipantFactory

internal class DefaultKLimiterBuilder : KLimiterBuilder {
    private val domains: MutableMap<String, RateLimitDomain> = mutableMapOf()
    private var lockStripes: Int = DEFAULT_LOCK_STRIPES

    override fun addDomain(domain: RateLimitDomain): KLimiterBuilder = apply {
        require(domains.put(domain.id, domain) == null) {
            "domain '${domain.id}' já registrado"
        }
    }

    override fun addDomains(domains: Collection<RateLimitDomain>): KLimiterBuilder = apply {
        domains.forEach { addDomain(it) }
    }

    override fun lockStripes(count: Int): KLimiterBuilder = apply {
        require(count > 0) { "lockStripes must be > 0" }
        lockStripes = count
    }

    override fun build(): KLimiter {
        check(domains.isNotEmpty()) { "ao menos um domain precisa ser registrado" }

        val factory = DefaultRateLimitParticipantFactory(
            domains = domains.toMap(),
            store = InMemoryRateLimitStore(),
            keyGenerator = CompositeKeyGenerator,
            lockManager = StripedLockManager(lockStripes),
            timeProvider = SystemTimeProvider,
        )
        return DefaultKLimiter(factory)
    }

    private companion object {
        const val DEFAULT_LOCK_STRIPES = 1024
    }
}
