package io.klimiter.core.api.config

data class RateLimitDescriptor(
    val key: String,
    val value: String? = null,
    val rule: RateLimitRule? = null,
    val children: List<RateLimitDescriptor> = emptyList(),
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
    }

    val isWhitelisted: Boolean get() = rule == null && children.isEmpty()

    fun findByPath(vararg path: DescriptorPath): RateLimitDescriptor? {
        if (path.isEmpty()) return this
        val head = path.first()
        val tail = path.drop(1).toTypedArray()
        return children.bestMatch(head)
            ?.let { if (tail.isEmpty()) it else it.findByPath(*tail) }
    }
}
