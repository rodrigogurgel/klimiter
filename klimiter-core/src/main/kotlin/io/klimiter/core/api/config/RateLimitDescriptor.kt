package io.klimiter.core.api.config

data class RateLimitDescriptor(
    val key: String,
    val value: String? = null,
    val rule: RateLimitRule? = null,
    val shadowMode: Boolean = false,
    val detailedMetric: Boolean = false,
    val valueToMetric: Boolean = false,
    val shareThreshold: Boolean = false,
    val children: List<RateLimitDescriptor> = emptyList()
) {
    init {
        require(key.isNotBlank()) {
            "key não pode ser vazio"
        }
        require(!shareThreshold || value?.endsWith("*") == true) {
            "shareThreshold só pode ser usado com valores wildcard (*)"
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