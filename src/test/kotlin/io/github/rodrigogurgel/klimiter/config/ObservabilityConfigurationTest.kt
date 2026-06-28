package io.github.rodrigogurgel.klimiter.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityConfigurationTest {
    @Test
    fun `non-grpc observations always pass regardless of rate`() {
        assertTrue(shouldObserve(isGrpcServer = false, sampleRate = 0.0) { 0.0 })
    }

    @Test
    fun `rate 1 observes every grpc call`() {
        assertTrue(shouldObserve(isGrpcServer = true, sampleRate = 1.0) { 0.999 })
    }

    @Test
    fun `rate 0 drops every grpc observation`() {
        assertFalse(shouldObserve(isGrpcServer = true, sampleRate = 0.0) { 0.0 })
    }

    @Test
    fun `intermediate rate samples by the random draw`() {
        assertTrue(shouldObserve(isGrpcServer = true, sampleRate = 0.5) { 0.49 }) // sorteio < taxa → observa
        assertFalse(shouldObserve(isGrpcServer = true, sampleRate = 0.5) { 0.50 }) // sorteio >= taxa → descarta
    }

    @Test
    fun `properties reject out-of-range rate`() {
        assertFailsWith<IllegalArgumentException> { ObservabilityProperties(grpcSampleRate = 1.5) }
        assertFailsWith<IllegalArgumentException> { ObservabilityProperties(grpcSampleRate = -0.1) }
    }
}
