package io.klimiter.redis.internal

import io.klimiter.core.api.config.DescriptorPath
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.spi.KeyGenerator
import io.klimiter.core.spi.RateLimitDomainRepository
import io.klimiter.core.spi.RateLimitOperation
import io.klimiter.core.spi.RateLimitOperationFactory
import io.klimiter.core.spi.TimeProvider
import io.klimiter.redis.internal.command.RedisCommandExecutor
import io.klimiter.redis.internal.lease.LeasedBucketStore
import org.slf4j.LoggerFactory
import kotlin.time.Duration

internal class RedisRateLimitOperationFactory(
    private val domainRepository: RateLimitDomainRepository,
    private val keyGenerator: KeyGenerator,
    private val timeProvider: TimeProvider,
    private val executor: RedisCommandExecutor,
    private val bucketStore: LeasedBucketStore,
    private val keyPrefix: String,
    private val leasePercentage: Int,
    private val redisKeyGracePeriod: Duration,
) : RateLimitOperationFactory {

    override fun create(request: RateLimitRequest): List<RateLimitOperation> {
        val domain = domainRepository.findById(request.domain)
        if (domain == null) {
            logger.debug("Domain not found, all descriptors will pass through domain={}", request.domain)
            return emptyList()
        }
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

        val baseKey = keyGenerator.generate(
            domain = request.domain,
            entries = descriptor.entries,
            windowDivider = windowSeconds,
            timeProvider = timeProvider,
        )
        val key = prefixed(baseKey)

        val limit = RateLimit(
            requestsPerUnit = requestsPerUnit,
            unit = unit,
            name = rule.name,
        )

        return RedisRateLimitOperation(
            key = key,
            limit = limit,
            hitsAddend = effectiveHitsAddend(request, descriptor),
            windowSeconds = windowSeconds,
            bucket = bucketStore.getOrCreate(key, windowSeconds),
            executor = executor,
            timeProvider = timeProvider,
            leasePercentage = leasePercentage,
            redisKeyGracePeriod = redisKeyGracePeriod,
        )
    }

    private fun prefixed(key: String): String = if (keyPrefix.isEmpty()) key else "$keyPrefix:$key"

    private fun effectiveHitsAddend(request: RateLimitRequest, descriptor: RateLimitRequestDescriptor): Long {
        val base = descriptor.hitsAddend ?: request.hitsAddend.toLong()
        return if (descriptor.isNegativeHits) -base else base
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RedisRateLimitOperationFactory::class.java)
    }
}
