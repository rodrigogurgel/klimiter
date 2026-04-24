package io.klimiter.core.internal.tcc

internal class TccCoordinator<R, O>(
    private val participants: List<TccParticipant<R>>,
    private val resultHandler: TccResultHandler<R, O>,
) {
    suspend fun execute(): O {
        val prepared = mutableListOf<TccParticipant<R>>()
        val tryResults = mutableListOf<R>()

        for (participant in participants) {
            val result = participant.tryPhase()
            tryResults += result

            if (resultHandler.isTrySuccess(result)) {
                prepared += participant
            }
        }

        val hasTryFailure = tryResults.any { !resultHandler.isTrySuccess(it) }

        if (hasTryFailure) {
            rollback(prepared)
            return resultHandler.onTryFailure(tryResults)
        }

        return try {
            val confirmResults = prepared.map { it.confirm() }
            resultHandler.onConfirmSuccess(confirmResults)
        } catch (cause: Throwable) {
            rollback(prepared)
            resultHandler.onConfirmFailure(tryResults, cause)
        }
    }

    private suspend fun rollback(prepared: List<TccParticipant<R>>) {
        prepared
            .asReversed()
            .forEach { participant ->
                runCatching { participant.cancel() }
            }
    }
}