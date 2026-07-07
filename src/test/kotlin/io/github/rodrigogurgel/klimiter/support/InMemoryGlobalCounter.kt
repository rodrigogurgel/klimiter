package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import kotlin.time.Duration

/**
 * Contador global em memória que espelha a semântica do `conditional_increment.lua` (V2 §4), para
 * testar o core sem Redis. Não thread-safe: uso em testes single-threaded.
 */
class InMemoryGlobalCounter : GlobalCounter {
    private val counters = HashMap<String, Long>()

    /** O contador corrente da chave (0 quando nunca escrita) — para asserções de queima/consumo. */
    fun counterOf(key: String): Long = counters[key] ?: 0

    override suspend fun tryAcquire(key: String, threshold: Long, hits: Long, ttl: Duration): AcquireResult {
        val counter = counters[key] ?: 0
        return if (hits > 0 && counter + hits <= threshold) {
            val total = counter + hits
            counters[key] = total
            AcquireResult(admitted = true, counter = total)
        } else {
            AcquireResult(admitted = false, counter = counter)
        }
    }
}
