package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import kotlin.time.Duration

/** Contador global que falha em toda operação, para exercitar a degradação §7.4 do [BatchEvaluator]. */
class ThrowingGlobalCounter(private val error: () -> Throwable) : GlobalCounter {
    override suspend fun lease(key: String, capacity: Long, requested: Long, ttl: Duration): LeaseResult = throw error()

    override suspend fun paceLease(
        key: String,
        capacity: Long,
        missing: Long,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): PaceResult = throw error()
}
