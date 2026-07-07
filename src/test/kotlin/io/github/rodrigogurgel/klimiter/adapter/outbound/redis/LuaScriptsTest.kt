package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.adapter.outbound.redis.LuaScripts.Companion.longAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Cobre o carregamento dos scripts Lua, o SHA1 local (= o do EVALSHA) e o parse da saída crua. */
class LuaScriptsTest {
    private val scripts = LuaScripts()

    @Test
    fun `loads every script from the classpath with a 40-hex sha1`() {
        for (script in listOf(scripts.conditionalIncrement, scripts.lease, scripts.paceLease)) {
            assertTrue(script.source.isNotBlank(), "fonte do script não deveria ser vazia")
            assertTrue(script.sha1.matches(Regex("[0-9a-f]{40}")), "sha1 deveria ser 40 hex: ${script.sha1}")
        }
    }

    @Test
    fun `scripts have distinct sources and digests`() {
        val sources = listOf(scripts.conditionalIncrement.source, scripts.lease.source, scripts.paceLease.source)
        val digests = listOf(scripts.conditionalIncrement.sha1, scripts.lease.sha1, scripts.paceLease.sha1)
        assertEquals(sources.size, sources.distinct().size)
        assertEquals(digests.size, digests.distinct().size)
    }

    @Test
    fun `missing resource fails fast`() {
        assertFailsWith<IllegalStateException> { LuaScript("redis/nao_existe.lua") }
    }

    @Test
    fun `longAt reads numbers by position and defaults to zero`() {
        val raw: List<Any?> = listOf(3L, 7, null)
        assertEquals(3L, raw.longAt(0))
        assertEquals(7L, raw.longAt(1))
        assertEquals(0L, raw.longAt(2)) // null -> 0
        assertEquals(0L, raw.longAt(9)) // fora do range -> 0
    }
}
