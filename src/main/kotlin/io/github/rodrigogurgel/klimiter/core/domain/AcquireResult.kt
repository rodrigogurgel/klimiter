package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Resultado do incremento condicional atômico (DESIGN-CONCEITUAL-V2.md §4). Value Object do domínio —
 * vive aqui, e não em `core.port`, porque o teste de arquitetura (R3) exige que as portas contenham
 * apenas interfaces.
 *
 * O [counter] vem preenchido **sempre** — em admissões e em negações — porque é ele que alimenta o
 * snapshot monotônico local (§5.1): toda resposta do central ensina o nó, até as que negam.
 */
data class AcquireResult(
    /** A operação admitiu (e incrementou) ou negou (sem escrever nada)? */
    val admitted: Boolean,
    /** O contador da janela após a operação (inalterado quando negado). */
    val counter: Long,
)
