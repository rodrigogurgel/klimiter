package io.klimiter.core.api.rls

data class RateLimitDescriptor(
    val entries: List<RateLimitDescriptorEntry>,
    val limit: RateLimitOverride? = null,
    val hitsAddend: Long? = null,
    val isNegativeHits: Boolean = false,
) {
    init {
        require(entries.isNotEmpty()) { "entries must not be empty" }
    }
}
