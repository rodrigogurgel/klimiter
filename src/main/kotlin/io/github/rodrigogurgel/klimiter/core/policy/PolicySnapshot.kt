package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue

/**
 * Visão imutável e completa do índice de políticas num instante. A recarga constrói um snapshot
 * novo e o substitui de uma vez (troca atômica, §8.1) — leituras nunca veem estado meio-atualizado.
 */
class PolicySnapshot(private val dimensions: Map<Dimension, DimensionPolicy>) {
    /** Número de dimensões configuradas neste snapshot. */
    val size: Int get() = dimensions.size

    /**
     * Resolve a política de `(dimension, value)` por precedência (§8.1): dimensão ausente →
     * [PolicyResolution.PassThrough]; senão → [PolicyResolution.Matched] com override ou default.
     * O [PolicyResolution.Matched.override] carrega o valor casado (ou `null` na default) para
     * identificar o meter por policy (OBSERVABILIDADE.md).
     */
    fun resolve(dimension: Dimension, value: DimensionValue): PolicyResolution {
        val dimensionPolicy = dimensions[dimension] ?: return PolicyResolution.PassThrough
        val override = dimensionPolicy.overrides[value]
        return PolicyResolution.Matched(override ?: dimensionPolicy.default, if (override != null) value else null)
    }

    /**
     * Keys dos meters por policy a manter ativos (OBSERVABILIDADE.md): a default de cada dimensão e
     * cada override com [Policy.detailedMetric] ligado. Base da reconciliação eager no reload.
     */
    fun detailedMeterKeys(): Set<PolicyMeterKey> = buildSet {
        dimensions.forEach { (dimension, dimensionPolicy) ->
            if (dimensionPolicy.default.detailedMetric) add(PolicyMeterKey(dimension, null))
            dimensionPolicy.overrides.forEach { (value, policy) ->
                if (policy.detailedMetric) add(PolicyMeterKey(dimension, value))
            }
        }
    }

    companion object {
        /** Snapshot vazio: tudo pass-through. Usado antes do primeiro carregamento (§8). */
        val EMPTY = PolicySnapshot(emptyMap())
    }
}
