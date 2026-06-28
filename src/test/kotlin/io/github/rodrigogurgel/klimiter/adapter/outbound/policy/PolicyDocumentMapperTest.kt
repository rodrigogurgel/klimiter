package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.policy.PolicyMeterKey
import io.github.rodrigogurgel.klimiter.core.policy.PolicyResolution
import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import io.github.rodrigogurgel.klimiter.core.policy.Prefetch
import io.github.rodrigogurgel.klimiter.core.policy.RateLimitUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Cobre as regras de validação do mapper `PolicyDocument.toSnapshot()` (espelho do JSON Schema). */
class PolicyDocumentMapperTest {
    private fun policyOf(snapshot: PolicySnapshot, dim: String, value: String) =
        assertIs<PolicyResolution.Matched>(snapshot.resolve(Dimension(dim), DimensionValue(value))).policy

    @Test
    fun `maps a valid document with default, override and both prefetch kinds`() {
        val doc = PolicyDocument(
            version = 1,
            policies = mapOf(
                "user_id" to DimensionDocument(
                    default = RuleDocument(
                        requestsPerUnit = 60,
                        unit = "SECOND",
                        prefetch = PrefetchDocument(percent = 10),
                    ),
                    overrides = mapOf("vip" to RuleDocument(1000, "MINUTE", PrefetchDocument(count = 5))),
                ),
            ),
        )
        val snapshot = doc.toSnapshot()
        assertEquals(1, snapshot.size)

        val def = policyOf(snapshot, "user_id", "anon")
        assertEquals(60, def.capacity.requestsPerUnit)
        assertEquals(RateLimitUnit.SECOND, def.unit)
        assertIs<Prefetch.Percent>(def.prefetch)

        val vip = policyOf(snapshot, "user_id", "vip")
        assertEquals(RateLimitUnit.MINUTE, vip.unit)
        assertIs<Prefetch.Count>(vip.prefetch)
    }

    @Test
    fun `absent prefetch maps to None`() {
        val doc = PolicyDocument(1, mapOf("d" to DimensionDocument(RuleDocument(10, "SECOND", prefetch = null))))
        assertEquals(Prefetch.None, policyOf(doc.toSnapshot(), "d", "x").prefetch)
    }

    @Test
    fun `rejects an unsupported version`() {
        val ex = assertFailsWith<IllegalArgumentException> { PolicyDocument(2, mapOf()).toSnapshot() }
        assertTrue(ex.message!!.contains("version"))
    }

    @Test
    fun `rejects missing or empty policies`() {
        assertFailsWith<IllegalArgumentException> { PolicyDocument(1, null).toSnapshot() }
        assertFailsWith<IllegalArgumentException> { PolicyDocument(1, emptyMap()).toSnapshot() }
    }

    @Test
    fun `rejects a dimension without default, contextualizing the dimension name`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            PolicyDocument(1, mapOf("user_id" to DimensionDocument(default = null))).toSnapshot()
        }
        assertTrue(ex.message!!.contains("user_id"))
    }

    @Test
    fun `rejects rules missing requests_per_unit or unit, and invalid unit`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyDocument(
                1,
                mapOf("d" to DimensionDocument(RuleDocument(requestsPerUnit = null, unit = "SECOND"))),
            ).toSnapshot()
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyDocument(1, mapOf("d" to DimensionDocument(RuleDocument(10, unit = null)))).toSnapshot()
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            PolicyDocument(1, mapOf("d" to DimensionDocument(RuleDocument(10, "DECADE")))).toSnapshot()
        }
        assertTrue(ex.message!!.contains("unit inválida"))
    }

    @Test
    fun `rejects prefetch with both or neither percent and count`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyDocument(
                1,
                mapOf("d" to DimensionDocument(RuleDocument(10, "SECOND", PrefetchDocument(percent = 10, count = 5)))),
            ).toSnapshot()
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyDocument(
                1,
                mapOf("d" to DimensionDocument(RuleDocument(10, "SECOND", PrefetchDocument()))),
            ).toSnapshot()
        }
    }

    @Test
    fun `detailed_metric defaults to true on the dimension default and false on overrides`() {
        val doc = PolicyDocument(
            1,
            mapOf(
                "user_id" to DimensionDocument(
                    default = RuleDocument(10, "SECOND"),
                    overrides = mapOf("vip" to RuleDocument(1000, "MINUTE")),
                ),
            ),
        )
        val snapshot = doc.toSnapshot()
        assertTrue(policyOf(snapshot, "user_id", "anon").detailedMetric)
        assertFalse(policyOf(snapshot, "user_id", "vip").detailedMetric)
    }

    @Test
    fun `detailed_metric honors explicit values overriding the defaults`() {
        val doc = PolicyDocument(
            1,
            mapOf(
                "user_id" to DimensionDocument(
                    default = RuleDocument(10, "SECOND", detailedMetric = false),
                    overrides = mapOf("vip" to RuleDocument(1000, "MINUTE", detailedMetric = true)),
                ),
            ),
        )
        val snapshot = doc.toSnapshot()
        assertFalse(policyOf(snapshot, "user_id", "anon").detailedMetric)
        assertTrue(policyOf(snapshot, "user_id", "vip").detailedMetric)
    }

    @Test
    fun `detailedMeterKeys lists enabled default and overrides, excluding disabled ones`() {
        val doc = PolicyDocument(
            1,
            mapOf(
                "user_id" to DimensionDocument(
                    default = RuleDocument(10, "SECOND"), // default → true
                    overrides = mapOf(
                        "vip" to RuleDocument(1000, "MINUTE", detailedMetric = true),
                        "anon" to RuleDocument(5, "SECOND"), // override → false
                    ),
                ),
                "ip" to DimensionDocument(RuleDocument(100, "SECOND", detailedMetric = false)), // default off
            ),
        )
        assertEquals(
            setOf(
                PolicyMeterKey(Dimension("user_id"), null),
                PolicyMeterKey(Dimension("user_id"), DimensionValue("vip")),
            ),
            doc.toSnapshot().detailedMeterKeys(),
        )
    }

    @Test
    fun `wraps an invalid override with its value name`() {
        val doc = PolicyDocument(
            1,
            mapOf(
                "user_id" to DimensionDocument(
                    RuleDocument(10, "SECOND"),
                    overrides = mapOf("bad" to RuleDocument(unit = "SECOND")),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalArgumentException> { doc.toSnapshot() }
        assertTrue(ex.message!!.contains("bad"))
    }
}
