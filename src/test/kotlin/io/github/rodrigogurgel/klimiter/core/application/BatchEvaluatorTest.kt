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

class BatchEvaluatorTest {
    private val policy = Policy(Capacity(100), RateLimitUnit.MINUTE)
    private val snapshot = PolicySnapshot(mapOf(Dimension("user_id") to DimensionPolicy(default = policy)))
    private val now = 60_000L // epochSecond 60 → janela MINUTE [60,120)

    private class FixedPolicies(private val snapshot: PolicySnapshot) : PolicyRepository {
        override fun current(): PolicySnapshot = snapshot
    }

    private fun evaluatorWith(budget: LocalBudget, metrics: RateLimitMetrics = RateLimitMetrics.NOOP): BatchEvaluator =
        BatchEvaluator(budget, FixedPolicies(snapshot), Clock { now }, metrics)

    private fun evaluatorWith(
        snapshot: PolicySnapshot,
        budget: LocalBudget,
        metrics: RateLimitMetrics,
    ): BatchEvaluator = BatchEvaluator(budget, FixedPolicies(snapshot), Clock { now }, metrics)

    private fun budget(counter: GlobalCounter) = LocalBudget(counter, "klimiter")

    private fun req(value: String, hits: Long = 1, priority: Priority = Priority.HIGH) =
        Request(Dimension("user_id"), DimensionValue(value), Hits(hits), priority)

    @Test
    fun `empty batch is allowed`() = runTest {
        val result = evaluatorWith(budget(InMemoryGlobalCounter())).evaluate(Batch(emptyList()))
        assertEquals(Status.ALLOWED, result.overall)
        assertTrue(result.decisions.isEmpty())
    }

    @Test
    fun `request without policy is pass-through`() = runTest {
        val batch = Batch(listOf(Request(Dimension("ip"), DimensionValue("1.2.3.4"), Hits(1), Priority.HIGH)))
        val result = evaluatorWith(budget(InMemoryGlobalCounter())).evaluate(batch)
        assertEquals(Status.ALLOWED, result.overall)
        assertEquals(Status.ALLOWED, result.decisions.single().status)
    }

    @Test
    fun `all matched requests are admitted`() = runTest {
        val result = evaluatorWith(budget(InMemoryGlobalCounter())).evaluate(Batch(listOf(req("uA"), req("uB"))))
        assertEquals(Status.ALLOWED, result.overall)
        assertEquals(2, result.decisions.size)
    }

    @Test
    fun `short-circuits when an inspection denies without any round-trip`() = runTest {
        val counter = InMemoryGlobalCounter()
        val metrics = RecordingRateLimitMetrics()
        val batch = Batch(listOf(req("uA"), req("uB", hits = 101))) // uB: hits > capacidade
        val result = evaluatorWith(budget(counter), metrics).evaluate(batch)
        assertEquals(Status.DENIED, result.overall)
        assertEquals(0, counter.leasedOf("klimiter:user_id:uA:60")) // ninguém reservou (§7.2)
        assertEquals(1, metrics.shortCircuits)
    }

    @Test
    fun `a denied batch refunds the admitted reservations`() = runTest {
        val counter = InMemoryGlobalCounter()
        counter.lease("klimiter:user_id:uB:60", 100, 100, 60.seconds) // uB esgotado por outro nó
        val budget = budget(counter)
        val metrics = RecordingRateLimitMetrics()
        val result = evaluatorWith(budget, metrics).evaluate(Batch(listOf(req("uA"), req("uB"))))
        assertEquals(Status.DENIED, result.overall)
        // uA passou na inspeção e reservou 1; o lote negou → refund devolve ao crédito local (§7.4).
        assertEquals(1, budget.bucketFor(Dimension("user_id"), DimensionValue("uA"), policy, 60).localCredit)
        assertEquals(1, metrics.refunds)
    }

    @Test
    fun `backend failure degrades the item to UNKNOWN`() = runTest {
        val budget = budget(ThrowingGlobalCounter { IllegalStateException("redis down") })
        val result = evaluatorWith(budget).evaluate(Batch(listOf(req("uA"))))
        assertEquals(Status.UNKNOWN, result.overall)
        assertEquals(Status.UNKNOWN, result.decisions.single().status)
    }

    @Test
    fun `cancellation is propagated not masked as UNKNOWN`() = runTest {
        val budget = budget(ThrowingGlobalCounter { CancellationException("client gone") })
        assertFailsWith<CancellationException> {
            evaluatorWith(budget).evaluate(Batch(listOf(req("uA"))))
        }
    }

    @Test
    fun `policy reserve metric carries dimension with null override for the default`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        evaluatorWith(budget(InMemoryGlobalCounter()), metrics).evaluate(Batch(listOf(req("uA"))))
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
        evaluatorWith(withOverride, budget(InMemoryGlobalCounter()), metrics).evaluate(Batch(listOf(req("vip"))))
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
        evaluatorWith(quiet, budget(InMemoryGlobalCounter()), metrics).evaluate(Batch(listOf(req("uA"))))
        assertTrue(metrics.policyReserves.isEmpty())
    }

    @Test
    fun `pass-through does not emit a policy reserve metric`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        val batch = Batch(listOf(Request(Dimension("ip"), DimensionValue("1.2.3.4"), Hits(1), Priority.HIGH)))
        evaluatorWith(budget(InMemoryGlobalCounter()), metrics).evaluate(batch)
        assertTrue(metrics.policyReserves.isEmpty())
    }
}
