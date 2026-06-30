package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Teto de concorrência ADAPTATIVO (modo `adaptive`) que se auto-calibra pela latência observada —
 * elimina a constante por-hardware do modo fixo. Controle estilo **CoDel** (alvo de latência
 * absoluto) com magnitude de corte tipo gradiente + **piso de goodput anti-espiral**, **sem
 * dependência** (a lib Netflix está dormente; o valor é uma classe). Decisões de design do harness:
 *
 * - **sinal = MÉDIA da janela** (não o mínimo: min ≪ média ≪ cauda, controlar o mínimo deixa o p99
 *   estourar);
 * - **alvo ABSOLUTO** ([targetMillis] ≈ SLO/5, pois p99 ≈ 4-5× a média) — relativo a um piso falharia
 *   com RTT alto;
 * - **AIMD**: média acima do alvo → corte multiplicativo proporcional (`alvo/média`, rápido); dentro
 *   do alvo e sob carga → crescimento aditivo `√teto` (lento);
 * - **piso de goodput (anti death-spiral)**: sob overload extremo, a CPU gasta REJEITANDO degrada o
 *   caminho admitido → a latência fica alta mesmo com teto baixo → o controlador encolhe mais → sheda
 *   mais → espiral, colapsando o **goodput** (servido útil) bem abaixo do joelho. O piso detecta isso:
 *   se um corte fez o goodput (completions/janela) **despencar** vs o pico, trava o teto no nível
 *   pré-corte (preserva goodput, aceitando latência maior — degradação graciosa). Relaxa devagar p/
 *   re-explorar;
 * - **decide só ao fechar a janela** (intervalo CoDel).
 *
 * Thread-safety: [observe] (`@Synchronized`) roda nas terminações; [current] é volátil (gate lock-free).
 */
class GradientConcurrencyLimit(
    initialLimit: Int,
    private val minLimit: Int,
    private val maxLimit: Int,
    private val windowMillis: Long,
    private val targetMillis: Long,
) : ConcurrencyLimit {
    init {
        require(minLimit >= 1) { "minLimit deve ser >= 1" }
        require(maxLimit >= minLimit) { "maxLimit deve ser >= minLimit" }
        require(initialLimit in minLimit..maxLimit) { "initialLimit fora de [minLimit, maxLimit]" }
        require(windowMillis > 0) { "windowMillis deve ser > 0" }
        require(targetMillis > 0) { "targetMillis deve ser > 0" }
    }

    @Volatile
    override var current: Int = initialLimit
        private set

    private var windowSum = 0L // soma de RTT na janela (p/ a média)
    private var windowCount = 0 // completions na janela ∝ goodput
    private var windowEnd = 0L
    private var bestGoodput = 0.0 // pico de goodput (decai devagar p/ re-aprender)
    private var goodputFloor = minLimit // piso anti-espiral
    private var prevLimit = initialLimit // teto da janela anterior (alvo do bounce)
    private var prevMean = Double.MAX_VALUE // média da janela anterior (detecta latência travada)

    @Synchronized
    override fun observe(rttMillis: Long, inFlight: Int, nowMillis: Long) {
        if (windowEnd == 0L) {
            windowEnd = nowMillis + windowMillis
            prevLimit = current
        }
        windowSum += rttMillis.coerceAtLeast(0)
        windowCount++
        if (nowMillis < windowEnd) return // decide só ao FECHAR a janela (intervalo CoDel)

        val goodput = windowCount.toDouble()
        val mean = windowSum / goodput
        bestGoodput = maxOf(goodput, bestGoodput * (1 - GOODPUT_DECAY))

        // Piso anti-espiral: o último corte derrubou o goodput vs o pico E NÃO melhorou a latência
        // → é espiral (a CPU de shed segura a latência), não shedding saudável → trava no teto
        // pré-corte. Distinguir pela latência evita engajar no overload MODERADO (onde encolher
        // melhora a latência de propósito). Goodput saudável → relaxa o piso devagar p/ re-explorar.
        val droppedGoodput = goodput < bestGoodput * GOODPUT_BAND
        val latencyStuck = mean >= prevMean * LATENCY_IMPROVE
        if (current < prevLimit && droppedGoodput && latencyStuck) {
            goodputFloor = prevLimit
        } else if (!droppedGoodput) {
            goodputFloor = maxOf(minLimit, (goodputFloor * (1 - FLOOR_DECAY)).roundToInt())
        }

        val headroom = maxOf(MIN_HEADROOM, sqrt(current.toDouble()))
        val raw = when {
            mean > targetMillis -> (current * (targetMillis / mean).coerceIn(MAX_CUT, 1.0)).roundToInt()
            inFlight * 2 >= current -> (current + headroom).roundToInt()
            else -> current
        }
        prevLimit = current
        prevMean = mean
        current = raw.coerceIn(minLimit, maxLimit).coerceAtLeast(goodputFloor)

        windowSum = 0
        windowCount = 0
        windowEnd = nowMillis + windowMillis
    }

    private companion object {
        const val MIN_HEADROOM = 4.0 // passo aditivo mínimo de crescimento
        const val MAX_CUT = 0.5 // corte máximo por janela (piso do multiplicador)
        const val GOODPUT_DECAY = 0.002 // pico de goodput (capacidade) re-aprende devagar (~0,2%/janela)
        const val GOODPUT_BAND = 0.85 // goodput < 85% do pico = "despencou"
        const val LATENCY_IMPROVE = 0.9 // corte só "ajudou" se a média caiu abaixo de 90% da anterior
        const val FLOOR_DECAY = 0.1 // piso relaxa a ~10%/janela p/ re-explorar
    }
}
