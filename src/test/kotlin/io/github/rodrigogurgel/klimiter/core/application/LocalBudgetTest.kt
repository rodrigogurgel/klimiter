package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.Policy
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import io.github.rodrigogurgel.klimiter.support.InMemoryGlobalCounter
import io.github.rodrigogurgel.klimiter.support.RecordingRateLimitMetrics
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LocalBudgetTest {
    private val policy = Policy(Capacity(100), RateLimitUnit.MINUTE)
    private val dim = Dimension("user_id")
    private val value = DimensionValue("u1")

    private fun budget() = LocalBudget(InMemoryGlobalCounter(), "klimiter")

    @Test
    fun `bucketFor reuses the same bucket within a window`() {
        val budget = budget()
        val a = budget.bucketFor(dim, value, policy, nowEpochSecond = 1_000)
        val b = budget.bucketFor(dim, value, policy, nowEpochSecond = 1_010) // mesma janela [960,1020)
        assertSame(a, b)
        assertEquals(1, budget.size())
    }

    @Test
    fun `reserve high admits and counts in the index`() = runTest {
        val budget = budget()
        val reservation = budget.reserve(dim, value, policy, hits = 1, priority = Priority.HIGH, nowMillis = 60_000)
        assertEquals(Status.ALLOWED, reservation.decision.status)
        assertEquals(1, budget.size())
    }

    @Test
    fun `reserve records a metric tagged by priority and status`() = runTest {
        val metrics = RecordingRateLimitMetrics()
        val budget = LocalBudget(InMemoryGlobalCounter(), "klimiter", metrics)
        budget.reserve(dim, value, policy, hits = 1, priority = Priority.HIGH, nowMillis = 60_000)
        assertEquals(listOf(Priority.HIGH to Status.ALLOWED), metrics.reserves)
    }

    @Test
    fun `inspect denies when hits exceed capacity`() {
        val decision = budget().inspect(dim, value, policy, hits = 101, priority = Priority.HIGH, nowMillis = 60_000)
        assertEquals(Status.DENIED, decision.status)
    }

    @Test
    fun `inspect allows a zero-hit request`() {
        val decision = budget().inspect(dim, value, policy, hits = 0, priority = Priority.HIGH, nowMillis = 60_000)
        assertEquals(Status.ALLOWED, decision.status)
    }

    @Test
    fun `evictExpired removes buckets from past windows`() {
        val budget = budget()
        budget.bucketFor(dim, value, policy, nowEpochSecond = 0) // janela [0,60)
        val removed = budget.evictExpired(nowMillis = 120_000)
        assertEquals(1, removed)
        assertEquals(0, budget.size())
    }
}
