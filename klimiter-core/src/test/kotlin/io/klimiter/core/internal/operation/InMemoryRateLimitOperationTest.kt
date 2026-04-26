package io.klimiter.core.internal.operation

import io.klimiter.core.FixedTimeProvider
import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryRateLimitOperationTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-04-24T12:00:00Z"))

    private fun makeOp(limit: Int, hitsAddend: Long = 1L, counter: AtomicLong = AtomicLong(0L)) =
        InMemoryRateLimitOperation(
            key = "test-key",
            limit = RateLimit(requestsPerUnit = limit, unit = RateLimitTimeUnit.SECOND),
            hitsAddend = hitsAddend,
            windowSeconds = 1L,
            counter = counter,
            timeProvider = clock,
        )

    @Test
    fun `first request within limit returns OK`() = runTest {
        val status = makeOp(limit = 5).execute()
        assertEquals(RateLimitCode.OK, status.code)
        assertEquals(4, status.limitRemaining)
    }

    @Test
    fun `request that exhausts limit returns OVER_LIMIT`() = runTest {
        val status = makeOp(limit = 5, counter = AtomicLong(5L)).execute()
        assertEquals(RateLimitCode.OVER_LIMIT, status.code)
        assertEquals(0, status.limitRemaining)
    }

    @Test
    fun `limitRemaining is correct after partial consumption`() = runTest {
        val status = makeOp(limit = 10, hitsAddend = 2L, counter = AtomicLong(3L)).execute()
        assertEquals(RateLimitCode.OK, status.code)
        assertEquals(5, status.limitRemaining)
    }

    @Test
    fun `rollback decrements counter`() = runTest {
        val counter = AtomicLong(0L)
        val op = makeOp(limit = 5, counter = counter)
        op.execute()
        assertEquals(1L, counter.get())
        op.rollback()
        assertEquals(0L, counter.get())
    }

    @Test
    fun `rollback is idempotent`() = runTest {
        val counter = AtomicLong(0L)
        val op = makeOp(limit = 5, counter = counter)
        op.execute()
        op.rollback()
        op.rollback()
        assertEquals(0L, counter.get())
    }

    @Test
    fun `rollback without execute is no-op`() = runTest {
        val counter = AtomicLong(0L)
        makeOp(limit = 5, counter = counter).rollback()
        assertEquals(0L, counter.get())
    }

    @Test
    fun `negative hitsAddend decrements the counter`() = runTest {
        val counter = AtomicLong(5L)
        val status = makeOp(limit = 10, hitsAddend = -2L, counter = counter).execute()
        assertEquals(RateLimitCode.OK, status.code)
        assertEquals(3L, counter.get())
    }

    @Test
    fun `durationUntilReset is positive and within one window`() = runTest {
        val status = makeOp(limit = 5).execute()
        val resetMs = status.durationUntilReset!!.inWholeMilliseconds
        assertTrue(resetMs > 0, "durationUntilReset must be positive, was $resetMs ms")
        assertTrue(resetMs <= 1_000, "durationUntilReset must not exceed the window, was $resetMs ms")
    }

    @Test
    fun `concurrent executions never exceed limit`() = runTest {
        val counter = AtomicLong(0L)
        val limit = 10
        val results = AtomicLong(0L)
        val jobs = (1..30).map {
            launch {
                val status = makeOp(limit = limit, counter = counter).execute()
                if (status.code == RateLimitCode.OK) results.incrementAndGet()
            }
        }
        jobs.forEach { it.join() }
        assertTrue(results.get() <= limit, "Allowed ${results.get()} requests, limit was $limit")
    }
}
