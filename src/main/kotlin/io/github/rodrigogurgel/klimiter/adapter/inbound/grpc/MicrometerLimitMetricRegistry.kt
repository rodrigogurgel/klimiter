package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import com.netflix.concurrency.limits.MetricRegistry
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.function.Supplier

/**
 * Ponte da SPI de métricas do concurrency-limits (Netflix) para o Micrometer: expõe o teto atual, o
 * RTT observado e os cortes do limitador adaptativo como meters `klimiter.shed.concurrency.*`,
 * integrando-os ao mesmo pipeline OTLP do resto do klimiter (OBSERVABILIDADE.md).
 *
 * Os três pontos de extensão da SPI ([gauge], [distribution], [counter]) são mapeados para meters
 * Micrometer; os métodos `register*` deprecados delegam a estes por default na interface.
 */
class MicrometerLimitMetricRegistry(private val registry: MeterRegistry) : MetricRegistry {
    override fun gauge(id: String, supplier: Supplier<Number>, vararg tagNameValuePairs: String) {
        Gauge.builder(meterName(id), supplier) { it.get().toDouble() }
            .tags(tags(tagNameValuePairs))
            .register(registry)
    }

    override fun distribution(id: String, vararg tagNameValuePairs: String): MetricRegistry.SampleListener {
        val summary = DistributionSummary.builder(meterName(id)).tags(tags(tagNameValuePairs)).register(registry)
        return MetricRegistry.SampleListener { value -> summary.record(value.toDouble()) }
    }

    override fun counter(id: String, vararg tagNameValuePairs: String): MetricRegistry.Counter {
        val counter = registry.counter(meterName(id), tags(tagNameValuePairs))
        return MetricRegistry.Counter { counter.increment() }
    }

    private fun tags(tagNameValuePairs: Array<out String>): Tags = Tags.of(*tagNameValuePairs)

    private companion object {
        const val PREFIX = "klimiter.shed.concurrency."

        fun meterName(id: String): String = "$PREFIX$id"
    }
}
