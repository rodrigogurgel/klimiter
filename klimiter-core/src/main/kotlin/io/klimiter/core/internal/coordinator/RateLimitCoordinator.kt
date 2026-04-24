package io.klimiter.core.internal.coordinator

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.internal.participant.RateLimitParticipant
import io.klimiter.core.internal.rls.RateLimitOverallCodeResolver
import io.klimiter.core.internal.tcc.TccCoordinator
import io.klimiter.core.internal.tcc.TccResultHandler

internal class RateLimitCoordinator(
    participants: List<RateLimitParticipant>,
) {
    private val delegate = TccCoordinator(
        participants = participants,
        resultHandler = RateLimitTccResultHandler(),
    )

    suspend fun execute(): RateLimitResponse =
        delegate.execute()
}

private class RateLimitTccResultHandler : TccResultHandler<RateLimitStatus, RateLimitResponse> {
    override fun isTrySuccess(result: RateLimitStatus): Boolean {
        return result.code == RateLimitCode.OK
    }

    override fun onTryFailure(results: List<RateLimitStatus>): RateLimitResponse {
        return RateLimitResponse(
            overallCode = RateLimitOverallCodeResolver.resolve(results),
            statuses = results,
        )
    }

    override fun onConfirmSuccess(results: List<RateLimitStatus>): RateLimitResponse {
        return RateLimitResponse(
            overallCode = RateLimitOverallCodeResolver.resolve(results),
            statuses = results,
        )
    }

    override fun onConfirmFailure(
        tryResults: List<RateLimitStatus>,
        cause: Throwable,
    ): RateLimitResponse {
        return RateLimitResponse(
            overallCode = RateLimitCode.UNKNOWN,
            statuses = tryResults,
        )
    }
}