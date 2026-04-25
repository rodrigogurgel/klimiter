package io.klimiter.redis.internal.command

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.reactive.awaitSingle

/**
 * Executor for standalone and sentinel deployments — both expose the same
 * [StatefulRedisConnection] type; sentinel routing is handled internally by Lettuce.
 */
internal class StandaloneRedisCommandExecutor(private val connection: StatefulRedisConnection<String, String>) :
    RedisCommandExecutor {

    override suspend fun evalsha(
        sha: String,
        outputType: ScriptOutputType,
        keys: Array<String>,
        args: Array<String>,
    ): List<Any?> = connection.reactive()
        .evalsha<Any>(sha, outputType, keys, *args)
        .collectList()
        .awaitSingle()

    override suspend fun scriptLoad(script: String): String = connection.reactive().scriptLoad(script).awaitSingle()
}
