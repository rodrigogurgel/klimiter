package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Valor concreto dentro de uma [Dimension] (ex.: `user-42` para a dimensão `user_id`).
 * Value Object com invariante de não-vazio (R5 da ARQUITETURA.md).
 */
@JvmInline
value class DimensionValue(val raw: String) {
    init {
        require(raw.isNotBlank()) { "valor da dimensão não pode ser vazio" }
    }

    override fun toString(): String = raw
}
