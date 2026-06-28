package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Resultado da resolução de uma política para `(dimensão, valor)` (§2.2, §8.1).
 */
sealed interface PolicyResolution {
    /** Há política casada; a requisição roteia por prioridade (§5/§6). */
    data class Matched(val policy: Policy) : PolicyResolution

    /** Sem política para a dimensão: admitido, não cobra nada (§2.2). */
    data object PassThrough : PolicyResolution
}
