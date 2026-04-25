package io.klimiter.klimiterservice.domain.model

data class RateLimitKey(val key: String, val value: String, val cost: Long) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
        require(cost >= 0L) { "cost must not be negative" }
    }
}
