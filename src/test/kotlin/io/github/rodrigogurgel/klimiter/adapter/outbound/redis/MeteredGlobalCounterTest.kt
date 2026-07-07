package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Cobre o decorator de métricas: delega ao [GlobalCounter] e cronometra cada round-trip por op. */
class MeteredGlobalCounterTest {
    private val registry = SimpleMeterRegistry()
    private val metered = MeteredGlobalCounter(InMemoryGlobalCounter(), registry)

    @Test
    fun `tryAcquire delegates and records the roundtrip timer tagged try_acquire`() = runBlocking {
        val result = metered.tryAcquire("k", threshold = 10, hits = 4, ttl = 1.minutes)
        assertTrue(result.admitted)
        assertEquals(4, result.counter)
        val timer = registry.find("klimiter.central.roundtrip").tag("op", "try_acquire").timer()
        assertEquals(1L, timer?.count())
    }

    @Test
    fun `close is a no-op when the delegate is not AutoCloseable`() {
        metered.close() // InMemoryGlobalCounter não é AutoCloseable → não deve lançar
    }
}
