package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * DTOs de desserialização do `policies.yaml` (formato em docs/POLITICAS.md). Espelham o JSON
 * Schema 1:1; campos opcionais aqui são validados na conversão para o domínio
 * ([toSnapshot]) com mensagens contextualizadas. Existem só na borda (R2 da ARQUITETURA.md).
 */
internal data class PolicyDocument(val version: Int? = null, val policies: Map<String, DimensionDocument>? = null)

internal data class DimensionDocument(
    val default: RuleDocument? = null,
    val overrides: Map<String, RuleDocument> = emptyMap(),
)

internal data class RuleDocument(
    @param:JsonProperty("requests_per_unit") val requestsPerUnit: Int? = null,
    val unit: String? = null,
    val prefetch: PrefetchDocument? = null,
    @param:JsonProperty("detailed_metric") val detailedMetric: Boolean? = null,
)

internal data class PrefetchDocument(val percent: Int? = null, val count: Int? = null)
