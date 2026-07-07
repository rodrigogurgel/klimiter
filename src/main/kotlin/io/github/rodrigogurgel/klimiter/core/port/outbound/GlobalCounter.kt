package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import kotlin.time.Duration

/**
 * Porta de saída para o **contador global por janela** (DESIGN-CONCEITUAL-V2.md §4) — a verdade
 * global, compartilhada por todos os nós, que só cresce dentro da janela (§9; não há refund). O
 * núcleo conhece apenas esta interface (regra de dependência §2 da ARQUITETURA.md): nada de
 * Redis/Lettuce/EVALSHA vaza para cima.
 *
 * A operação **deve** executar como um read-modify-write **atômico server-side**, single-key (§11)
 * — em Redis, um script Lua roteável por slot (compatível com cluster). Implementá-la como comandos
 * separados quebra a corretude sob concorrência.
 *
 * `capacity`/`hits` são contagens do contador (em [Long]); `ttl`/`elapsed`/`duration` são
 * [Duration] — o adapter as converte para os ms que a operação usa (§6.1).
 */
fun interface GlobalCounter {
    /**
     * Incremento condicional atômico (§4): admite sse `contador + hits ≤ limiar` e só então
     * incrementa — nunca escreve acima do limiar e nega **sem escrever**. O limiar é a capacidade
     * ([Priority.HIGH]) ou a linha de liberação derivada de [elapsed]/[duration]
     * ([Priority.LOW], §6.1). O contador retornado vem preenchido em admissões E em negações, para
     * alimentar o snapshot monotônico local (§5.1).
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
}
