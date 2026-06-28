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
    fun `loads both scripts from the classpath with a 40-hex sha1`() {
        for (script in listOf(scripts.lease, scripts.paceLease)) {
            assertTrue(script.source.isNotBlank(), "fonte do script não deveria ser vazia")
            assertTrue(script.sha1.matches(Regex("[0-9a-f]{40}")), "sha1 deveria ser 40 hex: ${script.sha1}")
        }
    }

    @Test
    fun `the two scripts have distinct sources and digests`() {
        assertTrue(scripts.lease.source != scripts.paceLease.source)
        assertTrue(scripts.lease.sha1 != scripts.paceLease.sha1)
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
