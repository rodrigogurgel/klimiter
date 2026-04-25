package io.klimiter.core.api.rls

data class RateLimitResponse(val overallCode: RateLimitCode, val statuses: List<RateLimitStatus> = emptyList()) {
    fun isOverLimit(): Boolean = overallCode == RateLimitCode.OVER_LIMIT
    fun isOk(): Boolean = overallCode == RateLimitCode.OK
}
