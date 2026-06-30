package io.github.rodrigogurgel.klimiter.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdmissionPropertiesTest {
    @Test
    fun `defaults to off`() {
        val props = AdmissionProperties()
        assertEquals(AdmissionMode.OFF, props.mode)
        assertEquals(0, props.maxInflight)
    }

    @Test
    fun `fixed mode requires a positive max-inflight`() {
        assertFailsWith<IllegalArgumentException> { AdmissionProperties(mode = AdmissionMode.FIXED) } // 0 default
        // válido com teto positivo
        assertEquals(50, AdmissionProperties(mode = AdmissionMode.FIXED, maxInflight = 50).maxInflight)
    }

    @Test
    fun `rejects negative max-inflight`() {
        assertFailsWith<IllegalArgumentException> { AdmissionProperties(maxInflight = -1) }
    }

    @Test
    fun `adaptive defaults are valid`() {
        val a = AdmissionProperties(mode = AdmissionMode.ADAPTIVE).adaptive
        assertEquals(50, a.initialLimit)
        assertEquals(4, a.minLimit)
        assertEquals(1000, a.maxLimit)
    }

    @Test
    fun `adaptive rejects invalid bounds and window`() {
        assertFailsWith<IllegalArgumentException> { AdmissionProperties.Adaptive(minLimit = 0) }
        assertFailsWith<IllegalArgumentException> { AdmissionProperties.Adaptive(minLimit = 10, maxLimit = 5) }
        assertFailsWith<IllegalArgumentException> { AdmissionProperties.Adaptive(initialLimit = 5000, maxLimit = 1000) }
        assertFailsWith<IllegalArgumentException> { AdmissionProperties.Adaptive(window = Duration.ZERO) }
    }
}
