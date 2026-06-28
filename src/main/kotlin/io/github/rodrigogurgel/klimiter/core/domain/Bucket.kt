package io.github.rodrigogurgel.klimiter.core.domain

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Identidade do bucket: eixo + valor + início da janela (§4.2). */
data class BucketKey(val dimension: Dimension, val value: DimensionValue, val windowStartEpochSecond: Long)

/**
 * Agregado local por (dimensão, valor, janela) e seus fast paths (§4.2, §5). Estado atômico sem
 * boxing no hot path: [AtomicLong]/[AtomicBoolean] + `compareAndSet`. A semântica volátil seq-cst
 * casa com a ordem de publicação do §5.
 *
 * Um bucket pertence a UMA janela; quando a janela vira, a evicção (§4.2) remove o velho e o índice
 * cria um novo, resetando o estado. O snapshot do livre global usa a representação **otimista** do
 * §4.2 (inicia em [capacity] ≡ "ninguém arrendou"), dispensando um flag "já observou".
 */
class Bucket(val key: BucketKey, val storageKey: String, val capacity: Long, val window: Window) {
    /** Crédito ALTA já arrendado do contador e ainda não consumido localmente. */
    private val creditCounter = AtomicLong(0)

    /** Snapshot de `capacity − já_arrendado`. Monotônico decrescente na janela (§4.3). */
    private val freeGlobalCounter = AtomicLong(capacity)

    /** Latch terminal de janela cheia (`livre_global == 0`, §4.3). */
    private val exhaustedLatch = AtomicBoolean(false)

    /** Single-flight de renovação por bucket (§5): suspende, não bloqueia. */
    val renewMutex = Mutex()

    /** Fim da janela em ms — usado pela evicção (§4.2). */
    val expiryMillis: Long = window.endEpochSecond * MILLIS_PER_SECOND

    /** Crédito local disponível (§4.2). */
    val localCredit: Long get() = creditCounter.get()

    /** Snapshot do livre global (§4.3). */
    val freeGlobal: Long get() = freeGlobalCounter.get()

    /** Latch de esgotado (§4.3). */
    val exhausted: Boolean get() = exhaustedLatch.get()

    /** L1 (§5): consome `hits` do crédito local via CAS; `false` se não cabe. */
    fun tryConsumeLocal(hits: Long): Boolean {
        if (hits > 0) {
            while (true) {
                val cur = creditCounter.get()
                if (cur < hits) return false
                if (creditCounter.compareAndSet(cur, cur - hits)) break
            }
        }
        return true
    }

    /**
     * Consome **até** `hits` do crédito local via CAS, devolvendo quanto consumiu (`0..hits`). Usado
     * pela BAIXA para drenar o prefetch já contado antes de arrendar o faltante (§6.1) — race-free:
     * se a ALTA concorrente esvaziar o crédito no meio, devolve só o que coube.
     */
    fun tryConsumeUpTo(hits: Long): Long {
        if (hits <= 0) return 0
        var taken = 0L
        var settled = false
        while (!settled) {
            val cur = creditCounter.get()
            if (cur <= 0) {
                settled = true
            } else {
                val take = minOf(cur, hits)
                settled = creditCounter.compareAndSet(cur, cur - take)
                if (settled) taken = take
            }
        }
        return taken
    }

    /** Refund local (§7.3): devolve crédito não usado. */
    fun refundLocal(hits: Long) {
        if (hits > 0) creditCounter.addAndGet(hits)
    }

    /** Publica o resultado de um lease (§5): primeiro o crédito local, depois o snapshot/latch. */
    fun onLeaseResult(granted: Long, free: Long) {
        if (granted > 0) creditCounter.addAndGet(granted)
        publishFreeGlobal(free)
    }

    /** Atualiza o livre global monotonicamente (só encolhe, §4.3) e o latch de esgotado. */
    fun publishFreeGlobal(free: Long) {
        val clamped = free.coerceAtLeast(0)
        val now = freeGlobalCounter.updateAndGet { cur -> minOf(cur, clamped) }
        if (now == 0L) exhaustedLatch.set(true)
    }

    /** Decisão PERMITIDO com a capacidade restante estimada (§2.1). */
    fun allowed(nowMillis: Long): Decision =
        Decision(Status.ALLOWED, Remaining(freeGlobal), window.ttl(nowMillis), ReportedCapacity(capacity))

    /** Decisão NEGADO (§2.1). */
    fun denied(nowMillis: Long): Decision =
        Decision(Status.DENIED, Remaining.NONE, window.ttl(nowMillis), ReportedCapacity(capacity))

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
