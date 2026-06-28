package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import kotlinx.coroutines.sync.withLock

/**
 * Caminho ALTA (§5): L1 crédito local lock-free → L2 negação rápida se esgotado → L3 negação rápida
 * se nem a melhor renovação serviria → L4 renovação single-flight sob o mutex do bucket, com
 * double-check (§5). Cada nível devolve `null` para "siga adiante"; o encadeamento elvis preserva a
 * ordem do menor para o maior custo. O nível que **dispara** registra seu [ReservePath] em
 * [RateLimitMetrics] (telemetria do hot path, OBSERVABILIDADE.md §1.2).
 */
object ReserveHigh {
    suspend fun reserve(
        bucket: Bucket,
        counter: GlobalCounter,
        prefetchBlock: Long,
        hits: Long,
        nowMillis: Long,
        metrics: RateLimitMetrics,
    ): Reservation = consumeLocal(bucket, hits, nowMillis, metrics)
        ?: denyIfExhausted(bucket, nowMillis, metrics)
        ?: denyIfImpossible(bucket, hits, nowMillis, metrics)
        ?: renew(bucket, counter, prefetchBlock, hits, nowMillis, metrics)

    /** L1: cabe no crédito local já arrendado (sem trava, sem round-trip). */
    private fun consumeLocal(bucket: Bucket, hits: Long, nowMillis: Long, metrics: RateLimitMetrics): Reservation? =
        if (bucket.tryConsumeLocal(hits)) {
            metrics.reserveHighPath(ReservePath.L1)
            Reservation.High(bucket, hits, bucket.allowed(nowMillis))
        } else {
            null
        }

    /** L2: janela cheia no contador (latch terminal, §4.3). */
    private fun denyIfExhausted(bucket: Bucket, nowMillis: Long, metrics: RateLimitMetrics): Reservation? =
        if (bucket.exhausted) {
            metrics.reserveHighPath(ReservePath.L2)
            Reservation.Final(bucket.denied(nowMillis))
        } else {
            null
        }

    /**
     * L3: nem a melhor renovação serviria. `disponível_estimado = local + livre global`; como o
     * snapshot é upper-bound do livre real (§4.3), `hits > estimado` ⟹ impossível — nega sem trava
     * nem round-trip (cobre também `hits > capacidade`, pois o snapshot inicial é a capacidade).
     */
    private fun denyIfImpossible(bucket: Bucket, hits: Long, nowMillis: Long, metrics: RateLimitMetrics): Reservation? =
        if (hits > bucket.localCredit + bucket.freeGlobal) {
            metrics.reserveHighPath(ReservePath.L3)
            Reservation.Final(bucket.denied(nowMillis))
        } else {
            null
        }

    /** L4: renovação single-flight (§5) com double-check após o lock. */
    private suspend fun renew(
        bucket: Bucket,
        counter: GlobalCounter,
        prefetchBlock: Long,
        hits: Long,
        nowMillis: Long,
        metrics: RateLimitMetrics,
    ): Reservation = bucket.renewMutex.withLock {
        consumeLocal(bucket, hits, nowMillis, metrics)
            ?: denyIfExhausted(bucket, nowMillis, metrics)
            ?: leaseAndConsume(bucket, counter, prefetchBlock, hits, nowMillis, metrics)
    }

    private suspend fun leaseAndConsume(
        bucket: Bucket,
        counter: GlobalCounter,
        prefetchBlock: Long,
        hits: Long,
        nowMillis: Long,
        metrics: RateLimitMetrics,
    ): Reservation {
        metrics.reserveHighPath(ReservePath.L4) // chegou ao central: houve round-trip
        // Pede um bloco que cubra ao menos `hits` (prefetch amortiza, §5).
        val block = maxOf(hits, prefetchBlock)
        val result = counter.lease(bucket.storageKey, bucket.capacity, block, bucket.window.ttl(nowMillis))
        bucket.onLeaseResult(result.granted, result.freeGlobal)
        return if (bucket.tryConsumeLocal(hits)) {
            Reservation.High(bucket, hits, bucket.allowed(nowMillis))
        } else {
            Reservation.Final(bucket.denied(nowMillis)) // remaining < hits no contador → negado (§9)
        }
    }
}
