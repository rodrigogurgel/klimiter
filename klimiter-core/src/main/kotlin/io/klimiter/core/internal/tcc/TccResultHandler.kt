package io.klimiter.core.internal.tcc

internal interface TccResultHandler<R, O> {
    fun isTrySuccess(result: R): Boolean
    fun onTryFailure(results: List<R>): O
    fun onConfirmSuccess(results: List<R>): O
    fun onConfirmFailure(tryResults: List<R>, cause: Throwable): O
}