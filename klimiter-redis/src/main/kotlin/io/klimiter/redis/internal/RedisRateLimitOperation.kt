package io.klimiter.redis.internal

import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.core.spi.RateLimitOperation
import io.klimiter.core.spi.TimeProvider
import io.klimiter.redis.internal.command.RedisCommandExecutor
import io.klimiter.redis.internal.lease.LeasedBucket
import io.klimiter.redis.internal.script.LeaseScripts
import io.klimiter.redis.internal.script.LuaScript
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Leased Redis rate-limit operation with synchronous renewal.
 *
 * Hot path is a single CAS against the bucket's local remaining budget. On exhaustion the
 * caller serializes behind a per-bucket mutex, runs at most one Redis renewal, and retries —
 * so concurrent requests on the same key coalesce into one round-trip rather than storming
 * Redis, and no request is silently dropped while budget exists globally.
 *
 * Rollback returns the reserved hits to the local pool; it never contacts Redis, so leased
 * units burned by a failed batch leak at most one lease-slice — the documented cost of the
 * pattern.
 */
internal class RedisRateLimitOperation(
    private val key: String,
    private val limit: RateLimit,
    private val hitsAddend: Long,
    private val windowSeconds: Long,
    private val bucket: LeasedBucket,
    private val executor: RedisCommandExecutor,
    private val timeProvider: TimeProvider,
    private val leasePercentage: Int,
) : RateLimitOperation {

    private var reserved: Long = 0L

    override suspend fun execute(): RateLimitStatus {
        val max = limit.requestsPerUnit.toLong()
        return when {
            hitsAddend <= 0L -> {
                if (hitsAddend < 0L) bucket.localRemaining.addAndGet(-hitsAddend)
                logger.trace("Allow key={} hits={} (credit/noop)", key, hitsAddend)
                status(RateLimitCode.OK)
            }

            hitsAddend > max -> {
                logger.trace("Deny key={} hits={} exceeds max={}", key, hitsAddend, max)
                status(RateLimitCode.OVER_LIMIT)
            }

            tryReserve() -> {
                reserved = hitsAddend
                logger.trace("Allow key={} hits={} from local budget", key, hitsAddend)
                status(RateLimitCode.OK)
            }

            else -> bucket.mutex.withLock {
                // Another caller may have renewed while we were waiting for the lock.
                if (tryReserve()) {
                    reserved = hitsAddend
                    logger.trace(
                        "Allow key={} hits={} from local budget (post-lock)",
                        key,
                        hitsAddend,
                    )
                    return@withLock status(RateLimitCode.OK)
                }
                val (granted, remaining) = acquireLease(max)

                if (granted > 0L) {
                    bucket.localRemaining.addAndGet(granted)
                    bucket.distributedRemaining.set(remaining)
                }
                if (tryReserve()) {
                    reserved = hitsAddend
                    logger.trace(
                        "Allow key={} hits={} after lease renewal granted={}",
                        key,
                        hitsAddend,
                        granted,
                    )
                    status(RateLimitCode.OK)
                } else {
                    logger.trace(
                        "Deny key={} hits={} globally exhausted distributedRemaining={}",
                        key,
                        hitsAddend,
                        remaining,
                    )
                    status(RateLimitCode.OVER_LIMIT)
                }
            }
        }
    }

    override suspend fun rollback() {
        if (reserved == 0L) return
        bucket.localRemaining.addAndGet(reserved)
        logger.trace("Rollback key={} amount={}", key, reserved)
        reserved = 0L
    }

    private fun tryReserve(): Boolean {
        val counter = bucket.localRemaining
        while (true) {
            val current = counter.get()
            if (current < hitsAddend) return false
            if (counter.compareAndSet(current, current - hitsAddend)) return true
        }
    }

    private suspend fun acquireLease(max: Long): AcquireLeaseResult {
        val leaseSize = leaseSize(max)
        val result = try {
            LEASE_ACQUIRE_SCRIPT.execute<List<Long>>(
                executor = executor,
                outputType = ScriptOutputType.MULTI,
                keys = arrayOf(key),
                args = arrayOf(
                    max.toString(),
                    leaseSize.toString(),
                    (windowSeconds + DEFAULT_GRACE_PERIOD.inWholeSeconds).toString(),
                ),
            )
        } catch (e: RedisException) {
            logger.error("Lease acquisition failed key={} leaseSize={} max={}", key, leaseSize, max, e)
            throw e
        }
        val granted = result.getOrNull(0) ?: 0L
        val remaining = result.getOrNull(1) ?: 0L

        logger.debug(
            "Lease key={} requested={} granted={} distributedRemaining={}",
            key,
            leaseSize,
            granted,
            remaining,
        )

        return AcquireLeaseResult(
            granted = granted,
            remaining = remaining,
        )
    }

    private data class AcquireLeaseResult(val granted: Long, val remaining: Long)

    private fun leaseSize(max: Long): Long {
        val proportional = ceil(max.toDouble() * leasePercentage / LEASE_PERCENT_BASE).toLong()
        return maxOf(hitsAddend, proportional)
    }

    private fun status(code: RateLimitCode): RateLimitStatus {
        val remaining = if (code == RateLimitCode.OK) {
            val distributedRemaining = bucket.distributedRemaining.get().coerceAtLeast(0).toInt()
            val localRemaining = bucket.localRemaining.get().coerceAtLeast(0L).toInt()

            localRemaining + distributedRemaining
        } else {
            0
        }
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
        private const val LEASE_PERCENT_BASE = 100
        private val logger = LoggerFactory.getLogger(RedisRateLimitOperation::class.java)
        private val LEASE_ACQUIRE_SCRIPT = LuaScript(LeaseScripts.LEASE_ACQUIRE)
        private val DEFAULT_GRACE_PERIOD: Duration = 10.seconds
    }
}
