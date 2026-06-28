package io.github.rodrigogurgel.klimiter.core.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Janela fixa alinhada à fronteira da unit (§3.1): tem sempre o tamanho de exatamente uma unit. O
 * início (em segundos do epoch) é embutido na chave determinística (§3.2), então cada janela é uma
 * chave distinta — não há "reset" de contador: a janela antiga simplesmente expira.
 *
 * O alinhamento usa [Math.floorDiv] para que fronteiras antes do epoch (relógios teóricos) caiam
 * corretamente. Trabalha em epoch-segundos para o alinhamento (units são ≥ 1s) e expõe [Duration]
 * para o decorrido/TTL — a borda de armazenamento converte para os ms das operações centrais (§6).
 *
 * A *derivação* e a composição da chave determinística são responsabilidade de um único ponto em
 * `core.policy` (ARQUITETURA.md §6); este tipo é apenas o Value Object resultante.
 */
data class Window(val startEpochSecond: Long, val duration: Duration) {
    init {
        require(duration.isPositive()) { "duração da janela deve ser > 0, recebido $duration" }
    }

    /** Fim da janela em segundos do epoch (exclusivo). */
    val endEpochSecond: Long get() = startEpochSecond + duration.inWholeSeconds

    private val startMillis: Long get() = startEpochSecond * MILLIS_PER_SECOND
    private val endMillis: Long get() = endEpochSecond * MILLIS_PER_SECOND

    /** Decorrido na janela, fixado a `[0, duração]` (§6.2). */
    fun elapsed(nowMillis: Long): Duration =
        (nowMillis - startMillis).coerceIn(0, duration.inWholeMilliseconds).milliseconds

    /** TTL restante da janela, nunca negativo (§4.1) — também o `reset_after` da decisão (§2.1). */
    fun ttl(nowMillis: Long): Duration = (endMillis - nowMillis).coerceAtLeast(0).milliseconds

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        /** Deriva a janela vigente para [nowEpochSecond] dada a [duration] da unit (§3.2). */
        fun derive(nowEpochSecond: Long, duration: Duration): Window {
            val windowSeconds = duration.inWholeSeconds
            require(windowSeconds > 0) { "duração da janela deve ser ≥ 1s, recebido $duration" }
            val start = Math.floorDiv(nowEpochSecond, windowSeconds) * windowSeconds
            return Window(start, duration)
        }
    }
}
