package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue

/**
 * Política de uma dimensão: a regra [default] e os [overrides] por valor exato. A resolução por
 * precedência (override > default) é o §8.1.
 */
data class DimensionPolicy(val default: Policy, val overrides: Map<DimensionValue, Policy> = emptyMap()) {
    /** Regra aplicável a [value]: o override exato quando existe, senão o [default] (§8.1). */
    fun resolve(value: DimensionValue): Policy = overrides[value] ?: default
}
