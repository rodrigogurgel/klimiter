package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GradientConcurrencyLimitTest {
    private fun limit(initial: Int = 50) = GradientConcurrencyLimit(initial, MIN, MAX, WINDOW, TARGET)

    /** Alimenta `count` amostras de `rtt`, uma a cada meia-janela (≈ 2 fecham por janela), sob carga. */
    private fun GradientConcurrencyLimit.drive(rtt: Long, count: Int, startMs: Long = 1000L): Long {
        var t = startMs
        repeat(count) {
            observe(rtt, current, t) // inFlight = current → sob carga
            t += WINDOW / 2
        }
        return t
    }

    @Test
    fun `grows while the mean stays below the target`() {
        val limit = limit(initial = 50)
        limit.drive(rtt = 2, count = 40) // média 2ms < alvo 5ms
        assertTrue(limit.current > 50, "esperava crescer, ficou ${limit.current}")
    }

    @Test
    fun `shrinks substantially when the mean exceeds the target`() {
        val limit = limit(initial = 50)
        val t = limit.drive(rtt = 2, count = 20) // cresce sob latência baixa
        val grown = limit.current
        limit.drive(rtt = 30, count = 40, startMs = t) // média 30ms ≫ alvo → corta
        assertTrue(limit.current * 2 < grown, "esperava encolher >2× de $grown, ficou ${limit.current}")
    }

    @Test
    fun `grows to maxLimit under low latency and clamps`() {
        val limit = limit(initial = 50)
        limit.drive(rtt = 1, count = 800)
        assertEquals(MAX, limit.current)
    }

    @Test
    fun `does not grow without load even below the target`() {
        val limit = limit(initial = 50)
        var t = 1000L
        repeat(50) {
            limit.observe(rttMillis = 2, inFlight = 10, nowMillis = t) // 10*2 < 50 → sem carga → mantém
            t += WINDOW
        }
        assertEquals(50, limit.current)
    }

    @Test
    fun `does not decide before the window closes`() {
        val limit = limit(initial = 50)
        limit.observe(rttMillis = 100, inFlight = 50, nowMillis = 1000) // abre a janela (fim em 1100)
        limit.observe(rttMillis = 100, inFlight = 50, nowMillis = 1050) // ainda dentro da janela
        assertEquals(50, limit.current) // nada decidido no meio da janela
    }

    @Test
    fun `rejects invalid parameters`() {
        assertFailsWith<IllegalArgumentException> { GradientConcurrencyLimit(50, 0, 100, 100, 5) } // minLimit < 1
        assertFailsWith<IllegalArgumentException> { GradientConcurrencyLimit(50, 10, 5, 100, 5) } // max < min
        assertFailsWith<IllegalArgumentException> { GradientConcurrencyLimit(200, 4, 100, 100, 5) } // initial fora
        assertFailsWith<IllegalArgumentException> { GradientConcurrencyLimit(50, 4, 100, 0, 5) } // window 0
        assertFailsWith<IllegalArgumentException> { GradientConcurrencyLimit(50, 4, 100, 100, 0) } // target 0
    }

    private companion object {
        const val MIN = 4
        const val MAX = 1000
        const val WINDOW = 100L
        const val TARGET = 5L
    }
}
