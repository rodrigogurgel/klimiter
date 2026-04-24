package io.klimiter.core.internal.port

internal interface LockManager {
    suspend fun <T> withLock(key: String, action: suspend () -> T): T
}
