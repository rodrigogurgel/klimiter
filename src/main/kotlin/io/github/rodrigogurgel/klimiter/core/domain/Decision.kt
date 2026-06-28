package io.github.rodrigogurgel.klimiter.core.domain

import kotlin.time.Duration

/**
 * Decisão por requisição (§2.1). Contrato interno do domínio, independente do proto (R2 da
 * ARQUITETURA.md): a tradução para `RateLimitDecision` vive na borda gRPC.
 */
data class Decision(
    val status: Status,
    val remaining: Remaining,
    val resetAfter: Duration,
    val capacity: ReportedCapacity,
) {
    companion object {
        /** Sem política para o eixo → pass-through (§2.2, §8): admitido, não cobra, sem janela. */
        val PASS_THROUGH = Decision(Status.ALLOWED, Remaining.NONE, Duration.ZERO, ReportedCapacity.NONE)
    }
}
