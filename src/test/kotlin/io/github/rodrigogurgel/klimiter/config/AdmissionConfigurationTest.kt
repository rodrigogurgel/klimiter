package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.ConcurrencyLimitInterceptor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdmissionConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(TestConfig::class.java))
        .withBean(SimpleMeterRegistry::class.java)

    @Test
    fun `interceptor is absent when mode is off (default)`() {
        runner.run { context ->
            assertTrue(context.getBeansOfType(ConcurrencyLimitInterceptor::class.java).isEmpty())
        }
    }

    @Test
    fun `interceptor is registered in fixed mode`() {
        runner.withPropertyValues("klimiter.admission.mode=fixed", "klimiter.admission.max-inflight=50")
            .run { context ->
                assertEquals(1, context.getBeansOfType(ConcurrencyLimitInterceptor::class.java).size)
            }
    }

    @Test
    fun `interceptor is registered in adaptive mode`() {
        runner.withPropertyValues("klimiter.admission.mode=adaptive").run { context ->
            assertEquals(1, context.getBeansOfType(ConcurrencyLimitInterceptor::class.java).size)
        }
    }

    @EnableConfigurationProperties(AdmissionProperties::class)
    @Import(AdmissionConfiguration::class)
    private class TestConfig
}
