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
 * Estrutura em dois níveis (dimensão → valor → stat) para lookup sem alocação no hot path. O EWMA
 * vive num [AtomicLong] com os bits do [Double] (CAS lock-free, sem boxing).
 */
class PressureBook {
    private class Stat {
        /** Bits do Double do EWMA; `0L` == `0.0`. */
        val ewmaBits = AtomicLong(0L)

        @Volatile
        var lastTouchMillis = 0L
    }

    private val stats = ConcurrentHashMap<Dimension, ConcurrentHashMap<DimensionValue, Stat>>()

    /** Registra o desfecho de uma reserva/negação da chave e atualiza o EWMA (lock-free). */
    fun record(dimension: Dimension, value: DimensionValue, denied: Boolean, nowMillis: Long) {
        val stat = stats
            .computeIfAbsent(dimension) { ConcurrentHashMap() }
            .computeIfAbsent(value) { Stat() }
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
        val stat = stats[dimension]?.get(value) ?: return 0.0
        return Double.fromBits(stat.ewmaBits.get())
    }

    /** Evicção de chaves frias (§5.4): remove entradas sem toque há mais de [idleMillis]. */
    fun evictIdle(nowMillis: Long, idleMillis: Long): Int {
        var removed = 0
        val byDimension = stats.entries.iterator()
        while (byDimension.hasNext()) {
            val byValue = byDimension.next().value
            val entries = byValue.entries.iterator()
            while (entries.hasNext()) {
                if (nowMillis - entries.next().value.lastTouchMillis > idleMillis) {
                    entries.remove()
                    removed++
                }
            }
            if (byValue.isEmpty()) byDimension.remove()
        }
        return removed
    }

    fun size(): Int = stats.values.sumOf { it.size }

    private companion object {
        /** Peso do desfecho mais recente no EWMA: converge em ~dezenas de eventos por chave. */
        const val ALPHA = 0.1
    }
}
