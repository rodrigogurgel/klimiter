package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LuaScripts.Companion.longAt
import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisScriptingCoroutinesCommands
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.coroutines
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import java.time.Duration as JavaDuration

/**
 * Adapter Redis (Lettuce) da porta [GlobalCounter] (§4): um POOL de N conexões multiplexadas e
 * pipelinadas sobre Netty, com round-robin lock-free. `coroutines()` suspende no round-trip — nenhuma
 * thread de plataforma fica parada esperando o Redis. NÃO criar conexão por request.
 *
 * Round-robin de N conexões persistentes (e não um pool com acquire/release): uma conexão Lettuce já
 * multiplexa comandos concorrentes pipelinados; espalhar por N usa N event-loops do Netty e mantém
 * mais comandos em voo — sobretudo no caminho BAIXA (§6), que faz 1 round-trip por request.
 *
 * **Standalone ou cluster** ([standalone]/[cluster]): como os scripts são single-key (`KEYS[1]`), o
 * cluster roteia cada comando por slot sem CROSSSLOT; o adapter só depende da interface de comandos
 * ([RedisScriptingCoroutinesCommands]), comum aos dois modos. As [Duration] da porta viram os ms dos
 * scripts Lua (§6.2). [closeable] encerra conexões e client.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class LettuceGlobalCounter(
    private val commands: List<RedisScriptingCoroutinesCommands<String, String>>,
    private val closeable: AutoCloseable,
) : GlobalCounter,
    AutoCloseable {
    init {
        require(commands.isNotEmpty()) { "LettuceGlobalCounter exige ao menos uma conexão" }
    }

    private val scripts = LuaScripts()

    /** Cursor de round-robin lock-free; `and MAX_VALUE` evita índice negativo no overflow. */
    private val cursor = AtomicInteger(0)

    private fun next(): RedisScriptingCoroutinesCommands<String, String> =
        commands[(cursor.getAndIncrement() and Int.MAX_VALUE) % commands.size]

    override suspend fun lease(key: String, capacity: Long, requested: Long, ttl: Duration): LeaseResult {
        val result = scripts.eval(
            next(),
            scripts.lease,
            key,
            capacity.toString(),
            requested.toString(),
            ttl.inWholeMilliseconds.toString(),
        )
        return LeaseResult(granted = result.longAt(0), freeGlobal = result.longAt(1))
    }

    override suspend fun paceLease(
        key: String,
        capacity: Long,
        missing: Long,
        elapsed: Duration,
        duration: Duration,
        ttl: Duration,
    ): PaceResult {
        val result = scripts.eval(
            next(),
            scripts.paceLease,
            key,
            capacity.toString(),
            missing.toString(),
            elapsed.inWholeMilliseconds.toString(),
            duration.inWholeMilliseconds.toString(),
            ttl.inWholeMilliseconds.toString(),
        )
        return PaceResult(admitted = result.longAt(0) == 1L, freeGlobal = result.longAt(1))
    }

    override fun close() = closeable.close()

    companion object {
        /** Refresh periódico da topologia do cluster (além do adaptativo em MOVED/ASK). */
        private val CLUSTER_TOPOLOGY_REFRESH = JavaDuration.ofSeconds(30)

        /** Standalone: client + [poolSize] conexões multiplexadas sempre abertas. */
        fun standalone(redisUri: String, poolSize: Int): LettuceGlobalCounter {
            require(poolSize > 0) { "poolSize deve ser > 0" }
            val client = RedisClient.create(redisUri)
            val connections = List(poolSize) { client.connect() }
            return LettuceGlobalCounter(connections.map { it.coroutines() }, closer(connections, client::shutdown))
        }

        /**
         * Cluster (Redis Cluster / ElastiCache cluster mode enabled): client com descoberta de
         * topologia (adaptive refresh em MOVED/ASK/reconnect + refresh periódico — sobrevive a
         * failover/resharding) e [poolSize] conexões. Escritas roteiam para o master do slot.
         */
        fun cluster(redisUri: String, poolSize: Int): LettuceGlobalCounter {
            require(poolSize > 0) { "poolSize deve ser > 0" }
            val client = RedisClusterClient.create(redisUri)
            client.setOptions(clusterOptions())
            val connections = List(poolSize) { client.connect() }
            return LettuceGlobalCounter(connections.map { it.coroutines() }, closer(connections, client::shutdown))
        }

        private fun clusterOptions(): ClusterClientOptions {
            val topology = ClusterTopologyRefreshOptions.builder()
                .enableAllAdaptiveRefreshTriggers()
                .enablePeriodicRefresh(CLUSTER_TOPOLOGY_REFRESH)
                .build()
            return ClusterClientOptions.builder().topologyRefreshOptions(topology).build()
        }

        /** Fecha as conexões e desliga o client (o caller fecha o [LettuceGlobalCounter]). */
        private fun closer(connections: List<AutoCloseable>, shutdown: () -> Unit) = AutoCloseable {
            connections.forEach { it.close() }
            shutdown()
        }
    }
}
