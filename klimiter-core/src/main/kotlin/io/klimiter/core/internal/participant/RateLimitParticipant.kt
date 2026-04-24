package io.klimiter.core.internal.participant

import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.internal.tcc.TccParticipant

internal interface RateLimitParticipant : TccParticipant<RateLimitStatus>