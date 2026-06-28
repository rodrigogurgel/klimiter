package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.PolicyResolution
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.fail

class PolicyFileWatcherTest {
    @TempDir
    private lateinit var dir: Path
    private lateinit var file: Path
    private lateinit var repository: FilePolicyRepository
    private lateinit var watcher: PolicyFileWatcher

    @BeforeEach
    fun setUp() {
        file = dir.resolve("policies.yaml")
        file.writeText(policiesYaml(requestsPerUnit = 100))
        val properties = PolicyProperties(path = file.toString(), reloadDebounce = Duration.ofMillis(30))
        repository = FilePolicyRepository(properties, YamlPolicyLoader())
        repository.loadOnStartup()
        watcher = PolicyFileWatcher(properties, repository)
    }

    @AfterEach
    fun tearDown() {
        watcher.stop()
    }

    @Test
    fun `reloads on file modification`() {
        assertEquals(Capacity(100), resolvedCapacity())

        watcher.start()
        file.writeText(policiesYaml(requestsPerUnit = 250))

        await { resolvedCapacity() == Capacity(250) }
    }

    @Test
    fun `keeps last good config on invalid change`() {
        watcher.start()
        file.writeText("version: 1\npolicies:\n  ip:\n    default:\n      unit: WEEK\n")

        // Inválido: o snapshot não muda. Aguarda além do debounce e confirma estabilidade.
        Thread.sleep(GRACE_MILLIS)
        assertEquals(Capacity(100), resolvedCapacity())
    }

    @Test
    fun `ignores file deletion`() {
        watcher.start()
        Files.delete(file)

        Thread.sleep(GRACE_MILLIS)
        assertEquals(Capacity(100), resolvedCapacity())
    }

    @Test
    fun `picks up a file created after start`(@TempDir emptyDir: Path) {
        val absent = emptyDir.resolve("policies.yaml")
        val properties = PolicyProperties(path = absent.toString(), reloadDebounce = Duration.ofMillis(30))
        val repo = FilePolicyRepository(properties, YamlPolicyLoader())
        repo.loadOnStartup()
        val freshWatcher = PolicyFileWatcher(properties, repo)
        try {
            assertEquals(0, repo.current().size) // ausente no boot → pass-through

            freshWatcher.start()
            absent.writeText(policiesYaml(requestsPerUnit = 100))

            await { repo.current().size == 1 }
        } finally {
            freshWatcher.stop()
        }
    }

    private fun resolvedCapacity(): Capacity? {
        val resolution = repository.current().resolve(Dimension("ip"), DimensionValue("10.0.0.1"))
        return (resolution as? PolicyResolution.Matched)?.policy?.capacity
    }

    private fun policiesYaml(requestsPerUnit: Int): String =
        """
        version: 1
        policies:
          ip:
            default:
              requests_per_unit: $requestsPerUnit
              unit: SECOND
        """.trimIndent()

    private fun await(timeoutMillis: Long = AWAIT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        fail("condição não satisfeita em ${timeoutMillis}ms")
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 20L
        const val GRACE_MILLIS = 300L
    }
}
