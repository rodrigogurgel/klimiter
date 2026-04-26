package io.klimiter.core.api

import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse

interface KLimiter {
    suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse
}
