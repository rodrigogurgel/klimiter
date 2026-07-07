package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
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
