package io.github.rodrigogurgel.klimiter.core.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WindowTest {
    @Test
    fun `derive aligns the window start to the epoch boundary`() {
        val window = Window.derive(nowEpochSecond = 1_700_000_042, duration = 1.minutes)
        assertEquals(1_700_000_040, window.startEpochSecond) // floor(.../60)*60
        assertEquals(1_700_000_100, window.endEpochSecond)
    }

    @Test
    fun `derive aligns correctly before the epoch via floorDiv`() {
        val window = Window.derive(nowEpochSecond = -1, duration = 1.minutes)
        assertEquals(-60, window.startEpochSecond)
    }

    @Test
    fun `elapsed is clamped to the window bounds`() {
        val window = Window(startEpochSecond = 100, duration = 60.seconds)
        assertEquals(0.seconds, window.elapsed(nowMillis = 50_000)) // antes do início → piso
        assertEquals(30.seconds, window.elapsed(nowMillis = 130_000)) // 30s dentro
        assertEquals(60.seconds, window.elapsed(nowMillis = 999_000)) // após o fim → teto
    }

    @Test
    fun `ttl never goes negative`() {
        val window = Window(startEpochSecond = 100, duration = 60.seconds)
        assertEquals(40.seconds, window.ttl(nowMillis = 120_000))
        assertEquals(0.seconds, window.ttl(nowMillis = 999_000))
    }

    @Test
    fun `rejects non-positive duration`() {
        assertFailsWith<IllegalArgumentException> { Window(startEpochSecond = 0, duration = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { Window.derive(nowEpochSecond = 0, duration = 0.seconds) }
    }
}
