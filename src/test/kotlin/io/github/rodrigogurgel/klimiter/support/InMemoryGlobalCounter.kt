package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import kotlin.time.Duration

/**
 * Contador global em memória que espelha a semântica dos scripts Lua, para testar o core sem Redis.
 * Não thread-safe: uso em testes single-threaded.
 */
class InMemoryGlobalCounter : GlobalCounter {
    private val counters = HashMap<String, Long>()

    fun leasedOf(key: String): Long = counters[key] ?: 0

    override suspend fun tryAcquire(
        key: String,
        capacity: Long,
        hits: Long,
        priority: Priority,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): AcquireResult {
        val counter = counters[key] ?: 0
        val threshold = when (priority) {
            Priority.HIGH -> capacity
            Priority.LOW -> ReleaseLine.line(capacity, elapsed, duration)
        }
        return if (hits > 0 && counter + hits <= threshold) {
            val total = counter + hits
            counters[key] = total
            AcquireResult(admitted = true, counter = total)
        } else {
            AcquireResult(admitted = false, counter = counter)
        }
    }

    override suspend fun lease(key: String, capacity: Long, requested: Long, ttl: Duration): LeaseResult {
        val leased = counters[key] ?: 0
        val granted = if (leased < capacity) minOf(requested, capacity - leased) else 0
        val total = leased + granted
        counters[key] = total
        return LeaseResult(granted, (capacity - total).coerceAtLeast(0))
    }

    override suspend fun paceLease(
        key: String,
        capacity: Long,
        missing: Long,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): PaceResult {
        val leased = counters[key] ?: 0
        val admitted = leased + missing <= ReleaseLine.line(capacity, elapsed, duration)
        val total = if (admitted && missing > 0) leased + missing else leased
        if (admitted && missing > 0) counters[key] = total
        return PaceResult(admitted, (capacity - total).coerceAtLeast(0))
    }
}
