package io.klimiter.service.domain.port.input

import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse

interface CheckRateLimitUseCase {
    suspend fun check(request: RateLimitRequest): RateLimitResponse
}
