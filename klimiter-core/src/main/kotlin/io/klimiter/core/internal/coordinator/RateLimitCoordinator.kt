package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.api.spi.RateLimitOperation
import org.slf4j.LoggerFactory

/**
 * Executes a list of [RateLimitOperation]s with all-or-nothing semantics: if any operation
 * returns non-OK, [RateLimitOperation.rollback] is invoked on every operation that previously
 * reserved. Stateless (object) to avoid per-request allocation.
 */
internal object RateLimitCoordinator {

    private val logger = LoggerFactory.getLogger(RateLimitCoordinator::class.java)

    suspend fun execute(operations: List<RateLimitOperation>): RateLimitResponse = when {
        operations.isEmpty() -> RateLimitResponse(overallCode = RateLimitCode.OK, statuses = emptyList())

        // Fast-path: single operation — hot path, no intermediate list allocation.
        operations.size == 1 -> {
            val status = operations[0].execute()
            RateLimitResponse(overallCode = status.code, statuses = listOf(status))
        }

        else -> executeMultiple(operations)
    }

    private suspend fun executeMultiple(operations: List<RateLimitOperation>): RateLimitResponse {
        val results = ArrayList<RateLimitStatus>(operations.size)
        for ((index, op) in operations.withIndex()) {
            val status = op.execute()
            results += status
            if (status.code != RateLimitCode.OK) {
                rollbackPrefix(operations, exclusiveEnd = index)
                return RateLimitResponse(
                    overallCode = RateLimitOverallCodeResolver.resolve(results),
                    statuses = results,
                )
            }
        }
        return RateLimitResponse(overallCode = RateLimitCode.OK, statuses = results)
    }

    private suspend fun rollbackPrefix(operations: List<RateLimitOperation>, exclusiveEnd: Int) {
        for (j in 0 until exclusiveEnd) {
            // Swallow per-operation failures so one bad rollback cannot leak reservations from
            // siblings — but log them; a silent failure here means a bucket just overcounted.
            runCatching { operations[j].rollback() }
                .onFailure { logger.warn("Rollback failed for operation index={}", j, it) }
        }
    }
}
