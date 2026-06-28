package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue

/**
 * Resultado da resolução de uma política para `(dimensão, valor)` (§2.2, §8.1).
 */
sealed interface PolicyResolution {
    /**
     * Há política casada; a requisição roteia por prioridade (§5/§6). [override] é o valor que casou
     * um override exato, ou `null` quando caiu na default da dimensão (§8.1) — usado para identificar
     * o meter por policy (OBSERVABILIDADE.md).
     */
    data class Matched(val policy: Policy, val override: DimensionValue? = null) : PolicyResolution

    /** Sem política para a dimensão: admitido, não cobra nada (§2.2). */
    data object PassThrough : PolicyResolution
}
