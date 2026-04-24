package io.klimiter.core.api.config

fun List<RateLimitDescriptor>.bestMatch(
    path: DescriptorPath
): RateLimitDescriptor? =
    matchExact(path)
        ?: matchWildcard(path)
        ?: matchKeyOnly(path)

private fun List<RateLimitDescriptor>.matchExact(
    path: DescriptorPath
): RateLimitDescriptor? =
    path.value?.let { v ->
        firstOrNull { it.key == path.key && it.value == v }
    }

private fun List<RateLimitDescriptor>.matchWildcard(
    path: DescriptorPath
): RateLimitDescriptor? =
    path.value?.let { v ->
        filter { it.key == path.key && it.value?.endsWith("*") == true }
            .maxByOrNull { it.value!!.length }
            ?.takeIf { v.startsWith(it.value!!.dropLast(1)) }
    }

private fun List<RateLimitDescriptor>.matchKeyOnly(
    path: DescriptorPath
): RateLimitDescriptor? =
    firstOrNull { it.key == path.key && it.value == null }