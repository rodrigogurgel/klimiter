package io.github.rodrigogurgel.klimiter.core.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BucketTest {
    private fun bucket(capacity: Long = 100): Bucket = Bucket(
        BucketKey(Dimension("user_id"), DimensionValue("u1"), 100),
        storageKey = "klimiter:user_id:u1:100",
        capacity = capacity,
        window = Window(startEpochSecond = 100, duration = 60.seconds),
    )

    @Test
    fun `starts with snapshot zero (trivially true lower bound) and not exhausted`() {
        val bucket = bucket(100)
        assertEquals(0, bucket.snapshot)
        assertFalse(bucket.exhausted)
    }

    @Test
    fun `observe is monotonic - an out-of-order smaller counter is discarded`() {
        val bucket = bucket(100)
        bucket.observe(40)
        assertEquals(40, bucket.snapshot)
        bucket.observe(25) // resposta fora de ordem (menor) → descartada (§5.1)
        assertEquals(40, bucket.snapshot)
        bucket.observe(60)
        assertEquals(60, bucket.snapshot)
    }

    @Test
    fun `exhausted is terminal once the snapshot reaches the capacity`() {
        val bucket = bucket(100)
        bucket.observe(99)
        assertFalse(bucket.exhausted)
        bucket.observe(100)
        assertTrue(bucket.exhausted) // §5.2: o contador nunca desce → cheio é cheio até a janela virar
    }

    @Test
    fun `allowed estimates remaining from the snapshot`() {
        val bucket = bucket(100)
        bucket.observe(30)
        assertEquals(Remaining(70), bucket.allowed(nowMillis = 130_000).remaining)
    }

    @Test
    fun `allowed and denied carry window reset and capacity`() {
        val bucket = bucket(100)
        val allowed = bucket.allowed(nowMillis = 130_000)
        assertEquals(Status.ALLOWED, allowed.status)
        assertEquals(ReportedCapacity(100), allowed.capacity)
        assertEquals(30.seconds, allowed.resetAfter) // fim=160s, agora=130s → 30s
        val denied = bucket.denied(nowMillis = 130_000)
        assertEquals(Status.DENIED, denied.status)
        assertEquals(Remaining.NONE, denied.remaining)
    }

    @Test
    fun `expiry tracks the window end for the eviction sweep`() {
        assertEquals(160_000, bucket().expiryMillis) // janela [100,160)s
    }
}
