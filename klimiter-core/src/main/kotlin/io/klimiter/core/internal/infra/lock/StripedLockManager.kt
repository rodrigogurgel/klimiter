package io.klimiter.core.internal.infra.lock

import io.klimiter.core.internal.port.LockManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class StripedLockManager(stripesCount: Int = DEFAULT_STRIPES_COUNT) : LockManager {
    private val stripes: Array<Mutex>

    init {
        require(stripesCount > 0) { "stripesCount must be > 0" }
        stripes = Array(stripesCount) { Mutex() }
    }

    override suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        require(key.isNotBlank()) { "key must not be blank" }
        return stripes[stripeIndex(key)].withLock { action() }
    }

    private fun stripeIndex(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % stripes.size

    private companion object {
        const val DEFAULT_STRIPES_COUNT = 1024
    }
}
