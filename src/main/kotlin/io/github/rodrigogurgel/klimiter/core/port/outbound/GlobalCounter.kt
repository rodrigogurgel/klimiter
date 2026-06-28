package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import kotlin.time.Duration

/**
 * Porta de saída para o **contador global por janela** (§4) — a verdade global, compartilhada por
 * todos os nós, que só cresce dentro da janela (§9). O núcleo conhece apenas esta interface (regra
 * de dependência §2): nada de Redis/Lettuce/EVALSHA vaza para cima.
 *
 * As operações **devem** executar como um read-modify-write **atômico server-side** (§11) — em Redis,
 * scripts Lua. Implementá-las como comandos separados quebra a corretude (§6.4, §11).
 *
 * `capacity`/`requested`/`missing` são contagens do contador (em [Long]); `ttl`/`elapsed`/`duration`
 * são [Duration] — o adapter as converte para os ms que as operações usam (§6.2).
 */
interface GlobalCounter {
    /** Lease atômico (§4.1): concede `min(requested, restante)` e incrementa o contador. */
    suspend fun lease(key: String, capacity: Long, requested: Long, ttl: Duration): LeaseResult

    /** Pacing fundido (§6.4): valida a linha E arrenda `missing` num passo atômico (fecha o TOCTOU). */
    suspend fun paceLease(
        key: String,
        capacity: Long,
        missing: Long,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): PaceResult
}
