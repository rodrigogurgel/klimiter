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
    fun `starts with no local credit and full free global`() {
        val bucket = bucket(100)
        assertEquals(0, bucket.localCredit)
        assertEquals(100, bucket.freeGlobal)
        assertFalse(bucket.exhausted)
    }

    @Test
    fun `tryConsumeLocal consumes when enough and refuses otherwise`() {
        val bucket = bucket(100)
        bucket.onLeaseResult(granted = 10, free = 90)
        assertTrue(bucket.tryConsumeLocal(4))
        assertEquals(6, bucket.localCredit)
        assertFalse(bucket.tryConsumeLocal(7)) // só 6 disponíveis
        assertEquals(6, bucket.localCredit)
    }

    @Test
    fun `tryConsumeUpTo drains at most what is available`() {
        val bucket = bucket(100)
        bucket.onLeaseResult(granted = 5, free = 95)
        assertEquals(5, bucket.tryConsumeUpTo(8)) // só 5 cabiam
        assertEquals(0, bucket.localCredit)
        assertEquals(0, bucket.tryConsumeUpTo(3)) // nada sobrou
    }

    @Test
    fun `refundLocal returns credit`() {
        val bucket = bucket(100)
        bucket.onLeaseResult(10, 90)
        bucket.tryConsumeLocal(10)
        bucket.refundLocal(10)
        assertEquals(10, bucket.localCredit)
    }

    @Test
    fun `publishFreeGlobal is monotonic and latches exhausted at zero`() {
        val bucket = bucket(100)
        bucket.publishFreeGlobal(40)
        assertEquals(40, bucket.freeGlobal)
        bucket.publishFreeGlobal(70) // fora de ordem (maior) → descartado (§4.3)
        assertEquals(40, bucket.freeGlobal)
        bucket.publishFreeGlobal(0)
        assertTrue(bucket.exhausted)
        assertEquals(0, bucket.freeGlobal)
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
}
