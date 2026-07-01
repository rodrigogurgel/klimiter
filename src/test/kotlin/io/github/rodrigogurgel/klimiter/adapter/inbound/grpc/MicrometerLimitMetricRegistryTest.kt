package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals

class MicrometerLimitMetricRegistryTest {
    private val registry = SimpleMeterRegistry()
    private val bridge = MicrometerLimitMetricRegistry(registry)

    @Test
    fun `counter increments a prefixed micrometer counter with tags`() {
        val counter = bridge.counter("dropped", "partition", "default")
        counter.increment()
        counter.increment()

        val meter = registry.get("klimiter.shed.concurrency.dropped").tag("partition", "default").counter()
        assertEquals(2.0, meter.count())
    }

    @Test
    fun `distribution records samples into a summary`() {
        val listener = bridge.distribution("rtt")
        listener.addSample(10)
        listener.addSample(20)

        val summary = registry.get("klimiter.shed.concurrency.rtt").summary()
        assertEquals(2, summary.count())
        assertEquals(30.0, summary.totalAmount())
    }

    @Test
    fun `gauge reflects the supplier`() {
        val value = AtomicInteger(7)
        bridge.gauge("limit", Supplier { value.get() })

        val gauge = registry.get("klimiter.shed.concurrency.limit").gauge()
        assertEquals(7.0, gauge.value())
        value.set(12)
        assertEquals(12.0, gauge.value()) // gauge lê o supplier vivo
    }
}
