package io.github.rodrigogurgel.klimiter.core.port.outbound

private const val MILLIS_PER_SECOND = 1000L

/**
 * Porta de saída para o tempo (§3.2). O núcleo nunca lê o relógio direto (R1 da ARQUITETURA.md):
 * em produção um adapter usa o relógio do sistema; nos testes, um relógio fixo.
 *
 * A fronteira de janela (§3.2) é alinhada em **epoch-segundos** (units são ≥ 1s); [nowEpochSecond]
 * deriva de [nowMillis] via [Math.floorDiv] para alinhar corretamente também antes do epoch.
 */
fun interface Clock {
    /** Instante atual em milissegundos do epoch (UTC). */
    fun nowMillis(): Long

    /** Instante atual em segundos do epoch (UTC), com piso para a fronteira de janela (§3.2). */
    fun nowEpochSecond(): Long = Math.floorDiv(nowMillis(), MILLIS_PER_SECOND)
}
