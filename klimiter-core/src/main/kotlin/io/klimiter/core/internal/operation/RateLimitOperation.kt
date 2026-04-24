package io.klimiter.core.internal.operation

import io.klimiter.core.api.rls.RateLimitStatus

/**
 * A single rate-limit quota reservation.
 *
 * [execute] atomically attempts the reservation and returns the status (OK or OVER_LIMIT).
 * [rollback] undoes a reservation made by a successful [execute]; it is idempotent — calling
 * without a prior reservation (or more than once) is a no-op.
 *
 * Implementations must not perform blocking I/O on the hot path; the in-process rate limiter
 * relies on lock-free CAS for correctness.
 */
internal interface RateLimitOperation {
    suspend fun execute(): RateLimitStatus
    suspend fun rollback()
}
