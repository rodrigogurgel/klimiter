package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue

/**
 * Identidade estável de um contador por policy (`klimiter.policy.reserve`, OBSERVABILIDADE.md).
 * [override] `null` ⟺ a regra default da dimensão; preenchido ⟺ um override exato por valor (§8.1).
 *
 * Usado na reconciliação eager dos meters no reload: o snapshot enumera as keys ativas
 * ([PolicySnapshot.detailedMeterKeys]) e o adapter cria/remove os meters correspondentes.
 */
data class PolicyMeterKey(val dimension: Dimension, val override: DimensionValue?)
