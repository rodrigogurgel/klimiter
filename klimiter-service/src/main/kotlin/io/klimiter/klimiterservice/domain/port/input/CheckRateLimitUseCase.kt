package io.klimiter.klimiterservice.domain.port.input

import io.klimiter.klimiterservice.domain.model.RateLimitCheckResult
import io.klimiter.klimiterservice.domain.model.RateLimitKey

interface CheckRateLimitUseCase {
    suspend fun check(keys: List<RateLimitKey>): RateLimitCheckResult
}
