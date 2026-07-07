package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LuaScripts.Companion.longAt
import io.github.rodrigogurgel.klimiter.core.domain.AcquireResult
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
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
 * Adapter Redis (Lettuce) da porta [GlobalCounter] (V2 §4): um POOL de N conexões multiplexadas e
 * pipelinadas sobre Netty, com round-robin lock-free. `coroutines()` suspende no round-trip — nenhuma
 * thread de plataforma fica parada esperando o Redis. NÃO criar conexão por request.
 *
 * Round-robin de N conexões persistentes (e não um pool com acquire/release): uma conexão Lettuce já
 * multiplexa comandos concorrentes pipelinados; espalhar por N usa N event-loops do Netty e mantém
 * mais comandos em voo — no V2 toda admissão faz 1 round-trip (§1).
 *
 * **Standalone ou cluster** ([standalone]/[cluster]): como o script é single-key (`KEYS[1]`), o
 * cluster roteia cada comando por slot sem CROSSSLOT; o adapter só depende da interface de comandos
 * ([RedisScriptingCoroutinesCommands]), comum aos dois modos. O limiar chega pronto do núcleo (§6.3)
 * e a [Duration] do TTL vira os ms do script (§6.1). [closeable] encerra conexões e client.
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

    override suspend fun tryAcquire(key: String, threshold: Long, hits: Long, ttl: Duration): AcquireResult {
        val result = scripts.eval(
            next(),
            scripts.conditionalIncrement,
            key,
            threshold.toString(),
            hits.toString(),
            ttl.inWholeMilliseconds.toString(),
        )
        return AcquireResult(admitted = result.longAt(0) == 1L, counter = result.longAt(1))
    }

    override fun close() = closeable.close()

    companion object {
        /** Refresh periódico da topologia do cluster (além do adaptativo em MOVED/ASK). */
        private val CLUSTER_TOPOLOGY_REFRESH = JavaDuration.ofSeconds(30)

        /**
         * Standalone: client + [poolSize] conexões multiplexadas sempre abertas. O [commandTimeout]
         * é setado na [RedisURI] e vira o command timeout default de todas as conexões.
         */
        fun standalone(redisUri: String, poolSize: Int, commandTimeout: JavaDuration): LettuceGlobalCounter {
            require(poolSize > 0) { "poolSize deve ser > 0" }
            val uri = RedisURI.create(redisUri).apply { timeout = commandTimeout }
            val client = RedisClient.create(uri)
            val connections = List(poolSize) { client.connect() }
            return LettuceGlobalCounter(connections.map { it.coroutines() }, closer(connections, client::shutdown))
        }

        /**
         * Cluster (Redis Cluster / ElastiCache cluster mode enabled): client com descoberta de
         * topologia (adaptive refresh em MOVED/ASK/reconnect + refresh periódico — sobrevive a
         * failover/resharding) e [poolSize] conexões. Escritas roteiam para o master do slot. O
         * [commandTimeout] na [RedisURI] vira o command timeout default das conexões.
         */
        fun cluster(redisUri: String, poolSize: Int, commandTimeout: JavaDuration): LettuceGlobalCounter {
            require(poolSize > 0) { "poolSize deve ser > 0" }
            val uri = RedisURI.create(redisUri).apply { timeout = commandTimeout }
            val client = RedisClusterClient.create(uri)
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
