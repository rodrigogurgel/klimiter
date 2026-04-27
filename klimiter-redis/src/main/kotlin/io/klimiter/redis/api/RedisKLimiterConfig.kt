package io.klimiter.redis.api

import io.klimiter.core.spi.CompositeKeyGenerator
import io.klimiter.core.spi.KeyGenerator
import io.klimiter.core.spi.SystemTimeProvider
import io.klimiter.core.spi.TimeProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the Redis-backed, lease-based KLimiter operation factory.
 *
 * @property keyPrefix Prepended to every bucket key so KLimiter can share a Redis with other
 *   apps without collisions. Empty disables prefixing.
 * @property keyGenerator How bucket keys are derived from (domain, entries, window). Default is
 *   [CompositeKeyGenerator], the same one used by the in-memory backend.
 * @property timeProvider Clock source. Tests can inject a deterministic provider.
 * @property leasePercentage Fraction (1–100) of the per-window limit each node requests from
 *   Redis on renewal. Smaller values smooth out distribution across nodes at the cost of more
 *   renewal round-trips; larger values are throughput-efficient but grant a single node a
 *   bigger share. 10% is a reasonable starting point.
 * @property gracePeriod Extra time each local [com.github.benmanes.caffeine.cache.Cache] bucket
 *   stays alive beyond its window TTL, absorbing GC / scheduler drift between the clock read
 *   and cache lookup. Applies to the in-process Caffeine store only.
 * @property redisKeyGracePeriod Extra seconds added to the Redis key TTL beyond the window
 *   length (`windowSeconds + redisKeyGracePeriod`). Keeps the key alive long enough for
 *   in-flight lease renewals to complete after the window boundary. Smaller than [gracePeriod]
 *   because Redis TTL drift is negligible compared to local GC pauses.
 * @property maxTrackedBuckets Upper bound on distinct bucket keys held locally. Null =
 *   unbounded; set this when key cardinality can be driven by untrusted input (e.g. arbitrary
 *   `user_id`s).
 */
data class RedisKLimiterConfig(
    val keyPrefix: String = DEFAULT_KEY_PREFIX,
    val keyGenerator: KeyGenerator = CompositeKeyGenerator,
    val timeProvider: TimeProvider = SystemTimeProvider,
    val leasePercentage: Int = DEFAULT_LEASE_PERCENTAGE,
    val gracePeriod: Duration = DEFAULT_GRACE_PERIOD,
    val redisKeyGracePeriod: Duration = DEFAULT_REDIS_KEY_GRACE_PERIOD,
    val maxTrackedBuckets: Long? = null,
) {
    init {
        require(leasePercentage in MIN_LEASE_PERCENTAGE..MAX_LEASE_PERCENTAGE) {
            "leasePercentage must be between $MIN_LEASE_PERCENTAGE and $MAX_LEASE_PERCENTAGE"
        }
        require(!gracePeriod.isNegative()) { "gracePeriod must not be negative" }
        require(!redisKeyGracePeriod.isNegative()) { "redisKeyGracePeriod must not be negative" }
        require(maxTrackedBuckets == null || maxTrackedBuckets > 0) {
            "maxTrackedBuckets must be > 0 or null"
        }
    }

    companion object {
        val DEFAULT_GRACE_PERIOD: Duration = 5.seconds
        val DEFAULT_REDIS_KEY_GRACE_PERIOD: Duration = 10.seconds
        const val DEFAULT_KEY_PREFIX: String = "klimiter"
        const val DEFAULT_LEASE_PERCENTAGE: Int = 10
        const val MIN_LEASE_PERCENTAGE: Int = 1
        const val MAX_LEASE_PERCENTAGE: Int = 100
    }
}
