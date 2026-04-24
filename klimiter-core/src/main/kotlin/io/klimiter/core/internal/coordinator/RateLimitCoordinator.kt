package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.internal.operation.RateLimitOperation
import io.klimiter.core.internal.rls.RateLimitOverallCodeResolver

/**
 * Executes a list of [RateLimitOperation]s with all-or-nothing semantics: if any operation
 * returns non-OK, [RateLimitOperation.rollback] is invoked on every operation that previously
 * reserved. Stateless (object) to avoid per-request allocation.
 */
internal object RateLimitCoordinator {

    suspend fun execute(operations: List<RateLimitOperation>): RateLimitResponse {
        if (operations.isEmpty()) {
            return RateLimitResponse(overallCode = RateLimitCode.OK, statuses = emptyList())
        }

        // Fast-path: single operation — hot path, no intermediate list allocation.
        if (operations.size == 1) {
            val status = operations[0].execute()
            return RateLimitResponse(
                overallCode = status.code,
                statuses = listOf(status),
            )
        }

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
            runCatching { operations[j].rollback() }
        }
    }
}
