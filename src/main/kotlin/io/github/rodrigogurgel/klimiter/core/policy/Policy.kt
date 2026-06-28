package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Uma regra de limite no modelo Envoy RLS (§3.1): [capacity] por [unit], com [prefetch] opcional.
 * Corresponde a um `default` ou a um `override` do arquivo de políticas.
 */
data class Policy(val capacity: Capacity, val unit: RateLimitUnit, val prefetch: Prefetch = Prefetch.None)
