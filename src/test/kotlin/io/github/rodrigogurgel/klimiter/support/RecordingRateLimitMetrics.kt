package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics

/** [RateLimitMetrics] que apenas grava as chamadas, para asserções nos testes do core. */
class RecordingRateLimitMetrics : RateLimitMetrics {
    /** Reserva detalhada por policy: (dimensão, override ou null, prioridade, status). */
    data class PolicyReserve(
        val dimension: Dimension,
        val override: DimensionValue?,
        val priority: Priority,
        val status: Status,
    )

    val reserves = mutableListOf<Pair<Priority, Status>>()
    val highPaths = mutableListOf<ReservePath>()
    val policyReserves = mutableListOf<PolicyReserve>()
    var lastSyncedPolicies: Set<PolicyMeterKey>? = null
        private set
    var shortCircuits = 0
        private set
    var refunds = 0
        private set

    override fun reserve(priority: Priority, status: Status) {
        reserves += priority to status
    }

    override fun reserveHighPath(path: ReservePath) {
        highPaths += path
    }

    override fun batchShortCircuited() {
        shortCircuits++
    }

    override fun batchRefunded() {
        refunds++
    }

    override fun policyReserve(dimension: Dimension, override: DimensionValue?, priority: Priority, status: Status) {
        policyReserves += PolicyReserve(dimension, override, priority, status)
    }

    override fun syncDetailedPolicies(active: Set<PolicyMeterKey>) {
        lastSyncedPolicies = active
    }
}
