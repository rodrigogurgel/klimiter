package io.klimiter.redis.internal.lease

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-key local state for the leased Redis limiter. Holds units available to serve from this
 * node without hitting Redis, plus a mutex that coalesces concurrent renewals into a single
 * round-trip.
 *
 * Lifecycle is managed by [LeasedBucketStore]; both fields are discarded together when the
 * bucket is evicted.
 */
internal class LeasedBucket(val remaining: AtomicLong = AtomicLong(0L), val mutex: Mutex = Mutex())
