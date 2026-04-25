package io.klimiter.core.api.config

data class RateLimitRule(
    val unit: RateLimitTimeUnit,
    val requestsPerUnit: Int,
    val name: String? = null,
    val unlimited: Boolean = false,
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
