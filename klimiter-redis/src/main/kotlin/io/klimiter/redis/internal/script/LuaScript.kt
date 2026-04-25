package io.klimiter.redis.internal.script

import io.klimiter.redis.internal.command.RedisCommandExecutor
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps a Lua script with lazy SCRIPT LOAD and automatic NOSCRIPT recovery. The first call
 * loads and caches the SHA1; subsequent calls use EVALSHA. If Redis evicts the script (FLUSH,
 * restart, failover to a replica that never cached it), we reload it transparently.
 *
 * The SHA cache is per-instance. Typical usage is to hold one `LuaScript` in a `private
 * companion object` of the class that consumes it (e.g. [io.klimiter.redis.internal.RedisRateLimitOperation]),
 * which shares the cache process-wide. This is safe even when multiple factories point at
 * different Redis deployments: the SHA1 is a deterministic function of the source, so every
 * deployment returns the same SHA and [RedisNoScriptException] handles the rare case where
 * a given server has not yet cached it.
 */
internal class LuaScript(private val source: String) {
    private val cachedSha = AtomicReference<String?>(null)

    suspend fun <T> execute(
        executor: RedisCommandExecutor,
        outputType: ScriptOutputType,
        keys: Array<String>,
        args: Array<String>,
    ): T {
        val sha = cachedSha.get() ?: loadAndCache(executor)

        return try {
            executor.evalsha(sha, outputType, keys, args)
        } catch (_: RedisNoScriptException) {
            cachedSha.set(null)
            val freshSha = loadAndCache(executor)
            executor.evalsha(freshSha, outputType, keys, args)
        }
    }

    private suspend fun loadAndCache(executor: RedisCommandExecutor): String {
        val sha = executor.scriptLoad(source)
        cachedSha.compareAndSet(null, sha)
        return sha
    }
}
