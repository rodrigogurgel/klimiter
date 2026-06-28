package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.coroutines.RedisScriptingCoroutinesCommands
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Um script Lua do classpath, com o SHA1 calculado localmente (= o digest que o Redis usa no
 * EVALSHA), evitando um round-trip de `SCRIPT LOAD` no boot.
 */
class LuaScript(resourcePath: String) {
    val source: String = LuaScript::class.java.classLoader
        .getResourceAsStream(resourcePath)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("script Lua não encontrado no classpath: $resourcePath")

    val sha1: String = sha1Hex(source)

    private companion object {
        fun sha1Hex(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Registro dos scripts (§4.1, §6.4) + EVALSHA com fallback NOSCRIPT: após failover/flush o script
 * some do cache do Redis → recarrega via EVAL (re-popula o cache) e segue. Stateless quanto à conexão
 * (os comandos vêm da conexão do pool em cada chamada), então uma instância serve o pool.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class LuaScripts {
    private val log = LoggerFactory.getLogger(LuaScripts::class.java)

    val lease = LuaScript("redis/lease.lua")
    val paceLease = LuaScript("redis/pace_lease.lua")

    /** Executa o script (saída MULTI → lista de inteiros), com EVALSHA → fallback EVAL no NOSCRIPT. */
    @Suppress("UNCHECKED_CAST", "SpreadOperator") // varargs repassados à API do Lettuce
    suspend fun evalLongs(
        commands: RedisScriptingCoroutinesCommands<String, String>,
        script: LuaScript,
        key: String,
        vararg args: String,
    ): List<Long> {
        val keys = arrayOf(key)
        val raw = try {
            commands.evalsha<List<Any?>>(script.sha1, ScriptOutputType.MULTI, keys, *args)
        } catch (noScript: RedisNoScriptException) {
            log.atDebug()
                .setCause(noScript)
                .addKeyValue("sha1", script.sha1)
                .setMessage("script ausente no cache do Redis (NOSCRIPT); recarregando via EVAL")
                .log()
            commands.eval<List<Any?>>(script.source, ScriptOutputType.MULTI, keys, *args)
        }
        return raw.orEmpty().map { (it as Number).toLong() }
    }
}
