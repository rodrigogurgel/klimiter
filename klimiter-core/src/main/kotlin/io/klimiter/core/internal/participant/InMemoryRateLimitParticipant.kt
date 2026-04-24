package io.klimiter.core.internal.participant

import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.internal.port.LockManager
import io.klimiter.core.internal.port.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal class InMemoryRateLimitParticipant(
    private val key: String,
    private val limit: RateLimit,
    private val hitsAddend: Long,
    private val windowSeconds: Long,
    private val counter: AtomicLong,
    private val lockManager: LockManager,
    private val timeProvider: TimeProvider,
) : RateLimitParticipant {

    private var reservedAmount: Long = 0L

    override suspend fun tryPhase(): RateLimitStatus {
        val max = limit.requestsPerUnit.toLong()
        while (true) {
            val current = counter.get()
            val projected = current + hitsAddend
            if (projected > max) return buildStatus(RateLimitCode.OVER_LIMIT, current, max)
            if (counter.compareAndSet(current, projected)) {
                reservedAmount = hitsAddend
                return buildStatus(RateLimitCode.OK, projected, max)
            }
        }
    }

    override suspend fun confirm(): RateLimitStatus = lockManager.withLock(key) {
        buildStatus(RateLimitCode.OK, counter.get(), limit.requestsPerUnit.toLong())
    }

    override suspend fun cancel() {
        if (reservedAmount == 0L) return
        counter.addAndGet(-reservedAmount)
        reservedAmount = 0L
    }

    private fun buildStatus(code: RateLimitCode, current: Long, maxRequests: Long): RateLimitStatus {
        val remaining = (maxRequests - current).coerceAtLeast(0L).toInt()
        return RateLimitStatus(
            code = code,
            currentLimit = limit,
            limitRemaining = remaining,
            durationUntilReset = durationUntilReset(),
        )
    }

    private fun durationUntilReset(): Duration {
        val now = timeProvider.now()
        val windowStart = (now.epochSecond / windowSeconds) * windowSeconds
        val nextWindowStart = Instant.ofEpochSecond(windowStart + windowSeconds)
        return Duration.between(now, nextWindowStart)
    }
}
