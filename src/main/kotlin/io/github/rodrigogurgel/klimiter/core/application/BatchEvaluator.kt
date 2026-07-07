package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchPriority
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Remaining
import io.github.rodrigogurgel.klimiter.core.domain.ReportedCapacity
import io.github.rodrigogurgel.klimiter.core.domain.Request
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.PolicyResolution
import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import io.github.rodrigogurgel.klimiter.core.port.outbound.PolicyRepository
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Avaliação all-or-nothing do lote (DESIGN-CONCEITUAL-V2.md §7): resolve a política e inspeciona
 * cada item (§7.1) → short-circuit se a inspeção já condena → **reserva sequencial, ordenada por
 * pressão decrescente** (§7.2/§7.3), abortando na primeira negação — os itens restantes nunca são
 * tocados. **Não há refund** (§7.4): o que o prefixo reservou antes do negador fica consumido até a
 * janela expirar (queima de prefixo, medida por `reserved − served`, §13).
 *
 * Por que sequencial e não fan-out: sem refund, paralelizar reservaria todas as chaves de um lote
 * condenado e desperdiçaria todas as irmãs do negador; o sequencial ordenado desperdiça no pior
 * caso o prefixo — e, com a ordenação certa, o negador está na posição 0 e o desperdício é zero.
 *
 * Robustez (§7.5): falha do contador ao reservar degrada **aquele item** para [Status.UNKNOWN] e
 * aborta os restantes — que ainda passam por re-inspeção local (de graça): uma negação garantida
 * entre eles vira NEGADO no veredito, nunca mascarada pelo DESCONHECIDO. O **cancelamento** é
 * propagado (não mascarado).
 */
