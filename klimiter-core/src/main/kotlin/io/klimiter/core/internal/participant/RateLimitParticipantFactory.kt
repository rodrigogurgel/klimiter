package io.klimiter.core.internal.participant

import io.klimiter.core.api.rls.RateLimitRequest

internal interface RateLimitParticipantFactory {
    fun create(request: RateLimitRequest): List<RateLimitParticipant>
}