package io.klimiter.klimiterservice.domain.port.output

import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus

interface RateLimitEnforcerPort {
    suspend fun enforce(key: RateLimitKey): RateLimitKeyStatus
}
