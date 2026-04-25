package io.klimiter.core.internal.infra.store

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

internal class InMemoryRateLimitStore(maxCacheSize: Long? = null, gracePeriod: Duration = DEFAULT_GRACE_PERIOD) {
    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .expireAfter(EntryExpiry(gracePeriod.inWholeNanoseconds))
        .apply { if (maxCacheSize != null) maximumSize(maxCacheSize) }
        .removalListener<String, Entry> { key, _, cause ->
            if (logger.isDebugEnabled) logger.debug("Bucket evicted key={} cause={}", key, cause)
        }
        .build()

    // Tracks recently-created bucket keys independently of the main cache so that
    // re-creations of the same time-bucketed key (the concurrent-window leak signature)
    // can be detected and logged without touching the hot path. Fire-and-forget — if this
    // cache loses an entry via its own TTL or maxSize, we just miss a warning, never
    // affect the rate-limit decision.
    private val recentlyCreated: Cache<String, Long> = Caffeine.newBuilder()
        .expireAfterWrite(RECENTLY_CREATED_TTL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .maximumSize(RECENTLY_CREATED_MAX_SIZE)
        .build()

    fun getOrCreate(key: String, ttlSeconds: Long): AtomicLong {
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }
        return cache.get(key) {
            val previousNanos = recentlyCreated.getIfPresent(key)
            if (previousNanos != null) {
                val ageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - previousNanos)
                logger.warn(
                    "Bucket recreated key={} previous_age_ms={} ttl_s={} — possible concurrent-window leak; " +
                        "consider increasing gracePeriod or reviewing maxCacheSize",
                    key,
                    ageMs,
                    ttlSeconds,
                )
            } else if (logger.isTraceEnabled) {
                logger.trace("Bucket created key={} ttl={}s", key, ttlSeconds)
            }
            recentlyCreated.put(key, System.nanoTime())
            Entry(AtomicLong(0L), ttlSeconds)
        }.counter
    }

    private class Entry(val counter: AtomicLong, val ttlSeconds: Long)

    private class EntryExpiry(private val gracePeriodNanos: Long) : Expiry<String, Entry> {
        override fun expireAfterCreate(key: String, value: Entry, currentTime: Long): Long =
            TimeUnit.SECONDS.toNanos(value.ttlSeconds) + gracePeriodNanos

        override fun expireAfterUpdate(key: String, value: Entry, currentTime: Long, currentDuration: Long): Long =
            currentDuration

        override fun expireAfterRead(key: String, value: Entry, currentTime: Long, currentDuration: Long): Long =
            currentDuration
    }

    companion object {
        // Grace period applied on top of the window TTL to absorb GC/scheduler delays between
        // the clock read and cache.get. Without it, time-bucketed keys can be recreated by
        // Caffeine within the same logical window, doubling the limit. The default covers the
        // realistic worst case short of pathological swap/full-GC scenarios: Kubernetes CPU
        // throttle bursts (up to ~10s), large-heap Parallel GC pauses, and coroutine scheduler
        // starvation under heavy load. See the "concurrent-window leak" bug history.
        val DEFAULT_GRACE_PERIOD: Duration = 30.seconds

        // Upper bound for detecting recreation of the same bucket key. Sized to cover any
        // realistic rate-limit window (SECOND up to HOUR) so the warning fires even when the
        // recreation happens near the end of the logical window. DAY+ rules may slip past it,
        // but those are unlikely to trigger the leak in the first place.
        private val RECENTLY_CREATED_TTL: Duration = 1.hours
        private const val RECENTLY_CREATED_MAX_SIZE: Long = 10_000L

        private val logger = LoggerFactory.getLogger(InMemoryRateLimitStore::class.java)
    }
}
