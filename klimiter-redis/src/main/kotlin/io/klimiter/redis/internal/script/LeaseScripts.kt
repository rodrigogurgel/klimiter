package io.klimiter.redis.internal.script

/**
 * Lua source for atomic lease acquisition against Redis.
 *
 * The bucket key already carries the window start (produced by KLimiter's key generator), so
 * each window is a distinct Redis key and TTL only needs to outlive that window. Rollbacks are
 * local-only — [ACQUIRE] has no sibling release script by design.
 */
internal object LeaseScripts {

    /**
     * Atomically grants up to `ARGV[2]` units from the bucket's remaining budget.
     *
     * KEYS[1] — bucket key.
     * ARGV[1] — global limit for the window.
     * ARGV[2] — requested lease size.
     * ARGV[3] — TTL in seconds, applied when the bucket has none yet.
     *
     * Returns the number of units granted (0 when the global limit has been reached).
     */
    const val ACQUIRE: String = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local requested = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])

        local current = tonumber(redis.call('GET', key)) or 0
        if current >= limit then
          return 0
        end

        local granted = math.min(limit - current, requested)
        redis.call('INCRBY', key, granted)
        if redis.call('PTTL', key) < 0 then
          redis.call('EXPIRE', key, ttl)
        end
        return granted
    """
}
