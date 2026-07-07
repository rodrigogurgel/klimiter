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
        metrics.batchDegraded()
        assertEquals(1.0, registry.counter("klimiter.batch.shortcircuit").count())
        assertEquals(1.0, registry.counter("klimiter.batch.degraded").count())
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
        // '-' fora de [A-Za-z0-9_] é saneado para '_'.
        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user_id.user_42", "priority", "low", "status", "denied").count(),
        )
    }

    @Test
    fun `a dotted dimension does not collide with dimension plus override`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        // O '.' do nome é só estrutural: 'user.id' vira 'user_id'; 'user' + override 'id' vira 'user.id'.
        metrics.policyReserve(Dimension("user.id"), override = null, Priority.HIGH, Status.ALLOWED)
        metrics.policyReserve(Dimension("user"), DimensionValue("id"), Priority.HIGH, Status.ALLOWED)

        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user_id", "priority", "high", "status", "allowed").count(),
        )
        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user.id", "priority", "high", "status", "allowed").count(),
        )
    }

    @Test
    fun `sync keeps a collided meter alive while a colliding policy survives`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        // Identidades diferentes, mesmo nome saneado: 'user-id' e 'user_id' → 'user_id'.
        val dashed = PolicyMeterKey(Dimension("user-id"), null)
        val underscored = PolicyMeterKey(Dimension("user_id"), null)
        metrics.syncDetailedPolicies(setOf(dashed, underscored))

        // 'user-id' sai da config: o meter compartilhado sobrevive para 'user_id'.
        metrics.syncDetailedPolicies(setOf(underscored))
        metrics.policyReserve(Dimension("user_id"), override = null, Priority.HIGH, Status.ALLOWED)
        assertEquals(
            1.0,
            registry.counter("klimiter.policy.reserve.user_id", "priority", "high", "status", "allowed").count(),
        )

        // Última policy do nome sai: aí sim o meter é removido do registry.
        metrics.syncDetailedPolicies(emptySet())
        assertNull(registry.find("klimiter.policy.reserve.user_id").counter())
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
