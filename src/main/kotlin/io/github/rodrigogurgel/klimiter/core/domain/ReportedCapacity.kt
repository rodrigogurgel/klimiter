package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Capacidade da política (`requests_per_unit`) ecoada ao cliente na decisão (§2.1): `0` em
 * pass-through (eixo sem política). Value Object com invariante de não-negatividade (R5 da
 * ARQUITETURA.md).
 *
 * Distinto de `core.policy.Capacity` (configurada, ≥ 1): este é o **eco** na resposta (≥ 0) e o
 * domínio não depende de `core.policy` (regra de dependência). Em [Long] (proto `uint64`).
 */
@JvmInline
value class ReportedCapacity(val value: Long) {
    init {
        require(value >= 0) { "capacity deve ser >= 0, recebido $value" }
    }

    companion object {
        /** Sem capacidade a reportar: pass-through (§2.1). */
        val NONE = ReportedCapacity(0)
    }
}
