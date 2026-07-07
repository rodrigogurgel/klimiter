package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.BucketKey
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.DecisionOrigin
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.WindowKey
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import java.util.concurrent.ConcurrentHashMap

/**
 * O estado local do V2 (§5) + o serviço de reserva: índice de buckets com **snapshot monotônico**,
 * as regras de **negação local** deny-only (§5.2) e a estatística de **pressão** (§5.3).
 *
 * O nó local **só nega; nunca admite** (§1): toda admissão é confirmada pelo incremento condicional
 * do central (§4). Um [ConcurrentHashMap] (já striped) faz o get-or-create atômico via
 * `computeIfAbsent`; a chave inclui o início da janela, então uma janela nova ganha um bucket novo
 * e a evicção (§5.4) remove os vencidos.
 */
class LocalState(
    private val counter: GlobalCounter,
    private val keyPrefix: String,
    private val metrics: RateLimitMetrics = RateLimitMetrics.NOOP,
) {
    private val buckets = ConcurrentHashMap<BucketKey, Bucket>()
    private val pressure = PressureBook()

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

    /**
     * Inspeção somente-leitura do lote (§7.1): espelha apenas as negações **garantidas** do §5.2.
     * `hits ≤ 0` → permitido (nada a cobrar); ALLOWED aqui significa "não é negação garantida" —
     * a admissão de verdade só existe no central.
     */
    fun inspect(bucket: Bucket, hits: Long, priority: Priority, nowMillis: Long): Decision = when {
        hits <= 0 -> bucket.allowed(nowMillis)
        deniedLocally(bucket, hits, priority, nowMillis) -> bucket.denied(nowMillis)
        else -> bucket.allowed(nowMillis)
    }

    /**
     * Reserva de um item (§4): re-testa a negação local (o snapshot pode ter aprendido algo desde a
     * inspeção — de graça) e então confirma no central via incremento condicional. **Toda** resposta
     * central atualiza o snapshot (§5.1) e a pressão (§5.3) — inclusive as negações.
     */
    suspend fun reserve(bucket: Bucket, hits: Long, priority: Priority, nowMillis: Long): Decision = when {
        hits <= 0 -> bucket.allowed(nowMillis)
        deniedLocally(bucket, hits, priority, nowMillis) -> denyLocally(bucket, priority, nowMillis)
        else -> acquireCentrally(bucket, hits, priority, nowMillis)
    }

    /** Negação de graça (§5.2): zero round-trips; conta a origem LOCAL e alimenta a pressão. */
    private fun denyLocally(bucket: Bucket, priority: Priority, nowMillis: Long): Decision {
        metrics.reserve(priority, Status.DENIED, DecisionOrigin.LOCAL)
        pressure.record(bucket.key.dimension, bucket.key.value, denied = true, nowMillis)
        return bucket.denied(nowMillis)
    }

    /** Confirmação no central (§4): incremento condicional; a resposta ensina snapshot e pressão. */
    private suspend fun acquireCentrally(bucket: Bucket, hits: Long, priority: Priority, nowMillis: Long): Decision {
        val result = counter.tryAcquire(
            bucket.storageKey,
            bucket.capacity,
            hits,
            priority,
            bucket.window.elapsed(nowMillis),
            bucket.window.duration,
            bucket.window.ttl(nowMillis),
        )
        bucket.observe(result.counter)
        pressure.record(bucket.key.dimension, bucket.key.value, denied = !result.admitted, nowMillis)
        return if (result.admitted) {
            metrics.reserve(priority, Status.ALLOWED, DecisionOrigin.CENTRAL)
            metrics.reservedHits(hits)
            bucket.allowed(nowMillis)
        } else {
            metrics.reserve(priority, Status.DENIED, DecisionOrigin.CENTRAL)
            bucket.denied(nowMillis)
        }
    }

    /** Pressão observada da chave (§5.3) — a régua da ordenação da reserva do lote (§7.3). */
    fun pressureOf(dimension: Dimension, value: DimensionValue): Double = pressure.pressureOf(dimension, value)

    /**
     * Registra uma negação decidida fora da reserva — a inspeção que condenou o lote (§7.1) — para
     * que a pressão da chave negadora reflita também os short-circuits.
     */
    fun recordDenied(dimension: Dimension, value: DimensionValue, nowMillis: Long) {
        pressure.record(dimension, value, denied = true, nowMillis)
    }

    /**
     * Nega localmente **sse** o central garantidamente negaria (§5.2): `snapshot ≤ contador real`
     * (monotônico, §5.1) e a linha local nunca é menor que a do central (§6.3) ⇒ negar aqui nunca
     * nega o que o central admitiria. `hits > capacidade` é impossível por definição.
     */
    private fun deniedLocally(bucket: Bucket, hits: Long, priority: Priority, nowMillis: Long): Boolean {
        if (hits > bucket.capacity) return true
        val threshold = when (priority) {
            Priority.HIGH -> bucket.capacity
            Priority.LOW -> ReleaseLine.line(bucket.capacity, bucket.window.elapsed(nowMillis), bucket.window.duration)
        }
        return bucket.snapshot + hits > threshold
    }

    /**
     * Varredura de evicção (§5.4): remove buckets de janelas expiradas e estatísticas de pressão de
     * chaves frias. Devolve quantos buckets saíram (alimenta o log da varredura).
     */
    fun evictExpired(nowMillis: Long): Int {
        var removed = 0
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiryMillis < nowMillis) {
                iterator.remove()
                removed++
            }
        }
        pressure.evictIdle(nowMillis, PRESSURE_IDLE_MILLIS)
        return removed
    }

    /** Tamanho atual do índice (alimenta o gauge `klimiter.bucket.index.size`, OBSERVABILIDADE §1.2). */
    fun size(): Int = buckets.size

    /** Converte ms → segundos do epoch alinhado à `Clock` (§3.2). Público p/ o lote resolver o bucket. */
    fun epochSecond(nowMillis: Long): Long = Math.floorDiv(nowMillis, MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        /** Pressão de chave sem tráfego há 10 min é ruído: evicta junto com a varredura (§5.4). */
        const val PRESSURE_IDLE_MILLIS = 10 * 60 * 1000L
    }
}
