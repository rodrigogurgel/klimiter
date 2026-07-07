package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Remaining
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.github.rodrigogurgel.klimiter.support.RecordingRateLimitMetrics
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Cobre o estado local do V2 (§5): inspeção deny-only, reserva confirmada pelo central com
 * aprendizado do snapshot, negação local de graça após o aprendizado e evicção.
 */
class LocalStateTest {
    private val policy = Policy(Capacity(100), RateLimitUnit.MINUTE)
    private val dimension = Dimension("user_id")
    private val value = DimensionValue("u1")
    private val now = 60_000L // epochSecond 60 → janela MINUTE [60,120)

    /** Conta os round-trips de [tryAcquire] para provar as negações locais (§5.2). */
    private class CountingCounter(private val delegate: InMemoryGlobalCounter = InMemoryGlobalCounter()) :
        GlobalCounter by delegate {
        var tryAcquires = 0
            private set

        override suspend fun tryAcquire(key: String, threshold: Long, hits: Long, ttl: Duration): AcquireResult {
            tryAcquires++
            return delegate.tryAcquire(key, threshold, hits, ttl)
        }
    }

    private fun state(counter: GlobalCounter, metrics: RateLimitMetrics = RateLimitMetrics.NOOP) =
        LocalState(counter, "klimiter", metrics)

    private fun LocalState.bucket() = bucketFor(dimension, value, policy, epochSecond(now))

    @Test
    fun `inspect allows zero hits and denies above the capacity`() {
        val state = state(CountingCounter())
        val bucket = state.bucket()
        assertEquals(Status.ALLOWED, state.inspect(bucket, 0, Priority.HIGH, now).status)
        assertEquals(Status.DENIED, state.inspect(bucket, 101, Priority.HIGH, now).status)
    }

    @Test
    fun `inspect is inconclusive (allowed) before any central observation`() {
        val state = state(CountingCounter())
        val bucket = state.bucket()
        // snapshot 0 é lower-bound trivial: nada é negação garantida ainda (§5.1).
        assertEquals(Status.ALLOWED, state.inspect(bucket, 100, Priority.HIGH, now).status)
    }

    @Test
    fun `reserve confirms at the central and learns the snapshot`() = runTest {
        val counter = CountingCounter()
        val metrics = RecordingRateLimitMetrics()
        val state = state(counter, metrics)
        val bucket = state.bucket()

        val decision = state.reserve(bucket, 1, Priority.HIGH, now)

        assertEquals(Status.ALLOWED, decision.status)
        assertEquals(Remaining(99), decision.remaining) // snapshot aprendido da resposta (§5.1)
        assertEquals(1, counter.tryAcquires)
        assertEquals(1L, metrics.reservedHits)
        assertEquals(
            RecordingRateLimitMetrics.Reserve(Priority.HIGH, Status.ALLOWED, DecisionOrigin.CENTRAL),
            metrics.reserves.single(),
        )
    }

    @Test
    fun `a central denial latches - the next reserve denies locally with zero round-trips`() = runTest {
        val counter = CountingCounter()
        val metrics = RecordingRateLimitMetrics()
        val state = state(counter, metrics)
        val bucket = state.bucket()

        assertEquals(Status.ALLOWED, state.reserve(bucket, 100, Priority.HIGH, now).status) // enche a janela
        assertEquals(Status.DENIED, state.reserve(bucket, 1, Priority.HIGH, now).status) // central nega, aprende
        val roundTripsAfterLearning = counter.tryAcquires

        assertEquals(Status.DENIED, state.reserve(bucket, 1, Priority.HIGH, now).status)
        assertEquals(roundTripsAfterLearning, counter.tryAcquires) // §5.2: esgotado terminal, de graça
        assertTrue(bucket.exhausted)
        assertEquals(DecisionOrigin.LOCAL, metrics.reserves.last().origin)
    }

    @Test
    fun `LOW learns the line - denials above it become local`() = runTest {
        val counter = CountingCounter()
        val state = state(counter)
        val bucket = state.bucket()
        val midWindow = 90_000L // decorrido 30s de 60s → linha = floor(100*30/60)+1 = 51

        assertEquals(Status.ALLOWED, state.reserve(bucket, 51, Priority.LOW, midWindow).status)
        assertEquals(Status.DENIED, state.reserve(bucket, 1, Priority.LOW, midWindow).status) // central nega
        val roundTripsAfterLearning = counter.tryAcquires

        // snapshot 51 + 1 > linha 51 → negação local exata até a linha subir (§5.2).
        assertEquals(Status.DENIED, state.reserve(bucket, 1, Priority.LOW, midWindow).status)
        assertEquals(roundTripsAfterLearning, counter.tryAcquires)
    }

    @Test
    fun `denials raise the observed pressure of the key`() = runTest {
        val state = state(CountingCounter())
        val bucket = state.bucket()
        assertEquals(0.0, state.pressureOf(dimension, value))

        state.reserve(bucket, 100, Priority.HIGH, now)
        repeat(10) { state.reserve(bucket, 1, Priority.HIGH, now) }

        assertTrue(state.pressureOf(dimension, value) > 0.5)
    }

    @Test
    fun `evictExpired removes buckets of finished windows`() {
        val state = state(CountingCounter())
        state.bucket() // janela [60,120)
        assertEquals(1, state.size())

        assertEquals(0, state.evictExpired(nowMillis = 100_000)) // ainda dentro da janela
        assertEquals(1, state.evictExpired(nowMillis = 130_000)) // janela vencida
        assertEquals(0, state.size())
    }
}
