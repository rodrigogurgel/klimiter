package io.klimiter.core.api.spi

import io.klimiter.core.api.rls.RateLimitStatus

/**
 * A single rate-limit quota reservation.
 *
 * [execute] atomically attempts the reservation and returns the status (OK or OVER_LIMIT).
 * [rollback] undoes a reservation made by a successful [execute]; it is idempotent — calling
 * without a prior reservation (or more than once) is a no-op.
 *
 * The in-process implementation relies on lock-free CAS; distributed implementations (e.g. Redis)
 * must achieve the same atomicity through the backing store (Lua scripts, CAS, transactions).
 */
interface RateLimitOperation {
    suspend fun execute(): RateLimitStatus
    suspend fun rollback()
}
