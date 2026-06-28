package io.github.rodrigogurgel.klimiter.adapter.outbound.clock

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SystemClockTest {
    @Test
    fun `nowMillis advances monotonically with wall clock`() {
        val clock = SystemClock()
        val before = System.currentTimeMillis()
        val now = clock.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after, "nowMillis $now fora de [$before, $after]")
    }

    @Test
    fun `nowEpochSecond is consistent with nowMillis`() {
        val clock = SystemClock()
        assertTrue(clock.nowEpochSecond() <= clock.nowMillis() / 1000 + 1)
    }
}
