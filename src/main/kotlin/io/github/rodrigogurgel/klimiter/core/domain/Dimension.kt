package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Eixo do limite (ex.: `user_id`, `ip`, `tenant`). Value Object com invariante de não-vazio
 * (R5 da ARQUITETURA.md): dimensões não trafegam como [String] crua entre fronteiras internas.
 */
@JvmInline
value class Dimension(val raw: String) {
    init {
        require(raw.isNotBlank()) { "dimensão não pode ser vazia" }
    }

    override fun toString(): String = raw
}
