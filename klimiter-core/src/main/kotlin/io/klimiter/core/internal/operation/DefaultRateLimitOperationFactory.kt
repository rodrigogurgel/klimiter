package io.klimiter.core.internal.operation

import io.klimiter.core.api.common.RateLimitTimeUnit
import io.klimiter.core.api.config.DescriptorPath
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import io.klimiter.core.internal.port.KeyGenerator
import io.klimiter.core.internal.port.TimeProvider
import io.klimiter.core.api.rls.RateLimitDescriptor as RequestDescriptor

internal class DefaultRateLimitOperationFactory(
    private val domains: Map<String, RateLimitDomain>,
    private val store: InMemoryRateLimitStore,
    private val keyGenerator: KeyGenerator,
    private val timeProvider: TimeProvider,
) : RateLimitOperationFactory {

    override fun create(request: RateLimitRequest): List<RateLimitOperation> {
        val domain = domains[request.domain] ?: return emptyList()
        return request.descriptors.mapNotNull { descriptor ->
            buildOperation(request, descriptor, domain)
        }
    }

    private fun buildOperation(
        request: RateLimitRequest,
        descriptor: RequestDescriptor,
        domain: RateLimitDomain,
    ): RateLimitOperation? {
        val paths = descriptor.entries
            .map { DescriptorPath(it.key, it.value.ifBlank { null }) }
            .toTypedArray()

        val matched = domain.findByPath(*paths) ?: return null
        if (matched.isWhitelisted) return null
        val rule = matched.rule ?: return null
        if (rule.unlimited) return null

        val requestsPerUnit = descriptor.limit?.requestsPerUnit ?: rule.requestsPerUnit
        val unit = descriptor.limit?.unit ?: rule.unit
        val windowSeconds = unit.toWindowSeconds()

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

    private fun effectiveHitsAddend(
        request: RateLimitRequest,
        descriptor: RequestDescriptor,
    ): Long {
        val base = descriptor.hitsAddend ?: request.hitsAddend.toLong()
        return if (descriptor.isNegativeHits) -base else base
    }

    private fun RateLimitTimeUnit.toWindowSeconds(): Long = when (this) {
        RateLimitTimeUnit.SECOND -> SECONDS_PER_SECOND
        RateLimitTimeUnit.MINUTE -> SECONDS_PER_MINUTE
        RateLimitTimeUnit.HOUR -> SECONDS_PER_HOUR
        RateLimitTimeUnit.DAY -> SECONDS_PER_DAY
        RateLimitTimeUnit.WEEK -> SECONDS_PER_WEEK
        RateLimitTimeUnit.MONTH -> SECONDS_PER_MONTH
        RateLimitTimeUnit.YEAR -> SECONDS_PER_YEAR
        RateLimitTimeUnit.UNKNOWN -> error("RateLimitTimeUnit.UNKNOWN is not supported")
    }

    private companion object {
        const val SECONDS_PER_SECOND = 1L
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val SECONDS_PER_DAY = 86_400L
        const val SECONDS_PER_WEEK = 604_800L
        const val SECONDS_PER_MONTH = 2_592_000L
        const val SECONDS_PER_YEAR = 31_536_000L
    }
}
