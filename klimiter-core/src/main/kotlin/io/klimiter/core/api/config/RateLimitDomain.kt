package io.klimiter.core.api.config

data class RateLimitDomain(val id: String, val descriptors: List<RateLimitDescriptor>) {
    init {
        require(id.isNotBlank()) {
            "domain id must not be blank"
        }
        require(descriptors.isNotEmpty()) {
            "domain must have at least one descriptor"
        }
    }

    fun findByPath(vararg path: DescriptorPath): RateLimitDescriptor? {
        if (path.isEmpty()) return null
        val head = path.first()
        val tail = path.drop(1).toTypedArray()
        return descriptors.bestMatch(head)
            ?.let { if (tail.isEmpty()) it else it.findByPath(*tail) }
    }
}
