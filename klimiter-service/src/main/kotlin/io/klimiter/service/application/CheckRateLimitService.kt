package io.klimiter.service.application

import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.service.domain.port.input.CheckRateLimitUseCase
import io.klimiter.service.domain.port.output.RateLimitEnforcerPort
import org.springframework.stereotype.Service

@Service
class CheckRateLimitService(private val enforcer: RateLimitEnforcerPort) : CheckRateLimitUseCase {

    override suspend fun check(request: RateLimitRequest): RateLimitResponse = enforcer.enforce(request)
}
