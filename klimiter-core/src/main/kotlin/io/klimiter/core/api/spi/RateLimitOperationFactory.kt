package io.klimiter.core.api.spi

import io.klimiter.core.api.rls.RateLimitRequest

/**
 * Builds the list of [RateLimitOperation]s that must succeed for a [RateLimitRequest] to be
 * allowed. Returning an empty list means the request is unconditionally allowed (no matching
 * rule, whitelisted, or unlimited).
 *
 * Implementations are expected to be thread-safe; a single factory instance is shared across
 * all concurrent requests handled by the KLimiter.
 */
interface RateLimitOperationFactory {
    fun create(request: RateLimitRequest): List<RateLimitOperation>
}
