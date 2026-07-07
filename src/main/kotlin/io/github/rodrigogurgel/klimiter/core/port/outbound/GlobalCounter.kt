package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
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
 * O **limiar chega pronto do núcleo** (§6.3): capacidade para ALTA, linha de liberação para BAIXA,
 * ambos derivados em aritmética exata num único lugar ([io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine]).
 * A operação central não tem matemática de domínio — o limiar que nega localmente e o que o central
 * testa são **o mesmo número**, por construção.
 */
fun interface GlobalCounter {
    /**
     * Incremento condicional atômico (§4): admite sse `contador + hits ≤ threshold` e só então
     * incrementa — nunca escreve acima do limiar e nega **sem escrever**. O contador retornado vem
     * preenchido em admissões E em negações, para alimentar o snapshot monotônico local (§5.1).
     * [ttl] é aplicado apenas quando a operação cria a chave (o adapter converte para ms, §6.1).
     */
    suspend fun tryAcquire(key: String, threshold: Long, hits: Long, ttl: Duration): AcquireResult
}
