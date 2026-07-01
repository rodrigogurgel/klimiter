package io.github.rodrigogurgel.klimiter.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith

class ShedPropertiesTest {
    @Test
    fun `cpu rejects out-of-range threshold`() {
        assertFailsWith<IllegalArgumentException> { ShedProperties.Cpu(threshold = 1.5) }
        assertFailsWith<IllegalArgumentException> { ShedProperties.Cpu(threshold = -0.1) }
    }

    @Test
    fun `cpu rejects non-positive sample interval`() {
        assertFailsWith<IllegalArgumentException> { ShedProperties.Cpu(sampleInterval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ShedProperties.Cpu(sampleInterval = Duration.ofMillis(-1)) }
    }

    @Test
    fun `concurrency rejects initial outside min-max`() {
        assertFailsWith<IllegalArgumentException> {
            ShedProperties.Concurrency(minLimit = 10, initialLimit = 5, maxConcurrency = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            ShedProperties.Concurrency(minLimit = 10, initialLimit = 200, maxConcurrency = 100)
        }
    }

    @Test
    fun `concurrency rejects rtt tolerance below one`() {
        assertFailsWith<IllegalArgumentException> { ShedProperties.Concurrency(rttTolerance = 0.5) }
    }

    @Test
    fun `defaults are valid`() {
        ShedProperties() // não lança
    }
}
