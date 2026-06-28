package io.github.rodrigogurgel.klimiter.core.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReleaseLineTest {
    @Test
    fun `admits the first unit at the very start of the window`() {
        assertEquals(1, ReleaseLine.line(capacity = 1000, elapsed = 0.seconds, duration = 1.minutes))
    }

    @Test
    fun `releases the full capacity at or after the window end`() {
        assertEquals(1000, ReleaseLine.line(1000, 1.minutes, 1.minutes))
        assertEquals(1000, ReleaseLine.line(1000, 2.minutes, 1.minutes))
    }

    @Test
    fun `grows linearly across the window`() {
        // metade da janela → floor(1000 * 30000 / 60000) + 1 = 501
        assertEquals(501, ReleaseLine.line(1000, 30.seconds, 1.minutes))
    }

    @Test
    fun `integer floor is never below the float floor so the node is never more permissive`() {
        val cap = 1000L
        val duration = 1.minutes
        var ms = 0L
        while (ms <= duration.inWholeMilliseconds) {
            val line = ReleaseLine.line(cap, ms.milliseconds, duration)
            val floatFloor = Math.floor(cap.toDouble() * ms / duration.inWholeMilliseconds).toLong() + 1
            assertTrue(line >= floatFloor.coerceAtMost(cap), "linha $line < piso flutuante $floatFloor em ${ms}ms")
            ms += 137 // passo arbitrário, varre a janela inteira
        }
    }

    @Test
    fun `stays exact for capacities that overflow Long in the product`() {
        // cap * elapsedMs estoura Long → usa BigInteger; meia janela ≈ metade da capacidade.
        val cap = Long.MAX_VALUE / 2
        val line = ReleaseLine.line(cap, 30.seconds, 1.minutes)
        assertTrue(line in (cap / 2)..(cap / 2 + 1), "linha $line fora do esperado ~cap/2")
    }
}
