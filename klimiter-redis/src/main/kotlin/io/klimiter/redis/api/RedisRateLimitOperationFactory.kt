package io.klimiter.redis.api

import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.redis.internal.RedisRateLimitOperationFactory
import io.klimiter.redis.internal.command.ClusterRedisCommandExecutor
import io.klimiter.redis.internal.command.RedisCommandExecutor
import io.klimiter.redis.internal.command.StandaloneRedisCommandExecutor
import io.klimiter.redis.internal.lease.LeasedBucketStore
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection

/**
 * Builders for Redis-backed, lease-based [RateLimitOperationFactory] instances. Each variant
 * matches one of the three deployment topologies Lettuce supports: standalone, Sentinel, and
 * Cluster. Sentinel and standalone share the same connection type; Lettuce resolves the master
 * behind the scenes.
 *
 * Example:
 * ```
 * val factory = RedisRateLimitOperationFactory.standalone(
 *     domains = listOf(apiDomain),
 *     connection = redisClient.connect(),
 * )
 * val limiter = KLimiterBuilder.create().operationFactory(factory).build()
 * ```
 */
object RedisRateLimitOperationFactory {

    /** Standalone Redis. Also, the right choice for Sentinel — pass a sentinel-aware connection. */
    fun standalone(
        domains: Collection<RateLimitDomain>,
        connection: StatefulRedisConnection<String, String>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): RateLimitOperationFactory = build(
        domains = domains,
        executor = StandaloneRedisCommandExecutor(connection),
        config = config,
    )

    /** Redis Cluster. Commands are routed by key hash slot; scripts broadcast on SCRIPT LOAD. */
    fun cluster(
        domains: Collection<RateLimitDomain>,
        connection: StatefulRedisClusterConnection<String, String>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): RateLimitOperationFactory = build(
        domains = domains,
        executor = ClusterRedisCommandExecutor(connection),
        config = config,
    )

    private fun build(
        domains: Collection<RateLimitDomain>,
        executor: RedisCommandExecutor,
        config: RedisKLimiterConfig,
    ): RateLimitOperationFactory = RedisRateLimitOperationFactory(
        domains = domains.indexById(),
        keyGenerator = config.keyGenerator,
        timeProvider = config.timeProvider,
        executor = executor,
        bucketStore = LeasedBucketStore(
            maxCacheSize = config.maxTrackedBuckets,
            gracePeriod = config.gracePeriod,
        ),
        keyPrefix = config.keyPrefix,
        leasePercentage = config.leasePercentage,
    )

    private fun Collection<RateLimitDomain>.indexById(): Map<String, RateLimitDomain> {
        val result = LinkedHashMap<String, RateLimitDomain>(size)
        for (domain in this) {
            require(result.put(domain.id, domain) == null) {
                "domain '${domain.id}' registered more than once"
            }
        }
        return result
    }
}
