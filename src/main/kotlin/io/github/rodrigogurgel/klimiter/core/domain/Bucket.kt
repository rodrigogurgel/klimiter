package io.github.rodrigogurgel.klimiter.core.domain

import java.util.concurrent.atomic.AtomicLong

/** Identidade do bucket: eixo + valor + início da janela (§5.1). */
data class BucketKey(val dimension: Dimension, val value: DimensionValue, val windowStartEpochSecond: Long)

/**
 * A visão local de um bucket `(dimensão, valor, janela)` (DESIGN-CONCEITUAL-V2.md §5.1): **um único
 * número** — o maior contador já observado do central ([snapshot], monotônico). Como o contador
 * global só cresce dentro da janela (não há refund, §9), o snapshot é um **limite inferior
 * garantido** do consumo real: suficiente para *negar* localmente (§5.2), nunca para admitir.
 *
 * Um bucket pertence a UMA janela; quando a janela vira, a evicção (§5.4) remove o velho e o índice
 * cria um novo, resetando o estado. O snapshot inicial `0` é um limite inferior trivialmente
 * verdadeiro — dispensa flag de "já observou".
 */
class Bucket(val key: BucketKey, val storageKey: String, val capacity: Long, val window: Window) {
    private val snapshotCounter = AtomicLong(0)

    /** Fim da janela em ms — usado pela evicção (§5.4). */
    val expiryMillis: Long = window.endEpochSecond * MILLIS_PER_SECOND

    /** Maior contador observado do central (§5.1): limite inferior garantido do consumo real. */
    val snapshot: Long get() = snapshotCounter.get()

    /**
     * Esgotado terminal (§5.2): com o incremento condicional (§4) o contador nunca passa da
     * capacidade nem desce — uma vez cheio, cheio até a janela virar. Derivado do snapshot, não é
     * um estado separado.
     */
    val exhausted: Boolean get() = snapshotCounter.get() >= capacity

    /**
     * Aprende o contador de **qualquer** resposta do central — admissões e negações (§5.1).
     * Monotônico: uma resposta fora de ordem reportando valor menor é descartada, mantendo o
     * snapshot um lower-bound apertado.
     */
    fun observe(counter: Long) {
        snapshotCounter.updateAndGet { current -> maxOf(current, counter) }
    }

    /**
     * Limiar da reserva (§4): capacidade para ALTA; linha de liberação (§6.1) para BAIXA. Derivado
     * em aritmética exata ([ReleaseLine]) num único lugar — o valor que a negação local testa (§5.2)
     * é o mesmo que vai pronto ao central, então as duas pontas não podem divergir (§6.3).
     */
    fun threshold(priority: Priority, nowMillis: Long): Long = when (priority) {
        Priority.HIGH -> capacity
        Priority.LOW -> ReleaseLine.line(capacity, window.elapsed(nowMillis), window.duration)
    }

    /** Decisão PERMITIDO com a capacidade restante estimada pelo snapshot (§2.1). */
    fun allowed(nowMillis: Long): Decision = Decision(
        Status.ALLOWED,
        Remaining((capacity - snapshot).coerceAtLeast(0)),
        window.ttl(nowMillis),
        ReportedCapacity(capacity),
    )

    /** Decisão NEGADO (§2.1). */
    fun denied(nowMillis: Long): Decision =
        Decision(Status.DENIED, Remaining.NONE, window.ttl(nowMillis), ReportedCapacity(capacity))

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
