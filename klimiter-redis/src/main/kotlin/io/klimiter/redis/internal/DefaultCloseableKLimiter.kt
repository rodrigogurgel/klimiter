package io.klimiter.redis.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.redis.api.CloseableKLimiter

internal class DefaultCloseableKLimiter(
    private val delegate: KLimiter,
    private val onClose: () -> Unit,
) : CloseableKLimiter {

    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse =
        delegate.shouldRateLimit(request)

    override fun close() = onClose()
}
