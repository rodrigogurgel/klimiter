package io.klimiter.core.api

import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.internal.DefaultKLimiterBuilder

interface KLimiterBuilder {
    fun addDomain(domain: RateLimitDomain): KLimiterBuilder
    fun addDomains(domains: Collection<RateLimitDomain>): KLimiterBuilder
    fun lockStripes(count: Int): KLimiterBuilder
    fun build(): KLimiter

    companion object {
        fun create(): KLimiterBuilder = DefaultKLimiterBuilder()
    }
}
