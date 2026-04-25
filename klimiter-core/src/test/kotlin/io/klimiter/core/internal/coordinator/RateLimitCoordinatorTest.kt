package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.api.spi.RateLimitOperation
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
    fun `middle failure rolls back only the prefix`() = runTest {
        val first = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val second = RecordingOperation(status = statusOf(RateLimitCode.OK))
        val third = RecordingOperation(status = statusOf(RateLimitCode.OVER_LIMIT))
        val fourth = RecordingOperation(status = statusOf(RateLimitCode.OK))

        val response = RateLimitCoordinator.execute(listOf(first, second, third, fourth))

        assertEquals(RateLimitCode.OVER_LIMIT, response.overallCode)
        assertEquals(3, response.statuses.size, "only executed ops contribute statuses")
        assertTrue(first.rolledBack)
        assertTrue(second.rolledBack)
        assertFalse(third.rolledBack, "failing op is not rolled back — it never reserved")
        assertFalse(fourth.executed, "ops after failure are not executed")
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
