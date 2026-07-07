package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Decorator de [GlobalCounter] que cronometra cada round-trip ao contador
 * (`klimiter.central.roundtrip`, tag `op`), sem tocar o core nem a implementação Lettuce — a
 * instrumentação fica no boundary (OBSERVABILIDADE.md §1.2). Delega o ciclo de vida ([close]) ao
 * adapter envolvido.
 */
class MeteredGlobalCounter(private val delegate: GlobalCounter, registry: MeterRegistry) :
    GlobalCounter,
    AutoCloseable {
    private val tryAcquireTimer = registry.timer(ROUNDTRIP, OP, "try_acquire")
    private val leaseTimer = registry.timer(ROUNDTRIP, OP, "lease")
    private val paceLeaseTimer = registry.timer(ROUNDTRIP, OP, "pace_lease")

    override suspend fun tryAcquire(
        key: String,
        capacity: Long,
        hits: Long,
        priority: Priority,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): AcquireResult = timed(tryAcquireTimer) {
        delegate.tryAcquire(key, capacity, hits, priority, elapsed, duration, ttl)
    }

    override suspend fun lease(key: String, capacity: Long, requested: Long, ttl: Duration): LeaseResult =
        timed(leaseTimer) { delegate.lease(key, capacity, requested, ttl) }

    override suspend fun paceLease(
        key: String,
        capacity: Long,
        missing: Long,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): PaceResult = timed(paceLeaseTimer) { delegate.paceLease(key, capacity, missing, elapsed, duration, ttl) }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }

    private suspend fun <T> timed(timer: Timer, block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    private companion object {
        const val ROUNDTRIP = "klimiter.central.roundtrip"
        const val OP = "op"
    }
}