class BatchEvaluator(
    private val state: LocalState,
    private val policies: PolicyRepository,
    private val clock: Clock,
    private val metrics: RateLimitMetrics = RateLimitMetrics.NOOP,
) : EvaluateUseCase {
    private val log = LoggerFactory.getLogger(BatchEvaluator::class.java)

    /**
     * Item do lote com a política e o **bucket** resolvidos uma única vez (§5.1, [Matched]) e sua
     * inspeção somente-leitura (§7.1). [matched] nulo ⟺ pass-through (§8): não vai ao contador.
     */
    private class Inspected(val request: Request, val matched: Matched?, val inspection: Decision) {
        /** O item cobra o contador central? (política casada e algo a consumir) */
        val goesCentral: Boolean get() = matched != null && request.hits.value > 0
    }

    /**
     * Política + bucket de um item com política casada (§8.1) — sempre os dois juntos. [override]
     * carrega o valor que casou um override exato (ou `null` na default), para o meter por policy.
     */
    private class Matched(val policy: Policy, val bucket: Bucket, val override: DimensionValue?)

    override suspend fun evaluate(batch: Batch): BatchResult {
        if (batch.requests.isEmpty()) {
            // §2.1: lote vazio ainda é uma resposta — conta no total (vazio classifica como HIGH).
            metrics.decided(BatchPriority.of(batch.requests), Status.ALLOWED)
            return BatchResult(Status.ALLOWED, emptyList())
        }

        val nowMillis = clock.nowMillis()
        val snapshot = policies.current()
        val items = batch.requests.map { request -> inspect(request, snapshot, nowMillis) }
        val doomed = aggregateDoom(items, nowMillis)

        // §7.1: se a inspeção já condena — item a item ou pelo agregado por chave —, ninguém
        // reserva: zero escritas, zero round-trips.
        val result = if (doomed.isNotEmpty() || items.any { it.inspection.status == Status.DENIED }) {
            shortCircuit(items, doomed, nowMillis)
            BatchResult(Status.DENIED, items.mapIndexed { index, item -> doomed[index] ?: item.inspection })
        } else {
            reserveSequentially(items, nowMillis)
        }
        // §2.1: uma resposta contada por REQUEST — veredito coletivo + prioridade agregada do lote.
        metrics.decided(BatchPriority.of(batch.requests), result.overall)
        return result
    }

    /**
     * §7.1 estendido ao agregado por chave: itens do lote que caem no MESMO bucket precisam caber
     * **juntos** (all-or-nothing). Se `snapshot + Σhits` já estoura a capacidade, nenhuma ordem de
     * admissão salva o lote (o contador nunca passa da capacidade, §4) — negação garantida sem
     * round-trip, em vez de queimar prefixo para descobrir no central. O agrupamento é por
     * identidade do [Bucket]: itens da mesma `(dimensão, valor)` resolvem para a mesma instância
     * dentro de um `evaluate` ([LocalState.bucketFor] é get-or-create).
     */
    private fun aggregateDoom(items: List<Inspected>, nowMillis: Long): Map<Int, Decision> {
        if (items.size < 2) return emptyMap()
        val indicesByBucket = HashMap<Bucket, MutableList<Int>>()
        for (index in items.indices) {
            val matched = items[index].matched
            if (matched == null || !items[index].goesCentral) continue
            indicesByBucket.getOrPut(matched.bucket) { ArrayList(2) }.add(index)
        }
        val doomed = HashMap<Int, Decision>()
        for ((bucket, indices) in indicesByBucket) {
            if (indices.size < 2) continue // item único: a inspeção individual já é mais forte
            val total = indices.sumOf { items[it].request.hits.value }
            // Prioridade ALTA ⇒ limiar = capacidade: o único teto que vale para o agregado em
            // qualquer mistura de prioridades (a linha da BAIXA é ≤ capacidade; só apertaria mais).
            val combined = state.inspect(bucket, total, Priority.HIGH, nowMillis)
            if (combined.status == Status.DENIED) {
                for (index in indices) doomed[index] = combined
            }
        }
        return doomed
    }

    /** §7.1: resolve a política (eixo sem política → pass-through, §8) + inspeção somente-leitura. */
    private fun inspect(request: Request, snapshot: PolicySnapshot, nowMillis: Long): Inspected =
        when (val resolution = snapshot.resolve(request.dimension, request.value)) {
            PolicyResolution.PassThrough -> Inspected(request, null, Decision.PASS_THROUGH)

            is PolicyResolution.Matched -> {
                val epochSecond = state.epochSecond(nowMillis)
                val bucket = state.bucketFor(request.dimension, request.value, resolution.policy, epochSecond)
                Inspected(
                    request = request,
                    matched = Matched(resolution.policy, bucket, resolution.override),
                    inspection = state.inspect(bucket, request.hits.value, request.priority, nowMillis),
                )
            }
        }

    /** §7.1: registra o short-circuit e alimenta a pressão das chaves que condenaram o lote (§5.3). */
    private fun shortCircuit(items: List<Inspected>, doomed: Map<Int, Decision>, nowMillis: Long) {
        metrics.batchShortCircuited()
        for (index in items.indices) {
            val decision = doomed[index] ?: items[index].inspection
            if (decision.status == Status.DENIED) {
                state.recordDenied(items[index].request.dimension, items[index].request.value, nowMillis)
            }
        }
    }

    /**
     * §7.2: reserva um a um, na ordem de pressão decrescente, abortando na primeira não-admissão.
     * Itens nunca tentados (abortados, pass-through, `hits ≤ 0`) ecoam a decisão da inspeção — o
     * veredito coletivo é quem manda no all-or-nothing. Exceção (§7.5): num abort por falha, os
     * nunca tentados são re-inspecionados localmente (de graça) e uma negação garantida entre eles
     * faz NEGADO dominar DESCONHECIDO no veredito.
     */
    private suspend fun reserveSequentially(items: List<Inspected>, nowMillis: Long): BatchResult {
        val order = reservationOrder(items)
        val decisions = arrayOfNulls<Decision>(items.size)
        var denierPosition = -1
        var unknownPosition = -1

        for (position in order.indices) {
            val index = order[position]
            val decision = reserveOne(items[index], nowMillis)
            decisions[index] = decision
            if (decision.status != Status.ALLOWED) {
                if (decision.status == Status.DENIED) denierPosition = position else unknownPosition = position
                break // aborta os restantes — nunca tocados (§7.2)
            }
        }

        // §7.5: o abort por falha não mascara uma negação garantida — o snapshot pode ter aprendido
        // desde a inspeção (ex.: um lote concorrente esgotou a chave) e re-inspecionar é local.
        val deniedUntried = unknownPosition >= 0 &&
            denyUntried(items, order, unknownPosition + 1, decisions, nowMillis)

        for (i in items.indices) {
            if (decisions[i] == null) decisions[i] = items[i].inspection
        }
        val overall = when {
            denierPosition >= 0 || deniedUntried -> Status.DENIED
            unknownPosition >= 0 -> Status.UNKNOWN
            else -> Status.ALLOWED
        }
        recordOutcome(overall, denierPosition, items)
        @Suppress("UNCHECKED_CAST")
        return BatchResult(overall, decisions.asList() as List<Decision>)
    }

    /** §13: o desfecho do lote vira métrica — served, abort por negador (§7.3) ou por falha (§7.5). */
    private fun recordOutcome(overall: Status, denierPosition: Int, items: List<Inspected>) {
        when {
            overall == Status.ALLOWED -> recordServed(items)
            denierPosition >= 0 -> metrics.batchAborted(denierPosition)
            else -> metrics.batchDegraded() // abort por falha (§7.5): não há negador na ordem
        }
    }

    /**
     * §7.3: ordena os itens que vão ao central por **pressão observada decrescente** (o negador
     * mais provável primeiro — se ele negar, nega de graça); empate → menor capacidade primeiro
     * (o slot mais escasso é o mais caro de queimar). Lotes têm poucos itens (≤ max-batch-size);
     * a ordenação com comparator é O(n log n) sobre n ≈ 3 — legibilidade vence micro-otimização.
     */
    private fun reservationOrder(items: List<Inspected>): List<Int> {
        val central = ArrayList<Int>(items.size)
        for (i in items.indices) {
            if (items[i].goesCentral) central.add(i)
        }
        if (central.size > 1) {
            val pressures = DoubleArray(items.size)
            for (index in central) {
                pressures[index] = state.pressureOf(items[index].request.dimension, items[index].request.value)
            }
            central.sortWith(
                compareByDescending<Int> { pressures[it] }.thenBy { items[it].matched?.bucket?.capacity },
            )
        }
        return central
    }

    /**
     * §7.5: re-inspeciona os itens nunca tentados (posições `from..fim` da ordem) após um abort por
     * falha; negações garantidas substituem o eco da inspeção em [decisions] e alimentam a pressão.
     * Devolve se achou alguma.
     */
    private fun denyUntried(
        items: List<Inspected>,
        order: List<Int>,
        from: Int,
        decisions: Array<Decision?>,
        nowMillis: Long,
    ): Boolean {
        var denied = false
        for (position in from until order.size) {
            val index = order[position]
            val item = items[index]
            // Total por defesa em profundidade: pass-through não entra na ordem; se entrar, só ecoa.
            val matched = item.matched ?: continue
            val fresh = state.inspect(matched.bucket, item.request.hits.value, item.request.priority, nowMillis)
            if (fresh.status == Status.DENIED) {
                state.recordDenied(item.request.dimension, item.request.value, nowMillis)
                decisions[index] = fresh
                denied = true
            }
        }
        return denied
    }

    /**
     * §7.5: falha de backend degrada o item para DESCONHECIDO; cancelamento propaga. **Total**: um
     * item sem política casada ecoa a própria inspeção (pass-through) — a ordenação (§7.3) filtra
     * esses itens como otimização, nunca como precondição de corretude, então um caller novo (ou
     * uma mudança no filtro) não vira exceção em tráfego vivo.
     */
    @Suppress("TooGenericExceptionCaught") // a porta abstrai o backend: qualquer falha → UNKNOWN (§7.5)
    private suspend fun reserveOne(item: Inspected, nowMillis: Long): Decision {
        val matched = item.matched ?: return item.inspection
        val request = item.request
        val decision = try {
            state.reserve(matched.bucket, request.hits.value, request.priority, nowMillis)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure // §7.5: cancelamento propaga
            log.atWarn()
                .setCause(failure)
                .addKeyValue("priority", request.priority)
                .setMessage("falha no contador global ao reservar; item degradado para UNKNOWN")
                .log()
            unknown(matched.policy)
        }
        // Métrica por policy (OBSERVABILIDADE.md): cobre PERMITIDO/NEGADO/DESCONHECIDO; opt-out por regra.
        if (matched.policy.detailedMetric) {
            metrics.policyReserve(request.dimension, matched.override, request.priority, decision.status)
        }
        return decision
    }

    /** §13: hits efetivamente servidos — só de lotes com veredito PERMITIDO. */
    private fun recordServed(items: List<Inspected>) {
        var hits = 0L
        for (item in items) {
            if (item.goesCentral) hits += item.request.hits.value
        }
        if (hits > 0) metrics.servedHits(hits)
    }

    /** Decisão degradada por falha de backend (§7.5): capacidade ecoada, sem estimativas. */
    private fun unknown(policy: Policy): Decision = Decision(
        status = Status.UNKNOWN,
        remaining = Remaining.NONE,
        resetAfter = Duration.ZERO,
        capacity = ReportedCapacity(policy.capacity.requestsPerUnit.toLong()),
    )
}
