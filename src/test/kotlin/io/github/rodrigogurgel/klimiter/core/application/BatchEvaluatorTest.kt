package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Hits
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Request
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.DimensionPolicy
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.github.rodrigogurgel.klimiter.core.port.outbound.PolicyRepository
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.github.rodrigogurgel.klimiter.support.RecordingRateLimitMetrics
import io.github.rodrigogurgel.klimiter.support.ThrowingGlobalCounter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cobre o all-or-nothing do V2 (§7): short-circuit na inspeção, reserva sequencial ordenada por
 * pressão com abort na primeira negação (sem refund — o prefixo queima, §7.4), degradação UNKNOWN
 * e propagação de cancelamento (§7.5).
 */
class BatchEvaluatorTest {
    private val policy = Policy(Capacity(100), RateLimitUnit.MINUTE)
    private val snapshot = PolicySnapshot(mapOf(Dimension("user_id") to DimensionPolicy(default = policy)))
    private val now = 60_000L // epochSecond 60 → janela MINUTE [60,120)

    private class FixedPolicies(private val snapshot: PolicySnapshot) : PolicyRepository {
        override fun current(): PolicySnapshot = snapshot
    }

    private fun stateWith(counter: GlobalCounter, metrics: RateLimitMetrics = RateLimitMetrics.NOOP) =
        LocalState(counter, "klimiter", metrics)

    private fun evaluatorWith(
        state: LocalState,
        metrics: RateLimitMetrics = RateLimitMetrics.NOOP,
        snapshot: PolicySnapshot = this.snapshot,
    ): BatchEvaluator = BatchEvaluator(state, FixedPolicies(snapshot), Clock { now }, metrics)

    private fun req(value: String, hits: Long = 1, priority: Priority = Priority.HIGH) =
        Request(Dimension("user_id"), DimensionValue(value), Hits(hits), priority)

    /** Enche a janela da chave no central, sem o nó local saber (outro nó consumiu). */
    private suspend fun fillCentrally(counter: InMemoryGlobalCounter, value: String) {
        counter.tryAcquire("klimiter:user_id:$value:60", 100, 100, Priority.HIGH, 0.seconds, 60.seconds, 60.seconds)
    }

    @Test
    fun `empty batch is allowed`() = runTest {
        val result = evaluatorWith(stateWith(InMemoryGlobalCounter())).evaluate(Batch(emptyList()))
        assertEquals(Status.ALLOWED, result.overall)
        assertTrue(result.decisions.isEmpty())
    }

    @Test
    fun `request without policy is pass-through`() = runTest {
        val batch = Batch(listOf(Request(Dimension("ip"), DimensionValue("1.2.3.4"), Hits(1), Priority.HIGH)))
        val result = evaluatorWith(stateWith(InMemoryGlobalCounter())).evaluate(batch)
        assertEquals(Status.ALLOWED, result.overall)
        assertEquals(Status.ALLOWED, result.decisions.single().status)
    }

