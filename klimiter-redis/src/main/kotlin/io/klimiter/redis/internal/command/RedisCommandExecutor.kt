package io.klimiter.redis.internal.command

import io.lettuce.core.ScriptOutputType

/**
 * Thin abstraction over Lettuce's standalone, sentinel, and cluster command surfaces.
 *
 * Standalone and sentinel share the same connection type (`StatefulRedisConnection`); cluster
 * uses `StatefulRedisClusterConnection`. Both expose `evalsha` / `scriptLoad`, but their types
 * are not related by inheritance, so we hide them behind this interface.
 */
internal interface RedisCommandExecutor {

    /**
     * Runs a previously-loaded script by its SHA1. Callers must catch `NOSCRIPT` and reload
     * the script via [scriptLoad] before retrying.
     */
    suspend fun evalsha(
        sha: String,
        outputType: ScriptOutputType,
        keys: Array<String>,
        args: Array<String>,
    ): List<Any?>

    /**
     * Loads a Lua script and returns its SHA1. For cluster, the script is broadcast to every
     * master node so subsequent `EVALSHA` calls hit the correct shard.
     */
    suspend fun scriptLoad(script: String): String
}
