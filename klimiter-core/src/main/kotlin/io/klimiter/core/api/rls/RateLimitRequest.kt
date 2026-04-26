package io.klimiter.core.api.rls

data class RateLimitRequest(
    val domain: String,
    val descriptors: List<RateLimitRequestDescriptor>,
    val hitsAddend: Int = 1,
) {
    init {
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(descriptors.isNotEmpty()) { "descriptors must not be empty" }
    }
}
