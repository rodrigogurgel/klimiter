package io.klimiter.core.api.config

data class RateLimitDescriptor(
    val key: String,
    val value: String? = null,
    val rule: RateLimitRule? = null,
    @Deprecated("not implemented yet")
    val shadowMode: Boolean = false,
    @Deprecated("not implemented yet")
    val detailedMetric: Boolean = false,
    @Deprecated("not implemented yet")
    val valueToMetric: Boolean = false,
    @Deprecated("not implemented yet")
    val shareThreshold: Boolean = false,
    val children: List<RateLimitDescriptor> = emptyList()
) {
    init {
        require(key.isNotBlank()) {
            "key must not be blank"
        }
        @Suppress("DEPRECATION")
        require(!shareThreshold || value?.endsWith("*") == true) {
            "shareThreshold can only be used with wildcard values (*)"
        }
    }

    val isWhitelisted: Boolean get() = rule == null && children.isEmpty()

    fun findByPath(vararg path: DescriptorPath): RateLimitDescriptor? {
        if (path.isEmpty()) return this

        val head = path.first()
        val tail = path.drop(1).toTypedArray()
        val match = children.bestMatch(head) ?: return null

        return if (tail.isEmpty()) match else match.findByPath(*tail)
    }
}
