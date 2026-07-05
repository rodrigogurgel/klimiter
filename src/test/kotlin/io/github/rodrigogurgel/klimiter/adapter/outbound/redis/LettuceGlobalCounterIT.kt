package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * Integração do adapter contra um Redis real (Testcontainers). Sem Docker, é pulado
 * (`disabledWithoutDocker`); os testes unitários do core seguem rodando. A primeira chamada já
 * exercita o fallback NOSCRIPT (o script não está no cache do servidor → EVALSHA falha → EVAL).
 */
@Testcontainers(disabledWithoutDocker = true)
class LettuceGlobalCounterIT {
    private lateinit var counter: LettuceGlobalCounter

    @BeforeEach
    fun setUp() {
        val uri = "redis://${redis.host}:${redis.firstMappedPort}"
        counter = LettuceGlobalCounter.standalone(uri, poolSize = 2, commandTimeout = COMMAND_TIMEOUT)
    }

    @AfterEach
    fun tearDown() {
        counter.close()
    }

    @Test
    fun `lease grants up to the remaining capacity and reports free global`() = runBlocking {
        val key = "klimiter:it:lease"
        val first = counter.lease(key, capacity = 10, requested = 4, ttl = 1.minutes)
        assertEquals(4, first.granted)
        assertEquals(6, first.freeGlobal)

        val second = counter.lease(key, capacity = 10, requested = 100, ttl = 1.minutes)
        assertEquals(6, second.granted) // só restavam 6
        assertEquals(0, second.freeGlobal)

        val third = counter.lease(key, capacity = 10, requested = 1, ttl = 1.minutes)
        assertEquals(0, third.granted) // janela cheia
        assertEquals(0, third.freeGlobal)
    }

    @Test
    fun `paceLease admits below the release line and increments`() = runBlocking {
        val key = "klimiter:it:pace"
        // cap 1000, decorrido 30s de 60s → linha = floor(1000*30/60)+1 = 501
        val result = counter.paceLease(
            key,
            capacity = 1000,
            missing = 1,
            elapsed = 30.seconds,
            duration = 60.seconds,
            ttl = 1.minutes,
        )
        assertTrue(result.admitted)
        assertEquals(999, result.freeGlobal)
    }

    private companion object {
        private val COMMAND_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(2)

        @Container
        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).apply { withExposedPorts(6379) }
    }
}
