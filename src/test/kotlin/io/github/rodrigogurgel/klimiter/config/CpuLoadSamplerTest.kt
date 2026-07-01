package io.github.rodrigogurgel.klimiter.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CpuLoadSamplerTest {
    /** cpu-nanos e relógio controlados por listas: cada `sample()` consome o próximo valor. */
    private fun sampler(cpu: List<Long>, clock: List<Long>): CpuLoadSampler {
        val c = cpu.iterator()
        val t = clock.iterator()
        return CpuLoadSampler(intervalMillis = 1000, cores = 2, processCpuNanos = c::next, nanoTime = t::next)
    }

    @Test
    fun `derives fraction from cpu-time delta over cores`() {
        val s = sampler(cpu = listOf(0L, 1_000_000_000L), clock = listOf(0L, 1_000_000_000L))
        s.sample() // linha de base
        s.sample() // 1s de CPU / 1s de relógio / 2 cores = 0.5
        assertEquals(0.5, s.invoke(), 1e-9)
    }

    @Test
    fun `first sample stays fail-open`() {
        val s = sampler(cpu = listOf(0L), clock = listOf(0L))
        s.sample()
        assertEquals(-1.0, s.invoke()) // uma amostra só: sem delta → nunca corta
    }

    @Test
    fun `clamps saturation to one`() {
        val s = sampler(cpu = listOf(0L, 5_000_000_000L), clock = listOf(0L, 1_000_000_000L))
        s.sample()
        s.sample() // 5 CPU-s / 1s / 2 cores = 2.5 → clamp 1.0
        assertEquals(1.0, s.invoke())
    }
}
