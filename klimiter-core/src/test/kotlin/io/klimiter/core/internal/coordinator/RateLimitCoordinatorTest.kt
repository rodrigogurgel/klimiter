package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.spi.RateLimitOperation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitCoordinatorTest {

    @Test
    fun `empty list returns OK with no statuses`() = runTest {
        val response = RateLimitCoordinator.execute(emptyList())
        assertEquals(RateLimitCode.OK, response.overallCode)
        assertTrue(response.statuses.isEmpty())
    }

    @Test
    fun `single OK operation returns OK`() = runTest {
        val op = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val response = RateLimitCoordinator.execute(listOf(op))
        assertEquals(RateLimitCode.OK, response.overallCode)
        assertEquals(1, response.statuses.size)
        assertFalse(op.rolledBack)
    }

    @Test
    fun `single OVER_LIMIT operation does not trigger rollback`() = runTest {
        val op = RecordingOperation(status = statusOf(RateLimitCode.OVER_LIMIT))
        val response = RateLimitCoordinator.execute(listOf(op))
        assertEquals(RateLimitCode.OVER_LIMIT, response.overallCode)
        assertFalse(op.rolledBack)
    }

    @Test
    fun `all operations are executed and OK ones are rolled back on failure`() = runTest {
        val first = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val second = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val third = RecordingOperation(status = statusOf(RateLimitCode.OVER_LIMIT))
        val fourth = RecordingOperation(status = statusOf(RateLimitCode.OK))

        val response = RateLimitCoordinator.execute(listOf(first, second, third, fourth))

        assertEquals(RateLimitCode.OVER_LIMIT, response.overallCode)
        assertEquals(4, response.statuses.size, "all ops contribute statuses")
        assertTrue(first.rolledBack)
        assertTrue(second.rolledBack)
        assertFalse(third.rolledBack, "failing op is not rolled back — it never reserved")
        assertTrue(fourth.executed, "ops after failure are still executed")
        assertTrue(fourth.rolledBack, "OK ops after failure are rolled back")
    }

    @Test
    fun `UNKNOWN status among OK statuses resolves to UNKNOWN overall`() = runTest {
        val op1 = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val op2 = RecordingOperation(status = statusOf(RateLimitCode.UNKNOWN))
        val response = RateLimitCoordinator.execute(listOf(op1, op2))
        assertEquals(RateLimitCode.UNKNOWN, response.overallCode)
        assertEquals(2, response.statuses.size)
        assertTrue(op1.rolledBack, "OK op is rolled back when overall is non-OK")
        assertFalse(op2.rolledBack, "UNKNOWN op is not rolled back — it never reserved")
    }

    @Test
    fun `rollback exception does not prevent sibling rollbacks`() = runTest {
        val first = RecordingOperation(status = statusOf(RateLimitCode.OK), throwOnRollback = true)
        val second = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val third = RecordingOperation(status = statusOf(RateLimitCode.OVER_LIMIT))

        val response = RateLimitCoordinator.execute(listOf(first, second, third))

        assertEquals(RateLimitCode.OVER_LIMIT, response.overallCode)
        assertTrue(first.rollbackAttempted)
        assertTrue(second.rolledBack, "second rollback still runs despite first throwing")
    }

    private fun statusOf(code: RateLimitCode) = RateLimitStatus(code = code)

    private class RecordingOperation(
        private val status: RateLimitStatus,
        private val throwOnRollback: Boolean = false,
    ) : RateLimitOperation {
        var executed: Boolean = false
            private set
        var rollbackAttempted: Boolean = false
            private set
        var rolledBack: Boolean = false
            private set

        override suspend fun execute(): RateLimitStatus {
            executed = true
            return status
        }

        override suspend fun rollback() {
            rollbackAttempted = true
            if (throwOnRollback) error("simulated rollback failure")
            rolledBack = true
        }
    }
}
