package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import io.github.rodrigogurgel.klimiter.core.domain.Priority
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `tryAcquire HIGH admits up to the capacity and denies without writing`() = runBlocking {
        val key = "klimiter:it:acquire:high"
        val first = counter.tryAcquire(
            key,
            capacity = 5,
            hits = 3,
            priority = Priority.HIGH,
            elapsed = 0.seconds,
            duration = 60.seconds,
            ttl = 1.minutes,
        )
        assertTrue(first.admitted)
        assertEquals(3, first.counter)

        // 3 + 3 > 5 → nega SEM escrever: o contador retorna inalterado (§4).
        val denied = counter.tryAcquire(key, 5, 3, Priority.HIGH, 0.seconds, 60.seconds, 1.minutes)
        assertFalse(denied.admitted)
        assertEquals(3, denied.counter)

        // O que cabe exato ainda passa; depois disso a janela está cheia e o contador congela em 5.
        val exact = counter.tryAcquire(key, 5, 2, Priority.HIGH, 0.seconds, 60.seconds, 1.minutes)
        assertTrue(exact.admitted)
        assertEquals(5, exact.counter)

        val full = counter.tryAcquire(key, 5, 1, Priority.HIGH, 0.seconds, 60.seconds, 1.minutes)
        assertFalse(full.admitted)
        assertEquals(5, full.counter)
    }

    @Test
    fun `tryAcquire LOW paces by the release line against the same counter`() = runBlocking {
        val key = "klimiter:it:acquire:low"
        // cap 1000, decorrido 30s de 60s → linha = floor(1000*30/60)+1 = 501
        val admitted = counter.tryAcquire(
            key,
            capacity = 1000,
            hits = 1,
            priority = Priority.LOW,
            elapsed = 30.seconds,
            duration = 60.seconds,
            ttl = 1.minutes,
        )
        assertTrue(admitted.admitted)
        assertEquals(1, admitted.counter)

        // 1 + 501 > 501 → acima da linha: negado sem escrever.
        val paced = counter.tryAcquire(key, 1000, 501, Priority.LOW, 30.seconds, 60.seconds, 1.minutes)
        assertFalse(paced.admitted)
        assertEquals(1, paced.counter)
    }

    private companion object {
        private val COMMAND_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(2)

        @Container
        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).apply { withExposedPorts(6379) }
    }
}
