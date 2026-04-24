package io.klimiter.core.internal.tcc

internal interface TccParticipant<R> {
    suspend fun tryPhase(): R
    suspend fun confirm(): R
    suspend fun cancel()
}