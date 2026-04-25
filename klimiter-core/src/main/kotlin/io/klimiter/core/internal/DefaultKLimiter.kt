package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.core.internal.coordinator.RateLimitCoordinator

internal class DefaultKLimiter(private val operationFactory: RateLimitOperationFactory) : KLimiter {

    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        val operations = operationFactory.create(request)
        return RateLimitCoordinator.execute(operations)
    }
}
