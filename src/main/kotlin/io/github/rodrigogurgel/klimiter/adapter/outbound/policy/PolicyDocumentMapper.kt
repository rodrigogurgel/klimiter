package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.DimensionPolicy
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import io.github.rodrigogurgel.klimiter.core.policy.Prefetch
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit

/** Versão de formato suportada (campo `version` do arquivo). */
private const val SUPPORTED_VERSION = 1

/**
 * Converte o [PolicyDocument] desserializado num [PolicySnapshot] do domínio, aplicando as
 * mesmas regras do JSON Schema. Lança [IllegalArgumentException] (via [require]/Value Objects)
 * com mensagem contextualizada quando o conteúdo é inválido; o chamador a traduz em
 * [PolicyFileException].
 */
internal fun PolicyDocument.toSnapshot(): PolicySnapshot {
    require(version == SUPPORTED_VERSION) { "version deve ser $SUPPORTED_VERSION, recebido $version" }
    val policies = policies
    require(!policies.isNullOrEmpty()) { "'policies' é obrigatório e deve ter ao menos uma dimensão" }

    val dimensions = policies.entries.associate { (name, document) ->
        val dimension = Dimension(name)
        dimension to inDimension(name) { document.toDimensionPolicy() }
    }
    return PolicySnapshot(dimensions)
}

private fun DimensionDocument.toDimensionPolicy(): DimensionPolicy {
    val default = requireNotNull(default) { "default é obrigatório" }
    val overridePolicies = overrides.entries.associate { (value, rule) ->
        // Override: detailed_metric default false (opt-in, evita PII/cardinalidade por valor, §1.3).
        DimensionValue(value) to inOverride(value) { rule.toPolicy(defaultDetailed = false) }
    }
    // Default da dimensão: detailed_metric default true.
    return DimensionPolicy(default.toPolicy(defaultDetailed = true), overridePolicies)
}

private fun RuleDocument.toPolicy(defaultDetailed: Boolean): Policy {
    val requestsPerUnit = requireNotNull(requestsPerUnit) { "requests_per_unit é obrigatório" }
    val unit = requireNotNull(unit) { "unit é obrigatório" }
    val rateLimitUnit = runCatching { RateLimitUnit.valueOf(unit) }.getOrElse {
        throw IllegalArgumentException("unit inválida '$unit' (use ${RateLimitUnit.entries.joinToString()})")
    }
    return Policy(Capacity(requestsPerUnit), rateLimitUnit, prefetch.toPrefetch(), detailedMetric ?: defaultDetailed)
}

private fun PrefetchDocument?.toPrefetch(): Prefetch {
    if (this == null) return Prefetch.None
    return when {
        percent != null && count != null -> throw IllegalArgumentException(
            "prefetch aceita apenas um entre 'percent' e 'count'",
        )

        percent != null -> Prefetch.Percent(percent)

        count != null -> Prefetch.Count(count)

        else -> throw IllegalArgumentException("prefetch precisa de 'percent' ou 'count'")
    }
}

private inline fun <T> inDimension(name: String, block: () -> T): T =
    runCatching(block).getOrElse { throw IllegalArgumentException("dimensão '$name': ${it.message}", it) }

private inline fun <T> inOverride(value: String, block: () -> T): T =
    runCatching(block).getOrElse { throw IllegalArgumentException("override '$value': ${it.message}", it) }
