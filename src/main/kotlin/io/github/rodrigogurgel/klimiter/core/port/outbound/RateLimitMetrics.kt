package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey

/**
 * Porta de saída de telemetria do caminho quente (DESIGN-CONCEITUAL-V2.md §13). O núcleo registra
 * **eventos de domínio**, não meters concretos: o adapter os mapeia para Micrometer (regra de
 * dependência §2 da ARQUITETURA.md), mantendo o `core` livre de framework. Métodos têm corpo
 * default vazio → [NOOP] é inerte.
 */
interface RateLimitMetrics {
    /**
     * Resultado de uma reserva individual e a **origem** da decisão (§13): [DecisionOrigin.LOCAL]
     * mede o que a negação local (§5.2) poupou do central; [DecisionOrigin.CENTRAL] são os
     * round-trips reais do incremento condicional (§4).
     */
    fun reserve(priority: Priority, status: Status, origin: DecisionOrigin) {
        /* corpo default vazio: a NOOP é inerte */
    }

    /** Lote natimorto na inspeção (§7.1): nenhuma reserva/round-trip aconteceu. */
    fun batchShortCircuited() { /* corpo default vazio: a NOOP é inerte */ }

    /**
     * Lote abortado na reserva sequencial (§7.2) com o negador na posição [denierPosition]
     * (0-based) da ordem por pressão. Posição frequentemente > 0 significa estatística de pressão
     * ruim — e é exatamente o prefixo antes do negador que fica queimado (§7.4).
     */
    fun batchAborted(denierPosition: Int) { /* corpo default vazio: a NOOP é inerte */ }

    /**
     * Hits **admitidos no central** (§13, métrica `reserved`) — inclui o prefixo queimado de lotes
     * depois abortados. `reserved − served` é o indicador da queima de prefixo (§7.4).
     */
    fun reservedHits(hits: Long) { /* corpo default vazio: a NOOP é inerte */ }

    /** Hits de lotes com veredito PERMITIDO (§13, métrica `served`) — a métrica de negócio. */
    fun servedHits(hits: Long) { /* corpo default vazio: a NOOP é inerte */ }

    /**
     * Reserva individual de uma policy com métrica detalhada ligada (OBSERVABILIDADE.md). [override]
     * `null` ⟺ a default da dimensão; preenchido ⟺ um override exato. Diferente das demais métricas
     * do hot path, identifica a policy (dimensão e, nos overrides, o valor) — exceção deliberada à
     * regra de cardinalidade (§1.3), limitada à config e opt-in nos overrides.
     */
    fun policyReserve(dimension: Dimension, override: DimensionValue?, priority: Priority, status: Status) {
        /* corpo default vazio: a NOOP é inerte */
    }

    /**
     * Reconcilia os meters por policy com o snapshot recém-carregado (boot/hot reload): pré-cria os
     * [active] que faltam e remove os que saíram da config. Eager → policies visíveis com tráfego zero.
     */
    fun syncDetailedPolicies(active: Set<PolicyMeterKey>) { /* corpo default vazio: a NOOP é inerte */ }

    companion object {
        /** Telemetria desligada (ou testes): nenhuma medição. */
        val NOOP: RateLimitMetrics = object : RateLimitMetrics {}
    }
}
