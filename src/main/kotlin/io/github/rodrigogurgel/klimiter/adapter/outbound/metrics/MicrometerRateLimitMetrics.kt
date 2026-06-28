package io.github.rodrigogurgel.klimiter.adapter.outbound.metrics

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Micrometer da porta [RateLimitMetrics] (OBSERVABILIDADE.md §1.2). Os contadores do hot path
 * são pré-criados (cardinalidade fechada: `priority` × `status`), evitando lookup no hot path.
 *
 * O contador por policy `klimiter.policy.reserve` (§1.3, exceção deliberada) embute a identidade da
 * policy no **nome** do meter (`...reserve.<dimension>[.<value>]`, saneado) e é gerenciado por
 * **reconciliação eager** ([syncDetailedPolicies]): pré-criado na carga do snapshot e removido quando
 * a policy sai da config, com `priority`/`status` como tags.
 */
@Component
class MicrometerRateLimitMetrics(private val registry: MeterRegistry) : RateLimitMetrics {
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

    /** Contadores por policy detalhada (mantém a referência para poder remover do registry). */
    private val policyCounters = ConcurrentHashMap<Triple<PolicyMeterKey, Priority, Status>, Counter>()

    override fun reserve(priority: Priority, status: Status) {
        reserveCounters.getValue(priority to status).increment()
    }

    override fun reserveHighPath(path: ReservePath) {
        reserveHighPathCounters.getValue(path).increment()
    }

    override fun batchShortCircuited() = shortCircuit.increment()

    override fun batchRefunded() = refund.increment()

    override fun policyReserve(dimension: Dimension, override: DimensionValue?, priority: Priority, status: Status) {
        val key = PolicyMeterKey(dimension, override)
        // Em regime o counter já foi pré-criado pela reconciliação; computeIfAbsent é rede de
        // segurança para a corrida boot/reload (raro) — nunca perde um evento.
        policyCounters.computeIfAbsent(Triple(key, priority, status)) { createPolicyCounter(it) }.increment()
    }

    override fun syncDetailedPolicies(active: Set<PolicyMeterKey>) {
        // Pré-cria (eager) os meters das policies ativas que ainda não existem.
        active.forEach { key ->
            Priority.entries.forEach { priority ->
                Status.entries.forEach { status ->
                    policyCounters.computeIfAbsent(Triple(key, priority, status)) { createPolicyCounter(it) }
                }
            }
        }
        // Remove do registry e do mapa os meters de policies que saíram da config (ou desligaram a flag).
        val iterator = policyCounters.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first !in active) {
                registry.remove(entry.value)
                iterator.remove()
            }
        }
    }

    private fun createPolicyCounter(key: Triple<PolicyMeterKey, Priority, Status>): Counter {
        val (policyKey, priority, status) = key
        return registry.counter(
            meterName(policyKey),
            "priority",
            priority.name.lowercase(),
            "status",
            status.name.lowercase(),
        )
    }

    /** `klimiter.policy.reserve.<dimension>[.<value>]`, saneando caracteres fora de `[A-Za-z0-9_.]`. */
    private fun meterName(key: PolicyMeterKey): String {
        val suffix = key.override?.let { ".${sanitize(it.raw)}" } ?: ""
        return "klimiter.policy.reserve.${sanitize(key.dimension.raw)}$suffix"
    }

    private fun sanitize(raw: String): String =
        raw.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '.') c else '_' }.joinToString("")
}
