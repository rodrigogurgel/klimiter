package io.github.rodrigogurgel.klimiter.adapter.outbound.metrics

import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Micrometer da porta [RateLimitMetrics] (OBSERVABILIDADE.md §1.2). Os contadores do hot path
 * são pré-criados (cardinalidade fechada: `priority` × `status` × `origin`; posição do negador
 * limitada a [ABORT_POSITION_CAP]), evitando lookup no hot path.
 *
 * O contador por policy `klimiter.policy.reserve` (§1.3, exceção deliberada) embute a identidade da
 * policy no **nome** do meter (`...reserve.<dimension>[.<value>]`, saneado) e é gerenciado por
 * **reconciliação eager** ([syncDetailedPolicies]): pré-criado na carga do snapshot e removido quando
 * a policy sai da config, com `priority`/`status` como tags.
 */
@Component
class MicrometerRateLimitMetrics(private val registry: MeterRegistry) : RateLimitMetrics {
    private val reserveCounters: Map<Triple<Priority, Status, DecisionOrigin>, Counter> =
        Priority.entries.flatMap { priority ->
            Status.entries.flatMap { status ->
                DecisionOrigin.entries.map { origin ->
                    Triple(priority, status, origin) to registry.counter(
                        "klimiter.reserve",
                        "priority",
                        priority.name.lowercase(),
                        "status",
                        status.name.lowercase(),
                        "origin",
                        origin.name.lowercase(),
                    )
                }
            }
        }.toMap()

    private val shortCircuit = registry.counter("klimiter.batch.shortcircuit")

    /** Lotes abortados por falha de backend (§7.5) — queima de incidente, não de ordenação. */
    private val degraded = registry.counter("klimiter.batch.degraded")

    /** Posição do negador na ordem por pressão (§7.3), limitada a `0..3+` para fechar a cardinalidade. */
    private val abortedByPosition: List<Counter> = (0..ABORT_POSITION_CAP).map { position ->
        val tag = if (position == ABORT_POSITION_CAP) "$ABORT_POSITION_CAP+" else position.toString()
        registry.counter("klimiter.batch.aborted", "denier_position", tag)
    }

    /** §13: `reserved − served` é a queima de prefixo (§7.4 do design). */
    private val reservedHits = registry.counter("klimiter.hits.reserved")
    private val servedHits = registry.counter("klimiter.hits.served")

    /** Contadores por policy detalhada (mantém a referência para poder remover do registry). */
    private val policyCounters = ConcurrentHashMap<Triple<PolicyMeterKey, Priority, Status>, Counter>()

    override fun reserve(priority: Priority, status: Status, origin: DecisionOrigin) {
        reserveCounters.getValue(Triple(priority, status, origin)).increment()
    }

    override fun batchShortCircuited() = shortCircuit.increment()

    override fun batchAborted(denierPosition: Int) {
        abortedByPosition[denierPosition.coerceIn(0, ABORT_POSITION_CAP)].increment()
    }

    override fun batchDegraded() = degraded.increment()

    override fun reservedHits(hits: Long) = reservedHits.increment(hits.toDouble())

    override fun servedHits(hits: Long) = servedHits.increment(hits.toDouble())

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
        // Remove do mapa os meters de policies que saíram da config (ou desligaram a flag).
        val orphaned = ArrayList<Counter>()
        val iterator = policyCounters.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first !in active) {
                orphaned += entry.value
                iterator.remove()
            }
        }
        // Identidades saneadas podem colidir no MESMO meter (ex.: 'user-id' e 'user_id' → 'user_id');
        // só remove do registry o counter que nenhuma policy sobrevivente compartilha — senão a
        // removida silenciaria a outra até o próximo reload.
        val survivors = HashSet<Counter>(policyCounters.values)
        for (counter in orphaned) {
            if (counter !in survivors) registry.remove(counter)
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

    /**
     * `klimiter.policy.reserve.<dimension>[.<value>]`, saneando caracteres fora de `[A-Za-z0-9_]`.
     * O `.` também é saneado: no nome ele é **só estrutural** (separa dimensão de valor), então uma
     * dimensão `user.id` não colide com dimensão `user` + override `id`.
     */
    private fun meterName(key: PolicyMeterKey): String {
        val suffix = key.override?.let { ".${sanitize(it.raw)}" } ?: ""
        return "klimiter.policy.reserve.${sanitize(key.dimension.raw)}$suffix"
    }

    private fun sanitize(raw: String): String =
        raw.map { c -> if (c.isLetterOrDigit() || c == '_') c else '_' }.joinToString("")

    private companion object {
        /** Posições acima disso agregam em `3+`: interessa "primeira posição ou não", não a cauda. */
        const val ABORT_POSITION_CAP = 3
    }
}
