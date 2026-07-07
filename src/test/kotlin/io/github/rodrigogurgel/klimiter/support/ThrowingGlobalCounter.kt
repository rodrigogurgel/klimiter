package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import kotlin.time.Duration

/** Contador global que falha em toda operação, para exercitar a degradação §7.5 do [BatchEvaluator]. */
class ThrowingGlobalCounter(private val error: () -> Throwable) : GlobalCounter {
    override suspend fun tryAcquire(
        key: String,
        capacity: Long,
        hits: Long,
        priority: Priority,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): AcquireResult = throw error()
}
