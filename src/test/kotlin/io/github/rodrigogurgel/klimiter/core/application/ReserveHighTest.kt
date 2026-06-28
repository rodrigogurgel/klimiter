package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.BucketKey
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.domain.Window
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.github.rodrigogurgel.klimiter.support.RecordingRateLimitMetrics
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ReserveHighTest {
    private val now = 100_000L
    private val metrics = RecordingRateLimitMetrics()

    private fun bucket(capacity: Long): Bucket =
        Bucket(BucketKey(Dimension("d"), DimensionValue("v"), 100), "k", capacity, Window(100, 60.seconds))

    @Test
    fun `L1 serves from local credit without touching the counter`() = runTest {
        val counter = InMemoryGlobalCounter()
        val bucket = bucket(100).apply { onLeaseResult(granted = 10, free = 90) }
        val reservation = ReserveHigh.reserve(bucket, counter, prefetchBlock = 50, hits = 3, nowMillis = now, metrics)
        assertEquals(Status.ALLOWED, reservation.decision.status)
        assertEquals(0, counter.leasedOf("k")) // nenhum round-trip
        assertEquals(listOf(ReservePath.L1), metrics.highPaths)
    }

    @Test
    fun `L2 denies fast when the bucket already learned it is exhausted`() = runTest {
        val counter = InMemoryGlobalCounter()
        val bucket = bucket(10).apply { publishFreeGlobal(0) } // latch de esgotado já aprendido
        val reservation = ReserveHigh.reserve(bucket, counter, prefetchBlock = 5, hits = 1, nowMillis = now, metrics)
        assertEquals(Status.DENIED, reservation.decision.status)
        assertEquals(0, counter.leasedOf("k")) // sem round-trip
        assertEquals(listOf(ReservePath.L2), metrics.highPaths)
    }

    @Test
    fun `L3 denies when not even the best renewal would serve`() = runTest {
        val counter = InMemoryGlobalCounter()
        val reservation = ReserveHigh.reserve(
            bucket(100),
            counter,
            prefetchBlock = 10,
            hits = 101,
            nowMillis = now,
            metrics,
        )
        assertEquals(Status.DENIED, reservation.decision.status) // hits > capacidade
        assertEquals(0, counter.leasedOf("k")) // sem round-trip
        assertEquals(listOf(ReservePath.L3), metrics.highPaths)
    }

    @Test
    fun `L4 renews a prefetch block and serves the request`() = runTest {
        val counter = InMemoryGlobalCounter()
        val bucket = bucket(100)
        val reservation = ReserveHigh.reserve(bucket, counter, prefetchBlock = 20, hits = 1, nowMillis = now, metrics)
        assertEquals(Status.ALLOWED, reservation.decision.status)
        assertEquals(20, counter.leasedOf("k")) // arrendou o bloco
        assertEquals(19, bucket.localCredit) // 20 − 1
        assertEquals(listOf(ReservePath.L4), metrics.highPaths)
    }

    @Test
    fun `L4 when the counter was exhausted by others ends in a denial`() = runTest {
        val counter = InMemoryGlobalCounter()
        counter.lease("k", capacity = 10, requested = 10, ttl = 60.seconds) // outro nó encheu
        val reservation = ReserveHigh.reserve(
            bucket(10),
            counter,
            prefetchBlock = 5,
            hits = 1,
            nowMillis = now,
            metrics,
        )
        assertEquals(Status.DENIED, reservation.decision.status)
        assertEquals(listOf(ReservePath.L4), metrics.highPaths) // tentou renovar (round-trip) antes de negar
    }
}
