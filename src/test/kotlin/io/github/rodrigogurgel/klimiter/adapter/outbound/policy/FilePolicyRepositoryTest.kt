package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.support.RecordingRateLimitMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class FilePolicyRepositoryTest {
    private fun repoFor(path: Path, metrics: RecordingRateLimitMetrics) =
        FilePolicyRepository(PolicyProperties(path = path.toString()), YamlPolicyLoader(), metrics)

    @Test
    fun `reload reconciles the detailed policy meters with the loaded snapshot`(@TempDir dir: Path) {
        val file = dir.resolve("policies.yaml")
        Files.writeString(
            file,
            """
            version: 1
            policies:
              user_id:
                default:
                  requests_per_unit: 100
                  unit: MINUTE
                overrides:
                  vip:
                    requests_per_unit: 1000
                    unit: MINUTE
                    detailed_metric: true
            """.trimIndent(),
        )
        val metrics = RecordingRateLimitMetrics()

        repoFor(file, metrics).reload()

        assertEquals(
            setOf(
                PolicyMeterKey(Dimension("user_id"), null),
                PolicyMeterKey(Dimension("user_id"), DimensionValue("vip")),
            ),
            metrics.lastSyncedPolicies,
        )
    }

    @Test
    fun `a reload that keeps the last good config still does not sync on parse failure`(@TempDir dir: Path) {
        val file = dir.resolve("policies.yaml")
        Files.writeString(file, "version: 2\npolicies: {}") // versão inválida → mantém o atual, sem sync
        val metrics = RecordingRateLimitMetrics()

        repoFor(file, metrics).reload()

        assertEquals(null, metrics.lastSyncedPolicies)
    }
}
