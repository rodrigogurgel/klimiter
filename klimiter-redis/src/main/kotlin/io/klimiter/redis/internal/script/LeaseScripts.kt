package io.klimiter.redis.internal.script

/**
 * Lua source for atomic lease acquisition against Redis.
 *
 * The bucket key already contains the window start, produced by KLimiter's key generator.
 * Therefore, each rate-limit window is represented by a distinct Redis key.
 *
 * The value stored in Redis is the accumulated amount already leased for that window,
 * not the remaining budget.
 *
 * Rollbacks are local-only: once a lease is acquired from Redis, this script does not
 * provide a matching release operation. Unused locally reserved capacity is expected to
 * expire locally and is not returned to the global Redis bucket.
 */
internal object LeaseScripts {

    /**
     * Atomically grants up to `ARGV[2]` units from the global window budget.
     *
     * KEYS[1] — bucket key for the current window.
     * ARGV[1] — global limit for the window.
     * ARGV[2] — requested lease size.
     * ARGV[3] — TTL in seconds, applied only when the bucket has no TTL yet.
     *
     * Behavior:
     * - Reads the amount already leased for the current window.
     * - If the leased amount is already greater than or equal to the global limit,
     *   grants zero units.
     * - Otherwise, grants the smaller value between the requested lease size and the
     *   remaining global capacity.
     * - Increments the bucket by the granted amount.
     * - Applies the TTL only if the key does not already have one.
     *
     * Returns:
     * - `[granted, remaining]`
     *
     * Where:
     * - `granted` is the number of units granted by this lease request.
     * - `remaining` is the remaining global capacity for the window after this grant.
     *
     * When the global limit has already been reached, returns `[0, leasedSoFar]`.
     */
    const val LEASE_ACQUIRE: String = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local requested = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])
        local leasedSoFar = tonumber(redis.call('GET', key)) or 0

        if leasedSoFar >= limit then
          return {0, 0}
        end

        local available = limit - leasedSoFar
        local granted = math.min(available, requested)
        local leasedAfterGrant = leasedSoFar + granted
        local remaining = limit - leasedAfterGrant
        
        redis.call('INCRBY', key, granted)

        if redis.call('PTTL', key) < 0 then
          redis.call('EXPIRE', key, ttl)
        end

        return {granted, remaining}
    """
}
