package io.klimiter.core.api.rls

import io.klimiter.core.api.common.RateLimitTimeUnit

data class RateLimit(
    val requestsPerUnit: Int,
    val unit: RateLimitTimeUnit,
    val name: String? = null
)