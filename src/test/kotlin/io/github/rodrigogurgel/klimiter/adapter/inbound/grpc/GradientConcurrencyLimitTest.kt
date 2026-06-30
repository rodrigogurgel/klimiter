package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GradientConcurrencyLimitTest {
    private fun limit(initial: Int = 50) = GradientConcurrencyLimit(initial, MIN, MAX, WINDOW, TARGET)

    /** Roda `windows` janelas de `rtt` com [SAMPLES] completions estáveis por janela (goodput estável,
     *  como no harness real — evita o ruído que dispara o piso de goodput em janelas minúsculas). */
    private fun GradientConcurrencyLimit.drive(rtt: Long, windows: Int, startMs: Long = 1000L): Long {
        var t = startMs
        repeat(windows) {
            repeat(SAMPLES) { observe(rtt, current, t) } // amostras dentro da janela (sob carga)
            observe(rtt, current, t + WINDOW) // fecha a janela
            t += WINDOW
        }
        return t
    }

    @Test
    fun `grows while the mean stays below the target`() {
        val limit = limit(initial = 50)
        limit.drive(rtt = 2, windows = 20) // média 2ms < alvo 5ms
        assertTrue(limit.current > 50, "esperava crescer, ficou ${limit.current}")
    }

    @Test
    fun `shrinks substantially when the mean exceeds the target`() {
        val limit = limit(initial = 50)
        val t = limit.drive(rtt = 2, windows = 10) // cresce sob latência baixa
        val grown = limit.current
        limit.drive(rtt = 30, windows = 20, startMs = t) // média 30ms ≫ alvo → corta
        assertTrue(limit.current * 2 < grown, "esperava encolher >2× de $grown, ficou ${limit.current}")
    }

    @Test
    fun `grows to maxLimit under low latency and clamps`() {
        val limit = limit(initial = 50)
        limit.drive(rtt = 1, windows = 400)
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
    fun `goodput floor holds the limit near the knee under sustained queueing`() {
        val knee = 40
        val limit = limit(initial = 100)
        var t = 1000L
        repeat(80) {
            // goodput ∝ min(teto, joelho): platô acima do joelho, cai abaixo (simula a curva real)
            val samples = minOf(limit.current, knee).coerceAtLeast(1)
            repeat(samples) { limit.observe(rttMillis = 30, inFlight = limit.current, nowMillis = t) }
            limit.observe(rttMillis = 30, inFlight = limit.current, nowMillis = t + WINDOW) // fecha a janela
            t += WINDOW
        }
        // latência sempre alta (shed-CPU): SEM o piso colapsaria pro mínimo; COM o piso segura ~o joelho
        assertTrue(limit.current >= knee / 2, "piso deveria segurar perto do joelho ($knee), ficou ${limit.current}")
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
        const val SAMPLES = 30 // completions/janela estáveis (goodput sem ruído)
    }
}
