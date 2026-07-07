package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Estatística de **pressão** por `(dimensão, valor)` (§5.3): EWMA da fração recente de reservas
 * negadas, atravessando janelas. Alimenta exclusivamente a **ordenação** da reserva do lote (§7.3)
 * — o negador mais provável vai primeiro. É heurística: erro na pressão degrada utilização
 * marginalmente, nunca corretude.
 *
 * Um único [ConcurrentHashMap] chaveado por `(dimensão, valor)`: sem mapa interno, não existe a
 * corrida "evicção remove o mapa vazio que um `record` concorrente acabou de obter" (a alocação da
 * chave é ruído perto do round-trip que acompanha cada registro). O EWMA vive num [AtomicLong] com
 * os bits do [Double] (CAS lock-free, sem boxing).
 */
class PressureBook {
    /** Identidade da estatística: eixo + valor. */
    private data class Key(val dimension: Dimension, val value: DimensionValue)

    /** Nasce com o toque corrente: a varredura concorrente nunca vê um Stat novo "ocioso desde 0". */
    private class Stat(@Volatile var lastTouchMillis: Long) {
        /** Bits do Double do EWMA; `0L` == `0.0`. */
        val ewmaBits = AtomicLong(0L)
    }

    private val stats = ConcurrentHashMap<Key, Stat>()

    /** Registra o desfecho de uma reserva/negação da chave e atualiza o EWMA (lock-free). */
    fun record(dimension: Dimension, value: DimensionValue, denied: Boolean, nowMillis: Long) {
        val stat = stats.computeIfAbsent(Key(dimension, value)) { Stat(nowMillis) }
        stat.lastTouchMillis = nowMillis
        val outcome = if (denied) 1.0 else 0.0
        while (true) {
            val bits = stat.ewmaBits.get()
            val next = ALPHA * outcome + (1 - ALPHA) * Double.fromBits(bits)
            if (stat.ewmaBits.compareAndSet(bits, next.toRawBits())) return
        }
    }

    /** Pressão observada da chave (`0.0` quando nunca vista) — a chave da ordenação (§7.3). */
    fun pressureOf(dimension: Dimension, value: DimensionValue): Double {
        val stat = stats[Key(dimension, value)] ?: return 0.0
        return Double.fromBits(stat.ewmaBits.get())
    }

    /**
     * Evicção de chaves frias (§5.4): remove entradas sem toque há mais de [idleMillis]. Um `record`
     * concorrente pode ressuscitar uma entrada já escolhida para remoção e perder aquele desfecho —
     * inofensivo para uma heurística (a chave renasce zerada no toque seguinte).
     */
    fun evictIdle(nowMillis: Long, idleMillis: Long): Int {
        var removed = 0
        val iterator = stats.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().value.lastTouchMillis > idleMillis) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    fun size(): Int = stats.size

    private companion object {
        /** Peso do desfecho mais recente no EWMA: converge em ~dezenas de eventos por chave. */
        const val ALPHA = 0.1
    }
}
