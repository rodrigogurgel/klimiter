package io.klimiter.klimiterservice.domain.model

data class RateLimitCheckResult(val overall: RateLimitDecision, val statuses: List<RateLimitKeyStatus>) {
    companion object {
        fun empty(): RateLimitCheckResult = RateLimitCheckResult(RateLimitDecision.OK, emptyList())
    }
}
