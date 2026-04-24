package io.klimiter.core.internal

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.internal.coordinator.RateLimitCoordinator
import io.klimiter.core.internal.participant.RateLimitParticipantFactory

internal class DefaultKLimiter(
    private val participantFactory: RateLimitParticipantFactory,
) : KLimiter {

    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        val participants = participantFactory.create(request)
        val coordinator = RateLimitCoordinator(participants)
        return coordinator.execute()
    }
}