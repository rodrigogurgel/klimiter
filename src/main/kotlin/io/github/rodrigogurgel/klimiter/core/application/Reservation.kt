package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.Status

/**
 * Resultado de uma reserva individual, com a possibilidade de **refund** para o all-or-nothing
 * (§7.4). Limite do refund (§9 — o contador central SÓ CRESCE):
 *  - ALTA: refund exato (devolve o crédito local).
 *  - BAIXA: o `INCRBY` do faltante NÃO é desfeito (conservador, §9); devolve só o crédito local
 *    drenado (§6.1, §7.3).
 */
sealed interface Reservation {
    val decision: Decision
    val admitted: Boolean get() = decision.status == Status.ALLOWED

    /** Desfaz a cobrança (best-effort) quando o lote é negado (§7.4). */
    fun release()

    /** Reserva ALTA admitida: refund devolve o crédito local consumido. */
    class High(private val bucket: Bucket, private val hits: Long, override val decision: Decision) : Reservation {
        override fun release() = bucket.refundLocal(hits)
    }

    /** Reserva BAIXA admitida: refund devolve apenas o crédito local drenado (§6.1). */
    class Low(private val bucket: Bucket, private val localUsed: Long, override val decision: Decision) : Reservation {
        override fun release() = bucket.refundLocal(localUsed)
    }

    /** Negação ou pass-through: nada a liberar. */
    class Final(override val decision: Decision) : Reservation {
        override fun release() = Unit
    }
}
