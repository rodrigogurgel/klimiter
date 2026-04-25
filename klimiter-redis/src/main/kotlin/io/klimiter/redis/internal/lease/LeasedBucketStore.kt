package io.klimiter.redis.internal.lease

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Process-wide cache of [LeasedBucket]s keyed by the fully-qualified (window-scoped) bucket key.
 *
 * Each bucket lives `windowSeconds + gracePeriod`, so stale state for last-window's key is
 * discarded automatically once the new window's key takes over. The grace absorbs GC and
 * scheduler drift — same rationale as the in-memory backend.
 */
internal class LeasedBucketStore(maxCacheSize: Long? = null, gracePeriod: Duration = DEFAULT_GRACE_PERIOD) {
    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .expireAfter(EntryExpiry(gracePeriod.inWholeNanoseconds))
        .apply { if (maxCacheSize != null) maximumSize(maxCacheSize) }
        .removalListener<String, Entry> { key, _, cause ->
            if (logger.isDebugEnabled) logger.debug("Leased bucket evicted key={} cause={}", key, cause)
        }
        .build()

    fun getOrCreate(key: String, ttlSeconds: Long): LeasedBucket {
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }
        return cache.get(key) { Entry(LeasedBucket(), ttlSeconds) }.bucket
    }

    private class Entry(val bucket: LeasedBucket, val ttlSeconds: Long)

    private class EntryExpiry(private val gracePeriodNanos: Long) : Expiry<String, Entry> {
        override fun expireAfterCreate(key: String, value: Entry, currentTime: Long): Long =
            TimeUnit.SECONDS.toNanos(value.ttlSeconds) + gracePeriodNanos

        override fun expireAfterUpdate(key: String, value: Entry, currentTime: Long, currentDuration: Long): Long =
            currentDuration

        override fun expireAfterRead(key: String, value: Entry, currentTime: Long, currentDuration: Long): Long =
            currentDuration
    }

    companion object {
        val DEFAULT_GRACE_PERIOD: Duration = 30.seconds
        private val logger = LoggerFactory.getLogger(LeasedBucketStore::class.java)
    }
}
