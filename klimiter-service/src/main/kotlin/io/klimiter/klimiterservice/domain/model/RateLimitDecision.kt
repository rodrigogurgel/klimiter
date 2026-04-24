package io.klimiter.klimiterservice.domain.model

enum class RateLimitDecision {
    OK,
    OVER_LIMIT,
    ERROR,
}
