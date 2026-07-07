package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
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
 * Integração do adapter no modo **cluster** ([LettuceGlobalCounter.cluster]) contra um Redis real em
 * cluster-mode (Testcontainers). Sem Docker, é pulado. Um único nó dono de todos os 16384 slots basta
 * para exercitar o caminho de cluster: descoberta de topologia, roteamento por slot do `EVALSHA`
 * single-key e o fallback NOSCRIPT. `cluster-announce-ip/port` fixos garantem que o MOVED aponte para
 * um endereço alcançável a partir do host.
 */
@Testcontainers(disabledWithoutDocker = true)
class LettuceClusterGlobalCounterIT {
    private lateinit var counter: LettuceGlobalCounter

    @BeforeEach
    fun setUp() {
        redis.execInContainer("redis-cli", "-p", "$PORT", "cluster", "addslotsrange", "0", "16383")
        waitClusterOk()
        counter = LettuceGlobalCounter.cluster(
            "redis://${redis.host}:$PORT",
            poolSize = 2,
            commandTimeout = COMMAND_TIMEOUT,
        )
    }

    @AfterEach
    fun tearDown() {
        counter.close()
    }

    @Test
    fun `tryAcquire routes by slot and never writes above the threshold`() = runBlocking {
        val key = "klimiter:it:cluster:acquire"
        val first = counter.tryAcquire(
            key,
            capacity = 4,
            hits = 4,
            priority = Priority.HIGH,
            elapsed = 0.seconds,
            duration = 60.seconds,
            ttl = 1.minutes,
        )
        assertTrue(first.admitted)
        assertEquals(4, first.counter)

        val denied = counter.tryAcquire(key, 4, 1, Priority.HIGH, 0.seconds, 60.seconds, 1.minutes)
        assertFalse(denied.admitted)
        assertEquals(4, denied.counter)
    }

    @Test
    fun `lease routes by slot through the cluster client and reports free global`() = runBlocking {
        val key = "klimiter:it:cluster:lease"
        val first = counter.lease(key, capacity = 10, requested = 4, ttl = 1.minutes)
        assertEquals(4, first.granted)
        assertEquals(6, first.freeGlobal)

        val second = counter.lease(key, capacity = 10, requested = 100, ttl = 1.minutes)
        assertEquals(6, second.granted)
        assertEquals(0, second.freeGlobal)
    }

    private fun waitClusterOk() {
        repeat(WAIT_ATTEMPTS) {
            val info = redis.execInContainer("redis-cli", "-p", "$PORT", "cluster", "info").stdout
            if (info.contains("cluster_state:ok")) return
            Thread.sleep(WAIT_INTERVAL_MS)
        }
        error("cluster não ficou pronto (cluster_state:ok) a tempo")
    }

    private companion object {
        private val COMMAND_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(2)

        const val PORT = 7379
        const val BUS_PORT = 17379
        const val WAIT_ATTEMPTS = 20
        const val WAIT_INTERVAL_MS = 250L

        @Container
        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(PORT)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig?.withPortBindings(
                        PortBinding(Ports.Binding.bindPort(PORT), ExposedPort(PORT)),
                    )
                }
                .withCommand(
                    "redis-server",
                    "--port", "$PORT",
                    "--cluster-enabled", "yes",
                    "--cluster-node-timeout", "5000",
                    "--cluster-announce-ip", "127.0.0.1",
                    "--cluster-announce-port", "$PORT",
                    "--cluster-announce-bus-port", "$BUS_PORT",
                    "--appendonly", "yes",
                )
    }
}