    @Test
    fun `all matched requests are admitted and served hits are recorded`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(InMemoryGlobalCounter(), metrics)
        val result = evaluatorWith(state, metrics).evaluate(Batch(listOf(req("uA"), req("uB"))))
        assertEquals(Status.ALLOWED, result.overall)
        assertEquals(2, result.decisions.size)
        assertEquals(2L, metrics.servedHits)
        assertEquals(2L, metrics.reservedHits) // served == reserved: nada queimou
    }

    @Test
    fun `short-circuits when an inspection denies without any round-trip`() = runTest {
        val counter = InMemoryGlobalCounter()
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(counter, metrics)
        val batch = Batch(listOf(req("uA"), req("uB", hits = 101))) // uB: hits > capacidade
        val result = evaluatorWith(state, metrics).evaluate(batch)
        assertEquals(Status.DENIED, result.overall)
        assertEquals(0, counter.counterOf("klimiter:user_id:uA:60")) // ninguém reservou (§7.1)
        assertEquals(1, metrics.shortCircuits)
    }

    @Test
    fun `an aborted batch burns the prefix - there is no refund`() = runTest {
        val counter = InMemoryGlobalCounter()
        fillCentrally(counter, "uB") // uB esgotado por outro nó; o local ainda não sabe
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(counter, metrics)

        val result = evaluatorWith(state, metrics).evaluate(Batch(listOf(req("uA"), req("uB"))))

        assertEquals(Status.DENIED, result.overall)
        // uA (posição 0 da ordem) reservou antes de uB negar: o slot fica consumido (§7.4).
        assertEquals(1, counter.counterOf("klimiter:user_id:uA:60"))
        assertEquals(listOf(1), metrics.abortedPositions) // negador na posição 1
        assertEquals(0L, metrics.servedHits)
        assertEquals(1L, metrics.reservedHits) // reserved − served = queima de prefixo (§13)
    }

    @Test
    fun `the learned denier short-circuits the next batch for free`() = runTest {
        val counter = InMemoryGlobalCounter()
        fillCentrally(counter, "uB")
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(counter, metrics)
        val evaluator = evaluatorWith(state, metrics)

        evaluator.evaluate(Batch(listOf(req("uA"), req("uB")))) // paga o aprendizado (§7.1)
        val result = evaluatorWith(state, metrics).evaluate(Batch(listOf(req("uA"), req("uB"))))

        assertEquals(Status.DENIED, result.overall)
        assertEquals(1, metrics.shortCircuits) // o segundo lote morreu na inspeção
        assertEquals(1, counter.counterOf("klimiter:user_id:uA:60")) // só o primeiro queimou
    }

    @Test
    fun `reservation order puts the highest-pressure key first and spares the siblings`() = runTest {
        val counter = InMemoryGlobalCounter()
        fillCentrally(counter, "uB")
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(counter, metrics)
        // Pressão observada de uB alta (ex.: negações vistas em short-circuits anteriores, §5.3).
        repeat(10) { state.recordDenied(Dimension("user_id"), DimensionValue("uB"), now) }

        val result = evaluatorWith(state, metrics).evaluate(Batch(listOf(req("uA"), req("uB"))))

        assertEquals(Status.DENIED, result.overall)
        // uB (maior pressão) foi primeiro e negou de graça: uA nunca foi tocada (§7.3).
        assertEquals(0, counter.counterOf("klimiter:user_id:uA:60"))
        assertEquals(listOf(0), metrics.abortedPositions)
    }

    @Test
    fun `backend failure degrades the item to UNKNOWN and aborts the rest`() = runTest {
        val state = stateWith(ThrowingGlobalCounter { IllegalStateException("redis down") })
        val result = evaluatorWith(state).evaluate(Batch(listOf(req("uA"), req("uB"))))
        assertEquals(Status.UNKNOWN, result.overall)
        assertEquals(Status.UNKNOWN, result.decisions.first().status)
        // O segundo item foi abortado sem reserva: ecoa a inspeção (o veredito coletivo manda, §7.2).
        assertEquals(Status.ALLOWED, result.decisions.last().status)
    }

    @Test
    fun `cancellation is propagated not masked as UNKNOWN`() = runTest {
        val state = stateWith(ThrowingGlobalCounter { CancellationException("client gone") })
        assertFailsWith<CancellationException> {
            evaluatorWith(state).evaluate(Batch(listOf(req("uA"))))
        }
    }

    @Test
    fun `policy reserve metric carries dimension with null override for the default`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(InMemoryGlobalCounter(), metrics)
        evaluatorWith(state, metrics).evaluate(Batch(listOf(req("uA"))))
        val recorded = metrics.policyReserves.single()
        assertEquals(Dimension("user_id"), recorded.dimension)
        assertEquals(null, recorded.override)
        assertEquals(Priority.HIGH, recorded.priority)
        assertEquals(Status.ALLOWED, recorded.status)
    }

    @Test
    fun `policy reserve metric carries the override value when an override matches`() = runTest {
        val withOverride = PolicySnapshot(
            mapOf(
                Dimension("user_id") to DimensionPolicy(
                    default = policy,
                    overrides = mapOf(DimensionValue("vip") to Policy(Capacity(100), RateLimitUnit.MINUTE)),
                ),
            ),
        )
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(InMemoryGlobalCounter(), metrics)
        evaluatorWith(state, metrics, withOverride).evaluate(Batch(listOf(req("vip"))))
        assertEquals(DimensionValue("vip"), metrics.policyReserves.single().override)
    }

    @Test
    fun `policy reserve metric is not emitted when detailedMetric is off`() = runTest {
        val quiet = PolicySnapshot(
            mapOf(
                Dimension("user_id") to DimensionPolicy(
                    default = Policy(Capacity(100), RateLimitUnit.MINUTE, detailedMetric = false),
                ),
            ),
        )
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(InMemoryGlobalCounter(), metrics)
        evaluatorWith(state, metrics, quiet).evaluate(Batch(listOf(req("uA"))))
        assertTrue(metrics.policyReserves.isEmpty())
    }

    @Test
    fun `pass-through does not emit a policy reserve metric`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        val state = stateWith(InMemoryGlobalCounter(), metrics)
        val batch = Batch(listOf(Request(Dimension("ip"), DimensionValue("1.2.3.4"), Hits(1), Priority.HIGH)))
        evaluatorWith(state, metrics).evaluate(batch)
        assertTrue(metrics.policyReserves.isEmpty())
    }
}
