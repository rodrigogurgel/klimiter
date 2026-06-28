package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Decision
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /** Item do lote já com a política resolvida e sua inspeção somente-leitura (§7.1). */
    private class Inspected(val request: Request, val policy: Policy?, val inspection: Decision)

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

            is PolicyResolution.Matched -> Inspected(
                request = request,
                policy = resolution.policy,
                inspection = budget.inspect(
                    request.dimension,
                    request.value,
                    resolution.policy,
                    request.hits.value,
                    request.priority,
                    nowMillis,
                ),
            )
        }

    /** Fan-out das reservas (§7): todos menos o último em paralelo; o último inline na corrotina atual. */
    private suspend fun reserveAll(items: List<Inspected>, nowMillis: Long): List<Reservation> = coroutineScope {
        if (items.size == 1) {
            listOf(reserveOne(items.first(), nowMillis))
        } else {
            val deferred = items.dropLast(1).map { item -> async { reserveOne(item, nowMillis) } }
            val last = reserveOne(items.last(), nowMillis)
            deferred.awaitAll() + last
        }
    }

    /** §7.4: pass-through não vai ao contador; falha de backend degrada para DESCONHECIDO. */
    @Suppress("TooGenericExceptionCaught") // a porta abstrai o backend: qualquer falha → UNKNOWN (§7.4)
    private suspend fun reserveOne(item: Inspected, nowMillis: Long): Reservation {
        val policy = item.policy ?: return Reservation.Final(Decision.PASS_THROUGH)
        val request = item.request
        return try {
            budget.reserve(
                request.dimension,
                request.value,
                policy,
                request.hits.value,
                request.priority,
                nowMillis,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure // §7.4: cancelamento propaga
            log.atWarn()
                .setCause(failure)
                .addKeyValue("priority", request.priority)
                .setMessage("falha no contador global ao reservar; item degradado para UNKNOWN")
                .log()
            Reservation.Final(unknown(policy))
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
