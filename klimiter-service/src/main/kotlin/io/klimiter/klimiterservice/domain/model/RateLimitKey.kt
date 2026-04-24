package io.klimiter.klimiterservice.domain.model

data class RateLimitKey(
    val key: String,
    val value: String,
    val cost: Long,
) {
    init {
        require(key.isNotBlank()) { "key não pode ser vazio" }
        require(cost >= 0L) { "cost não pode ser negativo" }
    }
}
