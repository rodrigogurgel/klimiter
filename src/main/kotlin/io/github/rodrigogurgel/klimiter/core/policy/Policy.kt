package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Uma regra de limite no modelo Envoy RLS (§3.1): [capacity] por [unit]. Corresponde a um `default`
 * ou a um `override` do arquivo de políticas. (Não existe campo `prefetch`: no V2 não há lease
 * local a amortizar — §8.1 do DESIGN-CONCEITUAL-V2.md.)
 *
 * [detailedMetric] liga o contador por policy `klimiter.policy.reserve` desta regra (OBSERVABILIDADE.md):
 * default `true` (a borda aplica o default assimétrico — `true` na default da dimensão, `false` nos
 * overrides).
 */
data class Policy(val capacity: Capacity, val unit: RateLimitUnit, val detailedMetric: Boolean = true)
