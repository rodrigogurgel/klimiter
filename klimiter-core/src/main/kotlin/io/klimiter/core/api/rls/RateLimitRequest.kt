package io.klimiter.core.api.rls

data class RateLimitRequest(
    val domain: String,
    val descriptors: List<RateLimitDescriptor>,
    val hitsAddend: Int = 1
) {
    init {
        require(domain.isNotBlank()) { "domain não pode ser vazio" }
        require(descriptors.isNotEmpty()) { "descriptors não pode ser vazio" }
    }
}