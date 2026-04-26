package io.klimiter.core.internal.operation

import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.spi.RateLimitOperation
import io.klimiter.core.spi.TimeProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class InMemoryRateLimitOperation(
    private val key: String,
    private val limit: RateLimit,
    private val hitsAddend: Long,
    private val windowSeconds: Long,
    private val counter: AtomicLong,
    private val timeProvider: TimeProvider,
) : RateLimitOperation {

    private var reserved: Long = 0L

    override suspend fun execute(): RateLimitStatus {
        val max = limit.requestsPerUnit.toLong()
        while (true) {
            val current = counter.get()
            val projected = current + hitsAddend
            if (projected > max) {
                logger.trace("Deny key={} current={} max={}", key, current, max)
                return buildStatus(RateLimitCode.OVER_LIMIT, current, max)
            }
            if (counter.compareAndSet(current, projected)) {
                reserved = hitsAddend
                logger.trace("Allow key={} count={} max={}", key, projected, max)
                return buildStatus(RateLimitCode.OK, projected, max)
            }
        }
    }

    override suspend fun rollback() {
        if (reserved == 0L) return
        counter.addAndGet(-reserved)
        logger.trace("Rollback key={} amount={}", key, reserved)
        reserved = 0L
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
        val nowEpochSecond = timeProvider.now().epochSecond
        val windowStart = (nowEpochSecond / windowSeconds) * windowSeconds
        return (windowStart + windowSeconds - nowEpochSecond).seconds
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(InMemoryRateLimitOperation::class.java)
    }
}
