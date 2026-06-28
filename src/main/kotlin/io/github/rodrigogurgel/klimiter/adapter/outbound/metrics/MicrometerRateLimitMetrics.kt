package io.github.rodrigogurgel.klimiter.adapter.outbound.metrics

import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Adapter Micrometer da porta [RateLimitMetrics] (OBSERVABILIDADE.md §1.2). Os contadores são
 * pré-criados (cardinalidade fechada: `priority` × `status`), evitando lookup no hot path.
 */
@Component
class MicrometerRateLimitMetrics(registry: MeterRegistry) : RateLimitMetrics {
    private val reserveCounters: Map<Pair<Priority, Status>, Counter> =
        Priority.entries.flatMap { priority ->
            Status.entries.map { status ->
                (priority to status) to registry.counter(
                    "klimiter.reserve",
                    "priority",
                    priority.name.lowercase(),
                    "status",
                    status.name.lowercase(),
                )
            }
        }.toMap()

    private val reserveHighPathCounters: Map<ReservePath, Counter> =
        ReservePath.entries.associateWith { path ->
            registry.counter("klimiter.reserve.high", "path", path.name.lowercase())
        }

    private val shortCircuit = registry.counter("klimiter.batch.shortcircuit")
    private val refund = registry.counter("klimiter.batch.refund")

    override fun reserve(priority: Priority, status: Status) {
        reserveCounters.getValue(priority to status).increment()
    }

    override fun reserveHighPath(path: ReservePath) {
        reserveHighPathCounters.getValue(path).increment()
    }

    override fun batchShortCircuited() = shortCircuit.increment()

    override fun batchRefunded() = refund.increment()
}
