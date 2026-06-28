package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Status de uma decisão (§2.1), compartilhado por alta e baixa prioridade. Enum do domínio,
 * independente do enum gerado pelo proto (R2 da ARQUITETURA.md): a tradução vive na borda gRPC.
 */
enum class Status {
    /** Admitido (§5/§6). */
    ALLOWED,

    /** Recusado: janela esgotada ou `N > capacidade` (alta), ou acima da linha de pacing (baixa, §6). */
    DENIED,

    /** Degradação por falha de backend ao reservar (§7.4) — não confundir com cancelamento. */
    UNKNOWN,
}
