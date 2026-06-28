package io.github.rodrigogurgel.klimiter.adapter.outbound.metrics

import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MicrometerRateLimitMetricsTest {
    @Test
    fun `counts reserves by priority and status`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.reserve(Priority.HIGH, Status.ALLOWED)
        metrics.reserve(Priority.HIGH, Status.ALLOWED)
        metrics.reserve(Priority.LOW, Status.DENIED)
        assertEquals(2.0, registry.counter("klimiter.reserve", "priority", "high", "status", "allowed").count())
        assertEquals(1.0, registry.counter("klimiter.reserve", "priority", "low", "status", "denied").count())
    }

    @Test
    fun `counts reserve high path by level`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.reserveHighPath(ReservePath.L1)
        metrics.reserveHighPath(ReservePath.L1)
        metrics.reserveHighPath(ReservePath.L4)
        assertEquals(2.0, registry.counter("klimiter.reserve.high", "path", "l1").count())
        assertEquals(1.0, registry.counter("klimiter.reserve.high", "path", "l4").count())
    }

    @Test
    fun `counts short-circuits and refunds`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerRateLimitMetrics(registry)
        metrics.batchShortCircuited()
        metrics.batchRefunded()
        metrics.batchRefunded()
        assertEquals(1.0, registry.counter("klimiter.batch.shortcircuit").count())
        assertEquals(2.0, registry.counter("klimiter.batch.refund").count())
    }
}
