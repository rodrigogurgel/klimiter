package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.Decision
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Avaliação all-or-nothing do lote (§7): resolve a política e inspeciona cada item (§7.1) →
 * short-circuit se a inspeção já condena (§7.2) → reserva em paralelo (fan-out, último inline) →
 * refund das admitidas se o veredito coletivo não for PERMITIDO (§7.4).
 *
 * Robustez (§7.4): falha do contador ao reservar **degrada só aquele item** para [Status.UNKNOWN];
 * o **cancelamento** é propagado (não mascarado). A estrutura concurrency garante que o cancelamento
 * do RPC aborta as reservas irmãs.
 */
class BatchEvaluator(
    private val budget: LocalBudget,
    private val policies: PolicyRepository,
    private val clock: Clock,
    private val metrics: RateLimitMetrics = RateLimitMetrics.NOOP,
) : EvaluateUseCase {
    private val log = LoggerFactory.getLogger(BatchEvaluator::class.java)

    /**
     * Item do lote com a política e o **bucket** resolvidos uma única vez (§4.2, [Matched]) e sua
     * inspeção somente-leitura (§7.1). [matched] nulo ⟺ pass-through (§8): não vai ao contador. O
     * bucket é reaproveitado na reserva, evitando reabrir o índice por item.
     */
    private class Inspected(val request: Request, val matched: Matched?, val inspection: Decision)

    /** Política + bucket de um item com política casada (§8.1) — sempre os dois juntos. */
    private class Matched(val policy: Policy, val bucket: Bucket)

    override suspend fun evaluate(batch: Batch): BatchResult = coroutineScope {
        if (batch.requests.isEmpty()) return@coroutineScope BatchResult(Status.ALLOWED, emptyList())

        val nowMillis = clock.nowMillis()
        val snapshot = policies.current()
        val items = batch.requests.map { request -> inspect(request, snapshot, nowMillis) }

        // §7.2: se a inspeção já condena, ninguém reserva, arrenda ou refunda.
        if (items.any { it.inspection.status == Status.DENIED }) {
            metrics.batchShortCircuited()
            return@coroutineScope BatchResult(Status.DENIED, items.map(Inspected::inspection))
        }

        val reservations = reserveAll(items, nowMillis)
        val overall = overallOf(reservations)
        if (overall != Status.ALLOWED) {
            metrics.batchRefunded()
            reservations.forEach(Reservation::release) // §7.4 refund
        }
        BatchResult(overall, reservations.map(Reservation::decision))
    }

    /** §7.1: resolve a política (eixo sem política → pass-through, §8) + inspeção somente-leitura. */
    private fun inspect(request: Request, snapshot: PolicySnapshot, nowMillis: Long): Inspected =
        when (val resolution = snapshot.resolve(request.dimension, request.value)) {
            PolicyResolution.PassThrough -> Inspected(request, null, Decision.PASS_THROUGH)

            is PolicyResolution.Matched -> {
                val epochSecond = budget.epochSecond(nowMillis)
                val bucket = budget.bucketFor(request.dimension, request.value, resolution.policy, epochSecond)
                Inspected(
                    request = request,
                    matched = Matched(resolution.policy, bucket),
                    inspection = budget.inspect(bucket, request.hits.value, request.priority, nowMillis),
                )
            }
        }

    /**
     * Fan-out das reservas (§7). O paralelismo só paga quando há **I/O ao central a sobrepor**: itens
     * servidos do crédito local (ALTA que cabe no lease) ou pass-through não suspendem, então criar uma
     * corrotina para eles só custaria alocação (§7, nota "paralelismo é otimização"). Por isso:
     * dispara `async` **apenas** para os itens que podem ir ao central, roda o último deles + todos os
     * locais inline na corrotina atual (sobrepondo com os `async`), e remonta na ordem do lote.
     */
    private suspend fun reserveAll(items: List<Inspected>, nowMillis: Long): List<Reservation> = coroutineScope {
        if (items.size == 1) return@coroutineScope listOf(reserveOne(items.first(), nowMillis))

        val lastCentral = items.indexOfLast(::mayHitCentral)
        val deferred = arrayOfNulls<Deferred<Reservation>>(items.size)
        for (i in items.indices) {
            if (i != lastCentral && mayHitCentral(items[i])) {
                deferred[i] = async { reserveOne(items[i], nowMillis) }
            }
        }
        val results = arrayOfNulls<Reservation>(items.size)
        // Inline: locais, pass-through e o último item central (sobrepõe com os `async` já disparados).
        for (i in items.indices) {
            if (deferred[i] == null) results[i] = reserveOne(items[i], nowMillis)
        }
        for (i in items.indices) deferred[i]?.let { results[i] = it.await() }
        @Suppress("UNCHECKED_CAST")
        results.asList() as List<Reservation>
    }

    /**
     * Heurística (não corretude): o item **pode** suspender num round-trip ao central? BAIXA que passou
     * o short-circuit ainda valida a linha no central (§6.1); ALTA só vai ao central na renovação L4
     * (§5) — se cabe no lease (L1), está esgotada (L2) ou é impossível (L3), decide local sem I/O.
     * Errar a heurística não afeta o resultado: no máximo um item local roda inline ou um central perde
     * a sobreposição.
     */
    private fun mayHitCentral(item: Inspected): Boolean {
        val bucket = item.matched?.bucket ?: return false
        val hits = item.request.hits.value
        // ALTA só suspende se a renovação L4 for possível: não cabe no lease, não está esgotada e o
        // disponível estimado (local + livre global) ainda cobre os hits.
        val highMayRenew = hits > bucket.localCredit &&
            !bucket.exhausted &&
            hits <= bucket.localCredit + bucket.freeGlobal
        return when {
            hits <= 0 -> false
            item.request.priority == Priority.LOW -> true
            else -> highMayRenew
        }
    }

    /** §7.4: pass-through não vai ao contador; falha de backend degrada para DESCONHECIDO. */
    @Suppress("TooGenericExceptionCaught") // a porta abstrai o backend: qualquer falha → UNKNOWN (§7.4)
    private suspend fun reserveOne(item: Inspected, nowMillis: Long): Reservation {
        val matched = item.matched ?: return Reservation.Final(Decision.PASS_THROUGH)
        val request = item.request
        return try {
            budget.reserve(matched.bucket, matched.policy, request.hits.value, request.priority, nowMillis)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure // §7.4: cancelamento propaga
            log.atWarn()
                .setCause(failure)
                .addKeyValue("priority", request.priority)
                .setMessage("falha no contador global ao reservar; item degradado para UNKNOWN")
                .log()
            Reservation.Final(unknown(matched.policy))
        }
    }

    /** Decisão degradada por falha de backend (§7.4): capacidade ecoada, sem estimativas. */
    private fun unknown(policy: Policy): Decision = Decision(
        status = Status.UNKNOWN,
        remaining = Remaining.NONE,
        resetAfter = Duration.ZERO,
        capacity = ReportedCapacity(policy.capacity.requestsPerUnit.toLong()),
    )

    /** Veredito coletivo (§7): PERMITIDO se todas passam; senão NEGADO domina, e DESCONHECIDO no resto. */
    private fun overallOf(reservations: List<Reservation>): Status = when {
        reservations.all { it.admitted } -> Status.ALLOWED
        reservations.any { it.decision.status == Status.DENIED } -> Status.DENIED
        else -> Status.UNKNOWN
    }
}
