package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Teto de concorrência ADAPTATIVO (modo `adaptive`) que se auto-calibra pela latência observada —
 * elimina a constante por-hardware do modo fixo. Controle estilo **CoDel** (alvo de latência
 * absoluto) com magnitude de corte tipo gradiente, **sem dependência** (a lib Netflix está dormente;
 * o valor é uma classe). Decisões de design vindas do harness:
 *
 * - **sinal = MÉDIA da janela**, não o mínimo: o request mais rápido quase nunca enfileira (min ≪
 *   média ≪ cauda), então controlar o mínimo deixa a cauda (p99) estourar com o teto alto;
 * - **alvo ABSOLUTO** ([targetMillis]) derivado do SLO, não relativo a um piso: para um SLO absoluto
 *   (p99 < 15 ms), um alvo relativo ao piso falharia com RTT alto (Redis remoto). Como p99 ≈ 4-5× a
 *   média (medido), o alvo da média ≈ SLO/5;
 * - **AIMD**: média acima do alvo → **corte multiplicativo** proporcional (`alvo/média`, rápido);
 *   dentro do alvo e **sob carga** → **crescimento aditivo** `√teto` (lento). Decréscimo rápido
 *   protege o SLO;
 * - **decide só ao fechar a janela** (intervalo CoDel): age em pressão sustentada, não em blip.
 *
 * Thread-safety: [observe] (`@Synchronized`) roda nas terminações de chamada; [current] é volátil
 * para leitura lock-free no gate.
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
    private var windowCount = 0
    private var windowEnd = 0L

    @Synchronized
    override fun observe(rttMillis: Long, inFlight: Int, nowMillis: Long) {
        if (windowEnd == 0L) windowEnd = nowMillis + windowMillis
        windowSum += rttMillis.coerceAtLeast(0)
        windowCount++
        if (nowMillis < windowEnd) return // decide só ao FECHAR a janela (intervalo CoDel)

        val mean = windowSum.toDouble() / windowCount
        val headroom = maxOf(MIN_HEADROOM, sqrt(current.toDouble()))
        current = when {
            // acima do alvo → corte proporcional (quanto mais longe, mais corta), limitado a ×0,5/janela
            mean > targetMillis -> (current * (targetMillis / mean).coerceIn(MAX_CUT, 1.0)).roundToInt()

            // dentro do alvo e SOB carga → cresce aditivo (sem evidência de carga, mantém)
            inFlight * 2 >= current -> (current + headroom).roundToInt()

            else -> current
        }.coerceIn(minLimit, maxLimit)

        windowSum = 0
        windowCount = 0
        windowEnd = nowMillis + windowMillis
    }

    private companion object {
        const val MIN_HEADROOM = 4.0 // passo aditivo mínimo de crescimento
        const val MAX_CUT = 0.5 // corte máximo por janela (piso do multiplicador)
    }
}
