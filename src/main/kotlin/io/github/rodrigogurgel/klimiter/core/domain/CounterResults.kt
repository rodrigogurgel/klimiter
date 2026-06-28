package io.github.rodrigogurgel.klimiter.core.domain

/*
 * Resultados das operações atômicas do contador global (§4.1, §6.4). São Value Objects do domínio —
 * vivem aqui, e não no pacote `core.port`, porque o teste de arquitetura (R3) exige que as portas
 * contenham apenas interfaces. A porta `GlobalCounter` (em `core.port.outbound`) os usa.
 * `freeGlobal` é uma contagem do contador da janela, em Long.
 */

/** Concessão do lease (§4.1). */
data class LeaseResult(
    /** Quanto foi concedido deste bloco (`0..requested`). */
    val granted: Long,
    /** `capacity − já_arrendado` após o incremento (`0` = janela cheia). */
    val freeGlobal: Long,
)

/** Resultado do pacing fundido (§6.4): valida a linha e arrenda o faltante num passo atômico. */
data class PaceResult(
    val admitted: Boolean,
    /** `capacity − já_arrendado` após o passo (`0` = janela cheia). */
    val freeGlobal: Long,
)
