package io.klimiter.core.internal.infra.store

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

internal class InMemoryRateLimitStore {
    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .expireAfter(EntryExpiry())
        .build()

    fun getOrCreate(key: String, ttlSeconds: Long): AtomicLong {
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }
        return cache.get(key) { Entry(AtomicLong(0L), ttlSeconds) }.counter
    }

    private class Entry(val counter: AtomicLong, val ttlSeconds: Long)

    private class EntryExpiry : Expiry<String, Entry> {
        override fun expireAfterCreate(key: String, value: Entry, currentTime: Long): Long =
            TimeUnit.SECONDS.toNanos(value.ttlSeconds + 1.minutes.inWholeSeconds)

        override fun expireAfterUpdate(
            key: String,
            value: Entry,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration

        override fun expireAfterRead(
            key: String,
            value: Entry,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration
    }
}
