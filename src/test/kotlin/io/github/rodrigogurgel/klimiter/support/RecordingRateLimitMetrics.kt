package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.BatchPriority
import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics

/** [RateLimitMetrics] que apenas grava as chamadas, para asserções nos testes do core. */
class RecordingRateLimitMetrics : RateLimitMetrics {
    /** Reserva individual: prioridade, status e a origem da decisão (LOCAL/CENTRAL, §13). */
    data class Reserve(val priority: Priority, val status: Status, val origin: DecisionOrigin)

    /** Resposta de uma requisição (§2.1): prioridade agregada do lote e status coletivo. */
    data class Decided(val priority: BatchPriority, val status: Status)

    /** Reserva detalhada por policy: (dimensão, override ou null, prioridade, status). */
    data class PolicyReserve(
        val dimension: Dimension,
        val override: DimensionValue?,
        val priority: Priority,
        val status: Status,
    )

    val reserves = mutableListOf<Reserve>()
    val decidedRequests = mutableListOf<Decided>()
    val policyReserves = mutableListOf<PolicyReserve>()
    val abortedPositions = mutableListOf<Int>()
    var lastSyncedPolicies: Set<PolicyMeterKey>? = null
        private set
    var shortCircuits = 0
        private set
    var degradedBatches = 0
        private set
    var reservedHits = 0L
        private set
    var servedHits = 0L
        private set

    override fun reserve(priority: Priority, status: Status, origin: DecisionOrigin) {
        reserves += Reserve(priority, status, origin)
    }

    override fun decided(priority: BatchPriority, status: Status) {
        decidedRequests += Decided(priority, status)
    }

    override fun batchShortCircuited() {
        shortCircuits++
    }

    override fun batchAborted(denierPosition: Int) {
        abortedPositions += denierPosition
    }

    override fun batchDegraded() {
        degradedBatches++
    }

    override fun reservedHits(hits: Long) {
        reservedHits += hits
    }

    override fun servedHits(hits: Long) {
        servedHits += hits
    }

    override fun policyReserve(dimension: Dimension, override: DimensionValue?, priority: Priority, status: Status) {
        policyReserves += PolicyReserve(dimension, override, priority, status)
    }

    override fun syncDetailedPolicies(active: Set<PolicyMeterKey>) {
        lastSyncedPolicies = active
    }
}
