package io.klimiter.core.api.rls

import io.klimiter.core.api.common.RateLimitTimeUnit

data class RateLimitOverride(val requestsPerUnit: Int, val unit: RateLimitTimeUnit)
