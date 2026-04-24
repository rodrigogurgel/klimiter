package io.klimiter.core.api.config

import io.klimiter.core.api.common.RateLimitTimeUnit

data class RateLimitRule(
    val unit: RateLimitTimeUnit,
    val requestsPerUnit: Int,
    val name: String? = null,
    val unlimited: Boolean = false,
    @Deprecated("not implemented yet")
    val replaces: List<String> = emptyList()
) {
    init {
        require(requestsPerUnit >= 0) {
            "requestsPerUnit must not be negative"
        }
        require(!unlimited || requestsPerUnit == 0) {
            "if unlimited=true, requestsPerUnit must be 0"
        }
    }
}
