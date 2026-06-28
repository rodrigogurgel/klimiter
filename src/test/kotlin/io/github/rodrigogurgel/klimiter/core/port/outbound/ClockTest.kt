package io.github.rodrigogurgel.klimiter.core.port.outbound

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClockTest {
    @Test
    fun `nowEpochSecond floors millis to whole seconds`() {
        val clock = Clock { 1_700_000_042_999 }
        assertEquals(1_700_000_042, clock.nowEpochSecond())
    }

    @Test
    fun `nowEpochSecond floors correctly before the epoch`() {
        val clock = Clock { -1 } // floorDiv(-1, 1000) = -1
        assertEquals(-1, clock.nowEpochSecond())
    }
}
