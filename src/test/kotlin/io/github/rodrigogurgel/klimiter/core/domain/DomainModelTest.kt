package io.github.rodrigogurgel.klimiter.core.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class DomainModelTest {
    @Test
    fun `hits rejects negative values and flags zero`() {
        assertFailsWith<IllegalArgumentException> { Hits(-1) }
        assertTrue(Hits(0).isZero)
        assertEquals(5L, Hits(5).value)
    }

    @Test
    fun `remaining rejects negative values`() {
        assertFailsWith<IllegalArgumentException> { Remaining(-1) }
        assertEquals(Remaining(0), Remaining.NONE)
    }

    @Test
    fun `pass-through decision admits without charging`() {
        val decision = Decision.PASS_THROUGH
        assertEquals(Status.ALLOWED, decision.status)
        assertEquals(Remaining.NONE, decision.remaining)
        assertEquals(ReportedCapacity.NONE, decision.capacity)
        assertEquals(Duration.ZERO, decision.resetAfter)
    }

    @Test
    fun `dimension and value reject blank and expose raw via toString`() {
        assertFailsWith<IllegalArgumentException> { Dimension("  ") }
        assertFailsWith<IllegalArgumentException> { DimensionValue("") }
        assertEquals("user_id", Dimension("user_id").toString())
        assertEquals("user-42", DimensionValue("user-42").toString())
    }

    @Test
    fun `request carries the limit axis as value objects`() {
        val request = Request(Dimension("user_id"), DimensionValue("user-42"), Hits(1), Priority.HIGH)
        assertEquals("user_id", request.dimension.raw)
        assertEquals("user-42", request.value.raw)
        assertEquals(Priority.HIGH, request.priority)
    }

    @Test
    fun `batch result preserves overall status and decision order`() {
        val result = BatchResult(Status.DENIED, listOf(Decision.PASS_THROUGH))
        assertEquals(Status.DENIED, result.overall)
        assertEquals(1, result.decisions.size)
    }
}
