package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Uma regra de limite no modelo Envoy RLS (§3.1): [capacity] por [unit], com [prefetch] opcional.
 * Corresponde a um `default` ou a um `override` do arquivo de políticas.
 *
 * [detailedMetric] liga o contador por policy `klimiter.policy.reserve` desta regra (OBSERVABILIDADE.md):
 * default `true` (a borda aplica o default assimétrico — `true` na default da dimensão, `false` nos
 * overrides).
 */
data class Policy(
    val capacity: Capacity,
    val unit: RateLimitUnit,
    val prefetch: Prefetch = Prefetch.None,
    val detailedMetric: Boolean = true,
)
