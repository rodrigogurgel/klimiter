package io.klimiter.core.internal.operation

import io.klimiter.core.api.rls.RateLimitRequest

internal interface RateLimitOperationFactory {
    fun create(request: RateLimitRequest): List<RateLimitOperation>
}
