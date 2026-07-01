package io.github.rodrigogurgel.klimiter.config

import com.netflix.concurrency.limits.grpc.server.ConcurrencyLimitServerInterceptor
import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.CpuShedServerInterceptor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShedConfigurationTest {
    private val configuration = ShedConfiguration()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `cpu interceptor is wired as a cpu shed interceptor`() {
        val interceptor = configuration.cpuShedServerInterceptor(
            ShedProperties(),
            CpuLoadSampler(intervalMillis = 250),
            registry,
        )
        assertTrue(interceptor is CpuShedServerInterceptor)
    }

    @Test
    fun `concurrency interceptor builds the adaptive limiter without partitions`() {
        // Exercita o caminho GrpcServerLimiterBuilder sem partições → SimpleLimiter em runtime.
        val interceptor = configuration.concurrencyLimitServerInterceptor(ShedProperties(), registry)
        assertTrue(interceptor is ConcurrencyLimitServerInterceptor)
    }

    @Test
    fun `cpu load sampler exposes the last reading`() {
        val sampler = configuration.cpuLoadSampler(ShedProperties(), registry)
        assertNotNull(sampler.invoke()) // sem start(): -1 (fail open), nunca nulo
    }
}
