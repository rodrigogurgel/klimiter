package io.klimiter.core.spi

import io.klimiter.core.api.config.RateLimitDomain

/**
 * Immutable [RateLimitDomainRepository] backed by a fixed collection of domains. Suitable for
 * configurations that are known at startup and never change. For dynamic or hot-reloadable
 * configurations, implement [RateLimitDomainRepository] directly.
 */
class StaticRateLimitDomainRepository(domains: Collection<RateLimitDomain>) : RateLimitDomainRepository {

    private val map: Map<String, RateLimitDomain>

    init {
        val result = LinkedHashMap<String, RateLimitDomain>(domains.size)
        for (domain in domains) {
            require(result.put(domain.id, domain) == null) {
                "domain '${domain.id}' registered more than once"
            }
        }
        map = result
    }

    override fun findById(id: String): RateLimitDomain? = map[id]
}
