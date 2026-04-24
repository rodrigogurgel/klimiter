package io.klimiter.core.api.config

data class RateLimitDomain(
    val id: String,
    val descriptors: List<RateLimitDescriptor>
) {
    init {
        require(id.isNotBlank()) {
            "domain id não pode ser vazio"
        }
        require(descriptors.isNotEmpty()) {
            "domain precisa ter ao menos um descriptor"
        }
    }

    fun findByPath(vararg path: DescriptorPath): RateLimitDescriptor? {
        if (path.isEmpty()) return null

        val head = path.first()
        val tail = path.drop(1).toTypedArray()
        val match = descriptors.bestMatch(head) ?: return null

        return if (tail.isEmpty()) match else match.findByPath(*tail)
    }
}