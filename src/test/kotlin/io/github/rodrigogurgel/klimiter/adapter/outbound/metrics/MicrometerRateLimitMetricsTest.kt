package io.github.rodrigogurgel.klimiter.adapter.outbound.metrics

import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicrometerRateLimitMetricsTest {
    @Test
    fun `counts reserves by priority, status and origin`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.reserve(Priority.HIGH, Status.ALLOWED, DecisionOrigin.CENTRAL)
        metrics.reserve(Priority.HIGH, Status.ALLOWED, DecisionOrigin.CENTRAL)
        metrics.reserve(Priority.LOW, Status.DENIED, DecisionOrigin.LOCAL)
        assertEquals(
            2.0,
            registry.counter(
                "klimiter.reserve",
                "priority",
                "high",
                "status",
                "allowed",
                "origin",
                "central",
            ).count(),
        )
        assertEquals(
            1.0,
            registry.counter("klimiter.reserve", "priority", "low", "status", "denied", "origin", "local").count(),
        )
    }

    @Test
    fun `counts short-circuits and aborted batches by denier position`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.batchShortCircuited()
        metrics.batchAborted(0)
        metrics.batchAborted(1)
        metrics.batchAborted(7) // acima do teto → agrega em "3+"
        assertEquals(1.0, registry.counter("klimiter.batch.shortcircuit").count())
        assertEquals(1.0, registry.counter("klimiter.batch.aborted", "denier_position", "0").count())
        assertEquals(1.0, registry.counter("klimiter.batch.aborted", "denier_position", "1").count())
        assertEquals(1.0, registry.counter("klimiter.batch.aborted", "denier_position", "3+").count())
    }

    @Test
    fun `accumulates reserved and served hits - the delta is the prefix burn`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.reservedHits(3)
        metrics.reservedHits(2)
        metrics.servedHits(3)
        assertEquals(5.0, registry.counter("klimiter.hits.reserved").count())
        assertEquals(3.0, registry.counter("klimiter.hits.served").count())
    }

    @Test
    fun `policy reserve names the meter by dimension for the default and sanitizes the value for overrides`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.policyReserve(Dimension("user_id"), override = null, Priority.HIGH, Status.ALLOWED)
        metrics.policyReserve(Dimension("user_id"), DimensionValue("user-42"), Priority.LOW, Status.DENIED)

        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user_id", "priority", "high", "status", "allowed").count(),
        )
        // '-' fora de [A-Za-z0-9_.] é saneado para '_'.
        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user_id.user_42", "priority", "low", "status", "denied").count(),
        )
    }

    @Test
    fun `sync pre-creates active policy meters and removes the ones that left`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        val def = PolicyMeterKey(Dimension("user_id"), null)
        val vip = PolicyMeterKey(Dimension("user_id"), DimensionValue("vip"))

        metrics.syncDetailedPolicies(setOf(def, vip))
        // Eager: meter existe com tráfego zero (priority × status pré-criados).
        assertEquals(
            0.0,
            registry.counter("klimiter.policy.reserve.user_id.vip", "priority", "high", "status", "allowed").count(),
        )

        // Reload sem o override: o meter do override é removido; o da default permanece.
        metrics.syncDetailedPolicies(setOf(def))
        assertNull(registry.find("klimiter.policy.reserve.user_id.vip").counter())
        assertEquals(
            0.0,
            registry.counter("klimiter.policy.reserve.user_id", "priority", "high", "status", "allowed").count(),
        )
    }
}
