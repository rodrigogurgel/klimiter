package io.klimiter.redis.internal.command

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import kotlinx.coroutines.reactive.awaitSingle

/**
 * Executor for Redis Cluster deployments. `EVALSHA` is routed to the owner node of the key's
 * hash slot; `SCRIPT LOAD` is broadcast to every master.
 *
 * All scripts used by KLimiter target a single key, so cross-slot concerns do not apply.
 */
internal class ClusterRedisCommandExecutor(private val connection: StatefulRedisClusterConnection<String, String>) :
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
