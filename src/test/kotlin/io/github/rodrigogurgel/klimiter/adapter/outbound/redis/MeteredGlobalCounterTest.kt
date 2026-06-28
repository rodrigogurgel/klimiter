package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Cobre o decorator de métricas: delega ao [GlobalCounter] e cronometra cada round-trip por op. */
class MeteredGlobalCounterTest {
    private val registry = SimpleMeterRegistry()
    private val metered = MeteredGlobalCounter(InMemoryGlobalCounter(), registry)

    @Test
    fun `lease delegates and records the roundtrip timer tagged lease`() = runBlocking {
        val result = metered.lease("k", capacity = 10, requested = 4, ttl = 1.minutes)
        assertEquals(4, result.granted)
        assertEquals(6, result.freeGlobal)
        val timer = registry.find("klimiter.central.roundtrip").tag("op", "lease").timer()
        assertEquals(1L, timer?.count())
    }

    @Test
    fun `paceLease delegates and records the roundtrip timer tagged pace_lease`() = runBlocking {
        val result = metered.paceLease(
            "k",
            capacity = 1000,
            missing = 1,
            elapsed = 30.seconds,
            duration = 60.seconds,
            ttl = 1.minutes,
        )
        assertTrue(result.admitted)
        val timer = registry.find("klimiter.central.roundtrip").tag("op", "pace_lease").timer()
        assertEquals(1L, timer?.count())
    }

    @Test
    fun `close is a no-op when the delegate is not AutoCloseable`() {
        metered.close() // InMemoryGlobalCounter não é AutoCloseable → não deve lançar
    }
}
