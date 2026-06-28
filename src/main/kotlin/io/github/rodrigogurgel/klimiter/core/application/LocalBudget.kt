package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.BucketKey
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.WindowKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import java.util.concurrent.ConcurrentHashMap

/**
 * Índice local de buckets — a visão por-nó (§4.2) — mais o serviço de reserva. Um
 * [ConcurrentHashMap] (já striped) faz o get-or-create atômico via `computeIfAbsent`; a chave inclui
 * o início da janela, então uma janela nova ganha um bucket novo (§4.3) e a evicção (§4.2) remove os
 * vencidos. `nowEpochSecond` deriva de `nowMillis` por `floorDiv` para alinhar com a `Clock` (§3.2).
 */
class LocalBudget(
    private val counter: GlobalCounter,
    private val keyPrefix: String,
    private val metrics: RateLimitMetrics = RateLimitMetrics.NOOP,
) {
    private val buckets = ConcurrentHashMap<BucketKey, Bucket>()

    fun bucketFor(dimension: Dimension, value: DimensionValue, policy: Policy, nowEpochSecond: Long): Bucket {
        val window = WindowKey.window(policy, nowEpochSecond)
        val key = BucketKey(dimension, value, window.startEpochSecond)
        return buckets.computeIfAbsent(key) {
            Bucket(
                key = it,
                storageKey = WindowKey.key(keyPrefix, dimension, value, window),
                capacity = policy.capacity.requestsPerUnit.toLong(),
                window = window,
            )
        }
    }

    suspend fun reserve(
        dimension: Dimension,
        value: DimensionValue,
        policy: Policy,
        hits: Long,
        priority: Priority,
        nowMillis: Long,
    ): Reservation {
        val bucket = bucketFor(dimension, value, policy, epochSecond(nowMillis))
        val reservation = when (priority) {
            Priority.HIGH -> ReserveHigh.reserve(bucket, counter, prefetchBlock(policy), hits, nowMillis, metrics)
            Priority.LOW -> Pacing.reserveLow(bucket, counter, hits, nowMillis)
        }
        metrics.reserve(priority, reservation.decision.status)
        return reservation
    }

    /**
     * Inspeção somente-leitura do lote (§7.1): espelha apenas negações garantidas, sem mutar nada.
     * `hits ≤ 0` → permitido (nada a cobrar); `hits > capacidade` → negado (impossível por definição);
     * senão roteia por prioridade.
     */
    fun inspect(
        dimension: Dimension,
        value: DimensionValue,
        policy: Policy,
        hits: Long,
        priority: Priority,
        nowMillis: Long,
    ): Decision {
        val bucket = bucketFor(dimension, value, policy, epochSecond(nowMillis))
        return when {
            hits <= 0 -> bucket.allowed(nowMillis)
            hits > bucket.capacity -> bucket.denied(nowMillis)
            priority == Priority.HIGH -> inspectHigh(bucket, hits, nowMillis)
            else -> inspectLow(bucket, hits, nowMillis)
        }
    }

    /** §7.1 ALTA: esgotado E nem o disponível estimado (local + livre global) serve. */
    private fun inspectHigh(bucket: Bucket, hits: Long, nowMillis: Long): Decision {
        val denied = bucket.exhausted && hits > bucket.localCredit + bucket.freeGlobal
        return if (denied) bucket.denied(nowMillis) else bucket.allowed(nowMillis)
    }

    /** §7.1 BAIXA: pré-portão — o faltante (após o crédito local) não cabe na linha. */
    private fun inspectLow(bucket: Bucket, hits: Long, nowMillis: Long): Decision {
        val line = ReleaseLine.line(bucket.capacity, bucket.window.elapsed(nowMillis), bucket.window.duration)
        val missing = maxOf(0L, hits - bucket.localCredit)
        val estLeased = bucket.capacity - bucket.freeGlobal
        val denied = bucket.exhausted || estLeased + missing > line
        return if (denied) bucket.denied(nowMillis) else bucket.allowed(nowMillis)
    }

    /** Varredura de evicção (§4.2): remove buckets de janelas já expiradas. Devolve quantos saíram. */
    fun evictExpired(nowMillis: Long): Int {
        var removed = 0
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiryMillis < nowMillis) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    /** Tamanho atual do índice (alimenta o gauge `klimiter.bucket.index.size`, OBSERVABILIDADE §1.2). */
    fun size(): Int = buckets.size

    private fun prefetchBlock(policy: Policy): Long = policy.prefetch.units(policy.capacity).toLong()

    private fun epochSecond(nowMillis: Long): Long = Math.floorDiv(nowMillis, MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
