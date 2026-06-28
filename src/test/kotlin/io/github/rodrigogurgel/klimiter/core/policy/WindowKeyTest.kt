package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WindowKeyTest {
    private val policy = Policy(Capacity(1000), RateLimitUnit.MINUTE)

    @Test
    fun `window aligns to the unit boundary`() {
        val window = WindowKey.window(policy, nowEpochSecond = 1_700_000_042)
        assertEquals(1_700_000_040, window.startEpochSecond)
    }

    @Test
    fun `key is prefix dimension value and window start`() {
        val window = WindowKey.window(policy, nowEpochSecond = 1_700_000_042)
        val key = WindowKey.key("klimiter", Dimension("user_id"), DimensionValue("user-42"), window)
        assertEquals("klimiter:user_id:user-42:1700000040", key)
    }
}
