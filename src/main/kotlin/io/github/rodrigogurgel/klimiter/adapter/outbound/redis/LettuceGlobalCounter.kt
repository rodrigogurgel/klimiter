package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LuaScripts.Companion.longAt
import io.github.rodrigogurgel.klimiter.core.domain.LeaseResult
import io.github.rodrigogurgel.klimiter.core.domain.PaceResult
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisScriptingCoroutinesCommands
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Adapter Redis (Lettuce) da porta [GlobalCounter] (§4): um POOL de N conexões multiplexadas e
 * pipelinadas sobre Netty, com round-robin lock-free. `coroutines()` suspende no round-trip — nenhuma
 * thread de plataforma fica parada esperando o Redis. NÃO criar conexão por request.
 *
 * Round-robin de N conexões persistentes (e não um pool com acquire/release): uma conexão Lettuce já
 * multiplexa comandos concorrentes pipelinados; espalhar por N usa N event-loops do Netty e mantém
 * mais comandos em voo — sobretudo no caminho BAIXA (§6), que faz 1 round-trip por request.
 *
 * As [Duration] da porta são convertidas para os ms que os scripts Lua usam (§6.2).
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class LettuceGlobalCounter(private val connections: List<StatefulRedisConnection<String, String>>) :
    GlobalCounter,
    AutoCloseable {
    init {
        require(connections.isNotEmpty()) { "LettuceGlobalCounter exige ao menos uma conexão" }
    }

    private val scripts = LuaScripts()

    private val commands: List<RedisScriptingCoroutinesCommands<String, String>> =
        connections.map { it.coroutines() }

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

    override fun close() = connections.forEach { it.close() }

    companion object {
        /** Cria client + [poolSize] conexões multiplexadas, sempre abertas. O caller fecha ambos. */
        fun connect(redisUri: String, poolSize: Int): Pair<RedisClient, LettuceGlobalCounter> {
            require(poolSize > 0) { "poolSize deve ser > 0" }
            val client = RedisClient.create(redisUri)
            val connections = List(poolSize) { client.connect() }
            return client to LettuceGlobalCounter(connections)
        }
    }
}
