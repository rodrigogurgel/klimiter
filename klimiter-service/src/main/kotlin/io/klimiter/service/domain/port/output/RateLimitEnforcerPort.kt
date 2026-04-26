package io.klimiter.service.domain.port.output

import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse

interface RateLimitEnforcerPort {
    suspend fun enforce(request: RateLimitRequest): RateLimitResponse
}
