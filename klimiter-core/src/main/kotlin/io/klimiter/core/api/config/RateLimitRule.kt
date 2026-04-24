package io.klimiter.core.api.config

import io.klimiter.core.api.common.RateLimitTimeUnit

data class RateLimitRule(
    val unit: RateLimitTimeUnit,
    val requestsPerUnit: Int,
    val name: String? = null,
    val unlimited: Boolean = false,
    val replaces: List<String> = emptyList()
) {
    init {
        require(requestsPerUnit >= 0) {
            "requestsPerUnit não pode ser negativo"
        }
        require(!unlimited || requestsPerUnit == 0) {
            "Se unlimited=true, requestsPerUnit deve ser 0"
        }
    }
}