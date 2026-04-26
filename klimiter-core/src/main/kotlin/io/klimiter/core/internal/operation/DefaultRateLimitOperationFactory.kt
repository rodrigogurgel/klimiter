package io.klimiter.core.internal.operation

import io.klimiter.core.api.config.DescriptorPath
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.spi.KeyGenerator
import io.klimiter.core.api.spi.RateLimitDomainRepository
import io.klimiter.core.api.spi.RateLimitOperation
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.core.api.spi.TimeProvider
import io.klimiter.core.internal.store.InMemoryRateLimitStore

internal class DefaultRateLimitOperationFactory(
    private val domainRepository: RateLimitDomainRepository,
    private val store: InMemoryRateLimitStore,
    private val keyGenerator: KeyGenerator,
    private val timeProvider: TimeProvider,
) : RateLimitOperationFactory {

    override fun create(request: RateLimitRequest): List<RateLimitOperation> {
        val domain = domainRepository.findById(request.domain) ?: return emptyList()
        return request.descriptors.mapNotNull { descriptor ->
            buildOperation(request, descriptor, domain)
        }
    }

    private fun buildOperation(
        request: RateLimitRequest,
        descriptor: RateLimitRequestDescriptor,
        domain: RateLimitDomain,
    ): RateLimitOperation? {
        val paths = descriptor.entries
            .map { DescriptorPath(it.key, it.value.ifBlank { null }) }
            .toTypedArray()

        val rule = domain.findByPath(*paths)
            ?.takeUnless { it.isWhitelisted }
            ?.rule
            ?.takeUnless { it.unlimited }
            ?: return null

        val requestsPerUnit = descriptor.limit?.requestsPerUnit ?: rule.requestsPerUnit
        val unit = descriptor.limit?.unit ?: rule.unit
        val windowSeconds = unit.windowSeconds()

        val key = keyGenerator.generate(
            domain = request.domain,
            entries = descriptor.entries,
            windowDivider = windowSeconds,
            timeProvider = timeProvider,
        )

        val limit = RateLimit(
            requestsPerUnit = requestsPerUnit,
            unit = unit,
            name = rule.name,
        )
        val counter = store.getOrCreate(key, windowSeconds)

        return InMemoryRateLimitOperation(
            key = key,
            limit = limit,
            hitsAddend = effectiveHitsAddend(request, descriptor),
            windowSeconds = windowSeconds,
            counter = counter,
            timeProvider = timeProvider,
        )
    }

    private fun effectiveHitsAddend(request: RateLimitRequest, descriptor: RateLimitRequestDescriptor): Long {
        val base = descriptor.hitsAddend ?: request.hitsAddend.toLong()
        return if (descriptor.isNegativeHits) -base else base
    }
}
