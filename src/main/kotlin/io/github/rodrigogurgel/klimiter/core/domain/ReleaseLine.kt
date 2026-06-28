package io.github.rodrigogurgel.klimiter.core.domain

import java.math.BigInteger
import kotlin.time.Duration

/**
 * Linha de liberação do pacing (§6.2), invariante crítica (§9).
 *
 * IDÊNTICA à fórmula das operações centrais (`pace_*`):
 * ```
 * linha = min( capacidade , piso( capacidade * decorrido / duração ) + 1 )
 * ```
 * e `= capacidade` quando duração ≤ 0 ou decorrido ≥ duração. O `+1` admite a primeira unidade já
 * no início da janela.
 *
 * Calculada em **[Long] puro** (não [Double]): o piso inteiro é sempre ≥ o piso flutuante, então o
 * nó NUNCA é mais permissivo que o central (§6.3, §9). O caminho normal é alocação-zero; só recorre
 * a [BigInteger] se `capacidade * decorrido_ms` estourar [Long] (capacidades irreais), preservando a
 * exatidão exigida pelo §6.3.
 */
object ReleaseLine {
    fun line(capacity: Long, elapsed: Duration, duration: Duration): Long {
        val durationMillis = duration.inWholeMilliseconds
        val elapsedMillis = elapsed.inWholeMilliseconds
        return when {
            durationMillis <= 0 || elapsedMillis >= durationMillis -> capacity
            capacity == 0L || elapsedMillis == 0L -> 1L.coerceAtMost(capacity)
            else -> (floorOfReleased(capacity, elapsedMillis, durationMillis) + 1).coerceAtMost(capacity)
        }
    }

    /** Piso de `capacidade * decorrido / duração`, exato mesmo quando o produto estoura [Long]. */
    private fun floorOfReleased(capacity: Long, elapsedMillis: Long, durationMillis: Long): Long {
        val product = capacity * elapsedMillis
        // Se a divisão não reconstrói o operando, houve overflow → recorre a BigInteger.
        return if (product / capacity != elapsedMillis) {
            (
                BigInteger.valueOf(capacity) * BigInteger.valueOf(elapsedMillis) /
                    BigInteger.valueOf(durationMillis)
                ).toLong()
        } else {
            product / durationMillis // operandos ≥ 0 → '/' == floorDiv
        }
    }
}
