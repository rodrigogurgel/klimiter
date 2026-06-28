package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PolicySnapshotTest {
    private val default = Policy(Capacity(1000), RateLimitUnit.MINUTE)
    private val override = Policy(Capacity(5000), RateLimitUnit.MINUTE, Prefetch.Count(200))

    private val snapshot = PolicySnapshot(
        mapOf(
            Dimension("user_id") to DimensionPolicy(
                default = default,
                overrides = mapOf(DimensionValue("user-42") to override),
            ),
        ),
    )

    @Test
    fun `missing dimension resolves to pass-through`() {
        val resolution = snapshot.resolve(Dimension("ip"), DimensionValue("10.0.0.1"))
        assertEquals(PolicyResolution.PassThrough, resolution)
    }

    @Test
    fun `value without override uses dimension default`() {
        val resolution = snapshot.resolve(Dimension("user_id"), DimensionValue("user-7"))
        assertEquals(default, assertIs<PolicyResolution.Matched>(resolution).policy)
    }

    @Test
    fun `value with override takes precedence over default`() {
        val resolution = snapshot.resolve(Dimension("user_id"), DimensionValue("user-42"))
        assertEquals(override, assertIs<PolicyResolution.Matched>(resolution).policy)
    }

    @Test
    fun `resolve exposes the matched override value and null on the default`() {
        val onOverride = snapshot.resolve(Dimension("user_id"), DimensionValue("user-42"))
        assertEquals(DimensionValue("user-42"), assertIs<PolicyResolution.Matched>(onOverride).override)
        val onDefault = snapshot.resolve(Dimension("user_id"), DimensionValue("user-7"))
        assertEquals(null, assertIs<PolicyResolution.Matched>(onDefault).override)
    }

    @Test
    fun `detailedMeterKeys lists the enabled default and overrides only`() {
        val keys = PolicySnapshot(
            mapOf(
                Dimension("user_id") to DimensionPolicy(
                    default = default, // detailedMetric true
                    overrides = mapOf(
                        DimensionValue(
                            "user-42",
                        ) to Policy(Capacity(5000), RateLimitUnit.MINUTE, detailedMetric = true),
                        DimensionValue("anon") to Policy(Capacity(5), RateLimitUnit.MINUTE, detailedMetric = false),
                    ),
                ),
            ),
        ).detailedMeterKeys()
        assertEquals(
            setOf(
                PolicyMeterKey(Dimension("user_id"), null),
                PolicyMeterKey(Dimension("user_id"), DimensionValue("user-42")),
            ),
            keys,
        )
    }

    @Test
    fun `empty snapshot is always pass-through`() {
        assertEquals(0, PolicySnapshot.EMPTY.size)
        assertEquals(
            PolicyResolution.PassThrough,
            PolicySnapshot.EMPTY.resolve(Dimension("user_id"), DimensionValue("x")),
        )
    }

    @Test
    fun `prefetch percent resolves to floor of capacity percentage`() {
        assertEquals(100, Prefetch.Percent(10).units(Capacity(1000)))
        assertEquals(0, Prefetch.None.units(Capacity(1000)))
        assertEquals(200, Prefetch.Count(200).units(Capacity(1000)))
    }

    @Test
    fun `value objects reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { Capacity(0) }
        assertFailsWith<IllegalArgumentException> { Prefetch.Percent(0) }
        assertFailsWith<IllegalArgumentException> { Prefetch.Percent(101) }
        assertFailsWith<IllegalArgumentException> { Dimension(" ") }
        assertFailsWith<IllegalArgumentException> { DimensionValue("") }
    }
}
