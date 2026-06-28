package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.Capacity
import io.github.rodrigogurgel.klimiter.core.policy.PolicyResolution
import io.github.rodrigogurgel.klimiter.core.policy.Prefetch
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class YamlPolicyLoaderTest {
    private val loader = YamlPolicyLoader()

    @Test
    fun `loads bundled example policies yaml`() {
        val snapshot = loader.load(Path.of("config/policies/policies.yaml"))

        val userId = assertIs<PolicyResolution.Matched>(
            snapshot.resolve(Dimension("user_id"), DimensionValue("qualquer")),
        )
        assertEquals(Capacity(1000), userId.policy.capacity)
        assertEquals(RateLimitUnit.MINUTE, userId.policy.unit)
        assertEquals(Prefetch.Percent(10), userId.policy.prefetch)

        val serviceAccount = assertIs<PolicyResolution.Matched>(
            snapshot.resolve(Dimension("user_id"), DimensionValue("service-account")),
        )
        assertEquals(Prefetch.Count(500), serviceAccount.policy.prefetch)

        assertEquals(
            PolicyResolution.PassThrough,
            snapshot.resolve(Dimension("desconhecida"), DimensionValue("x")),
        )
    }

    @Test
    fun `rule without prefetch resolves to Prefetch None`(@TempDir dir: Path) {
        val snapshot = loader.load(
            dir.policiesYaml(
                """
                version: 1
                policies:
                  ip:
                    default:
                      requests_per_unit: 100
                      unit: SECOND
                """.trimIndent(),
            ),
        )
        val matched = assertIs<PolicyResolution.Matched>(
            snapshot.resolve(Dimension("ip"), DimensionValue("10.0.0.1")),
        )
        assertEquals(Prefetch.None, matched.policy.prefetch)
    }

    @Test
    fun `unknown field is rejected`(@TempDir dir: Path) {
        val file = dir.policiesYaml(
            """
            version: 1
            policies:
              ip:
                default:
                  requests_per_unit: 100
                  unit: SECOND
                  ttl: 60
            """.trimIndent(),
        )
        assertFailsWith<PolicyFileException> { loader.load(file) }
    }

    @Test
    fun `prefetch with both percent and count is rejected`(@TempDir dir: Path) {
        val file = dir.policiesYaml(
            """
            version: 1
            policies:
              ip:
                default:
                  requests_per_unit: 100
                  unit: SECOND
                  prefetch:
                    percent: 10
                    count: 5
            """.trimIndent(),
        )
        assertFailsWith<PolicyFileException> { loader.load(file) }
    }

    @Test
    fun `dimension without default is rejected`(@TempDir dir: Path) {
        val file = dir.policiesYaml(
            """
            version: 1
            policies:
              ip:
                overrides:
                  x:
                    requests_per_unit: 10
                    unit: SECOND
            """.trimIndent(),
        )
        assertFailsWith<PolicyFileException> { loader.load(file) }
    }

    @Test
    fun `invalid unit is rejected`(@TempDir dir: Path) {
        val file = dir.policiesYaml(
            """
            version: 1
            policies:
              ip:
                default:
                  requests_per_unit: 10
                  unit: WEEK
            """.trimIndent(),
        )
        assertFailsWith<PolicyFileException> { loader.load(file) }
    }

    @Test
    fun `version other than 1 is rejected`(@TempDir dir: Path) {
        val file = dir.policiesYaml(
            """
            version: 2
            policies:
              ip:
                default:
                  requests_per_unit: 10
                  unit: SECOND
            """.trimIndent(),
        )
        assertFailsWith<PolicyFileException> { loader.load(file) }
    }

    @Test
    fun `missing file throws PolicyFileException`(@TempDir dir: Path) {
        assertFailsWith<PolicyFileException> { loader.load(dir.resolve("ausente.yaml")) }
    }

    private fun Path.policiesYaml(content: String): Path = resolve("policies.yaml").apply { writeText(content) }
}
