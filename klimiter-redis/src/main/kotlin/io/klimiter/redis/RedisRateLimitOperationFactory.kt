package io.klimiter.redis

import io.klimiter.core.spi.RateLimitDomainRepository
import io.klimiter.core.spi.RateLimitOperationFactory
import io.klimiter.redis.api.RedisKLimiterConfig
import io.klimiter.redis.internal.command.ClusterRedisCommandExecutor
import io.klimiter.redis.internal.command.RedisCommandExecutor
import io.klimiter.redis.internal.command.StandaloneRedisCommandExecutor
import io.klimiter.redis.internal.lease.LeasedBucketStore
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.klimiter.redis.internal.RedisRateLimitOperationFactory as InternalRedisRateLimitOperationFactory

/**
 * Builders for Redis-backed, lease-based [RateLimitOperationFactory] instances. Each variant
 * matches one of the three deployment topologies Lettuce supports: standalone, Sentinel, and
 * Cluster. Sentinel and standalone share the same connection type; Lettuce resolves the master
 * behind the scenes.
 *
 * Example:
 * ```
 * val factory = RedisRateLimitOperationFactory.standalone(
 *     domainRepository = StaticRateLimitDomainRepository(listOf(apiDomain)),
 *     connection = redisClient.connect(),
 * )
 * val limiter = KLimiterBuilder.create().operationFactory(factory).build()
 * ```
 */
object RedisRateLimitOperationFactory {

    /** Standalone Redis. Also, the right choice for Sentinel — pass a sentinel-aware connection. */
    fun standalone(
        domainRepository: RateLimitDomainRepository,
        connection: StatefulRedisConnection<String, String>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): RateLimitOperationFactory = build(
        domainRepository = domainRepository,
        executor = StandaloneRedisCommandExecutor(connection),
        config = config,
    )

    /** Redis Cluster. Commands are routed by key hash slot; scripts broadcast on SCRIPT LOAD. */
    fun cluster(
        domainRepository: RateLimitDomainRepository,
        connection: StatefulRedisClusterConnection<String, String>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): RateLimitOperationFactory = build(
        domainRepository = domainRepository,
        executor = ClusterRedisCommandExecutor(connection),
        config = config,
    )

    private fun build(
        domainRepository: RateLimitDomainRepository,
        executor: RedisCommandExecutor,
        config: RedisKLimiterConfig,
    ): RateLimitOperationFactory = InternalRedisRateLimitOperationFactory(
        domainRepository = domainRepository,
        keyGenerator = config.keyGenerator,
        timeProvider = config.timeProvider,
        executor = executor,
        bucketStore = LeasedBucketStore(
            maxCacheSize = config.maxTrackedBuckets,
            gracePeriod = config.gracePeriod,
        ),
        keyPrefix = config.keyPrefix,
        leasePercentage = config.leasePercentage,
        gracePeriod = config.gracePeriod,
    )
}
