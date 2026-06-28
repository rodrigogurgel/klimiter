package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.BucketKey
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.domain.Window
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PacingTest {
    // Janela de 60s; "agora" a 30s → linha = floor(cap * 30/60) + 1.
    private val now = 30_000L

    private fun bucket(capacity: Long): Bucket =
        Bucket(BucketKey(Dimension("d"), DimensionValue("v"), 0), "k", capacity, Window(0, 60.seconds))

    @Test
    fun `admits below the release line and increments the counter`() = runTest {
        val counter = InMemoryGlobalCounter()
        val reservation = Pacing.reserveLow(bucket(1000), counter, hits = 1, nowMillis = now)
        assertEquals(Status.ALLOWED, reservation.decision.status)
        assertEquals(1, counter.leasedOf("k"))
    }

    @Test
    fun `denies above the release line without incrementing`() = runTest {
        val counter = InMemoryGlobalCounter()
        counter.lease("k", 1000, 501, 60.seconds) // contador já na linha (501 em 30s)
        val reservation = Pacing.reserveLow(bucket(1000), counter, hits = 1, nowMillis = now)
        assertEquals(Status.DENIED, reservation.decision.status)
        assertEquals(501, counter.leasedOf("k"))
    }

    @Test
    fun `drains local credit and leases only the missing`() = runTest {
        val counter = InMemoryGlobalCounter()
        val bucket = bucket(1000).apply { onLeaseResult(granted = 3, free = 997) }
        val reservation = Pacing.reserveLow(bucket, counter, hits = 5, nowMillis = now)
        assertEquals(Status.ALLOWED, reservation.decision.status)
        assertEquals(2, counter.leasedOf("k")) // faltante = 5 − 3
        assertEquals(0, bucket.localCredit) // drenou os 3
    }

    @Test
    fun `pre-gate denies locally without a round-trip`() = runTest {
        val counter = InMemoryGlobalCounter()
        val bucket = bucket(1000).apply { publishFreeGlobal(0) } // snapshot diz cheio → estLeased > linha
        val reservation = Pacing.reserveLow(bucket, counter, hits = 1, nowMillis = now)
        assertEquals(Status.DENIED, reservation.decision.status)
        assertEquals(0, counter.leasedOf("k")) // pré-portão: sem round-trip
    }
}
