package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status

/**
 * Porta de saída de telemetria do caminho quente (§5/§6/§7). O núcleo registra **eventos de
 * domínio**, não meters concretos: o adapter os mapeia para Micrometer (regra de dependência §2),
 * mantendo o `core` livre de framework. Métodos têm corpo default vazio → [NOOP] é inerte.
 */
interface RateLimitMetrics {
    /**
     * Resultado de uma reserva individual (§5/§6). Combinado com `klimiter.central.roundtrip`,
     * permite derivar a taxa de acerto local da ALTA e o shed do pré-portão da BAIXA.
     */
    fun reserve(priority: Priority, status: Status) { /* corpo default vazio: a NOOP é inerte */ }

    /** Nível do caminho de reserva ALTA tomado (§5): distribuição L1–L4 para tunar o prefetch. */
    fun reserveHighPath(path: ReservePath) { /* corpo default vazio: a NOOP é inerte */ }

    /** Lote natimorto pelo short-circuit (§7.2): nenhuma reserva/round-trip aconteceu. */
    fun batchShortCircuited() { /* corpo default vazio: a NOOP é inerte */ }

    /** Lote refundado (§7.4): veredito coletivo não-PERMITIDO desfez as reservas admitidas. */
    fun batchRefunded() { /* corpo default vazio: a NOOP é inerte */ }

    companion object {
        /** Telemetria desligada (ou testes): nenhuma medição. */
        val NOOP: RateLimitMetrics = object : RateLimitMetrics {}
    }
}
