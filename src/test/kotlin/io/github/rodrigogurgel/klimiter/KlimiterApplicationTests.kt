package io.github.rodrigogurgel.klimiter

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Smoke test do wiring completo: sobe o contexto Spring com o contador global ligado a um Redis real
 * (Testcontainers). Sem Docker é pulado (`disabledWithoutDocker`); o restante da suíte segue rodando.
 */
// porta gRPC 0 = aleatória: o smoke test de contexto não colide com um serviço já rodando (ex.: o
// container do compose ou outro teste) na porta default 9090.
@SpringBootTest(properties = ["spring.grpc.server.port=0"])
@Testcontainers(disabledWithoutDocker = true)
class KlimiterApplicationTests {
    @Test
    fun contextLoads() {
        // Verifica que o contexto Spring sobe sem erros (incl. conexão ao Redis e beans do core).
    }

    private companion object {
        @Container
        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).apply { withExposedPorts(6379) }

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("klimiter.redis.uri") { "redis://${redis.host}:${redis.firstMappedPort}" }
        }
    }
}
