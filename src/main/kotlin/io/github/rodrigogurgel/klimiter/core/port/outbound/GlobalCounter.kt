package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.domain.Priority
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
    /**
     * Incremento condicional atômico (DESIGN-CONCEITUAL-V2.md §4): admite sse
     * `contador + hits ≤ limiar` e só então incrementa — nunca escreve acima do limiar e nega **sem
     * escrever**. O limiar é a capacidade ([Priority.HIGH]) ou a linha de liberação derivada de
     * [elapsed]/[duration] ([Priority.LOW], §6.1). O contador retornado vem preenchido em admissões
     * E em negações, para alimentar o snapshot monotônico local (§5.1).
     */
    suspend fun tryAcquire(
        key: String,
        capacity: Long,
        hits: Long,
        priority: Priority,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): AcquireResult

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
