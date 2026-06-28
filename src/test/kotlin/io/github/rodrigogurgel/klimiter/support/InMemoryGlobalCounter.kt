package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import kotlin.time.Duration

/**
 * Contador global em memória que espelha a semântica dos scripts Lua (lease, pace_lease), para testar
 * o core sem Redis. Não thread-safe: uso em testes single-threaded.
 */
class InMemoryGlobalCounter : GlobalCounter {
    private val counters = HashMap<String, Long>()

    fun leasedOf(key: String): Long = counters[key] ?: 0

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
