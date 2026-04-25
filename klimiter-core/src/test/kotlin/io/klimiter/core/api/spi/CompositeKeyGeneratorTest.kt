package io.klimiter.core.api.spi

import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CompositeKeyGeneratorTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-04-24T12:00:00Z"))

    @Test
    fun `entries that ambiguate under underscore separator stay distinct`() {
        // Regression guard: the old `_` separator made these collide.
        val a = CompositeKeyGenerator.generate(
            domain = "api",
            entries = listOf(RateLimitDescriptorEntry("user_id", "42")),
            windowDivider = 60L,
            timeProvider = clock,
        )
        val b = CompositeKeyGenerator.generate(
            domain = "api",
            entries = listOf(RateLimitDescriptorEntry("user", "id_42")),
            windowDivider = 60L,
            timeProvider = clock,
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `same inputs within the same window produce the same key`() {
        val key = {
            CompositeKeyGenerator.generate(
                domain = "api",
                entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1")),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
        assertEquals(key(), key())
    }

    @Test
    fun `different windows produce different keys`() {
        val windowA = CompositeKeyGenerator.generate(
            domain = "api",
            entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1")),
            windowDivider = 60L,
            timeProvider = FixedTimeProvider(Instant.parse("2026-04-24T12:00:00Z")),
        )
        val windowB = CompositeKeyGenerator.generate(
            domain = "api",
            entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1")),
            windowDivider = 60L,
            timeProvider = FixedTimeProvider(Instant.parse("2026-04-24T12:01:00Z")),
        )
        assertNotEquals(windowA, windowB)
    }

    @Test
    fun `separator in entry key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeKeyGenerator.generate(
                domain = "api",
                entries = listOf(RateLimitDescriptorEntry("user|id", "42")),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
    }

    @Test
    fun `separator in entry value is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeKeyGenerator.generate(
                domain = "api",
                entries = listOf(RateLimitDescriptorEntry("user_id", "a|b")),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
    }

    @Test
    fun `separator in domain is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeKeyGenerator.generate(
                domain = "ap|i",
                entries = listOf(RateLimitDescriptorEntry("user_id", "42")),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
    }

    @Test
    fun `blank domain is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeKeyGenerator.generate(
                domain = "  ",
                entries = listOf(RateLimitDescriptorEntry("k", "v")),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
    }

    @Test
    fun `empty entries are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeKeyGenerator.generate(
                domain = "api",
                entries = emptyList(),
                windowDivider = 60L,
                timeProvider = clock,
            )
        }
    }

    private class FixedTimeProvider(private val fixed: Instant) : TimeProvider {
        override fun now(): Instant = fixed
    }
}
