package io.github.rodrigogurgel.klimiter.core.policy

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unidade da janela no modelo Envoy RLS (§3.1). A janela tem sempre o tamanho de exatamente
 * uma unit e é alinhada ao epoch/UTC (§3.2). Não existe duração arbitrária.
 *
 * @property window comprimento da janela desta unit.
 */
enum class RateLimitUnit(val window: Duration) {
    SECOND(1.seconds),
    MINUTE(60.seconds),
    HOUR(3600.seconds),
    DAY(86_400.seconds),
}
