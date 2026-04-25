package io.klimiter.klimiterservice.application

import io.klimiter.klimiterservice.domain.model.RateLimitCheckResult
import io.klimiter.klimiterservice.domain.model.RateLimitDecision
import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus
import io.klimiter.klimiterservice.domain.port.input.CheckRateLimitUseCase
import io.klimiter.klimiterservice.domain.port.output.RateLimitEnforcerPort
import org.springframework.stereotype.Service

@Service
class CheckRateLimitService(private val enforcer: RateLimitEnforcerPort) : CheckRateLimitUseCase {

    override suspend fun check(keys: List<RateLimitKey>): RateLimitCheckResult {
        if (keys.isEmpty()) return RateLimitCheckResult.empty()

        val statuses = keys.map { enforcer.enforce(it) }
        return RateLimitCheckResult(
            overall = overallDecision(statuses),
            statuses = statuses,
        )
    }

    private fun overallDecision(statuses: List<RateLimitKeyStatus>): RateLimitDecision = when {
        statuses.any { it.decision == RateLimitDecision.OVER_LIMIT } -> RateLimitDecision.OVER_LIMIT
        statuses.any { it.decision == RateLimitDecision.ERROR } -> RateLimitDecision.ERROR
        else -> RateLimitDecision.OK
    }
}
