package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressureBookTest {
    private val dimension = Dimension("user_id")
    private val value = DimensionValue("u1")

    @Test
    fun `unseen key has zero pressure`() {
        assertEquals(0.0, PressureBook().pressureOf(dimension, value))
    }

    @Test
    fun `denials raise the pressure and admissions decay it`() {
        val book = PressureBook()
        repeat(50) { book.record(dimension, value, denied = true, nowMillis = 0) }
        val underDenials = book.pressureOf(dimension, value)
        assertTrue(underDenials > 0.9, "pressão sob negações deveria convergir a ~1, foi $underDenials")

        repeat(50) { book.record(dimension, value, denied = false, nowMillis = 0) }
        val afterRecovery = book.pressureOf(dimension, value)
        assertTrue(afterRecovery < 0.1, "pressão após admissões deveria decair a ~0, foi $afterRecovery")
    }

    @Test
    fun `keys are independent`() {
        val book = PressureBook()
        book.record(dimension, value, denied = true, nowMillis = 0)
        assertEquals(0.0, book.pressureOf(dimension, DimensionValue("outro")))
        assertEquals(0.0, book.pressureOf(Dimension("ip"), value))
    }

    @Test
    fun `evictIdle removes only cold keys`() {
        val book = PressureBook()
        book.record(dimension, DimensionValue("fria"), denied = true, nowMillis = 0)
        book.record(dimension, DimensionValue("quente"), denied = true, nowMillis = 600_000)

        val removed = book.evictIdle(nowMillis = 700_000, idleMillis = 300_000)

        assertEquals(1, removed)
        assertEquals(0.0, book.pressureOf(dimension, DimensionValue("fria"))) // esquecida
        assertTrue(book.pressureOf(dimension, DimensionValue("quente")) > 0.0) // preservada
        assertEquals(1, book.size())
    }
}
