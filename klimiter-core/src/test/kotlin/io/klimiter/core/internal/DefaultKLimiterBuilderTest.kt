package io.klimiter.core.internal

import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.spi.KeyGenerator
import io.klimiter.core.api.spi.RateLimitOperation
import io.klimiter.core.api.spi.RateLimitOperationFactory
import io.klimiter.core.api.spi.StaticRateLimitDomainRepository
import io.klimiter.core.api.spi.TimeProvider
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DefaultKLimiterBuilderTest {

    private val noopFactory = object : RateLimitOperationFactory {
        override fun create(request: RateLimitRequest): List<RateLimitOperation> = emptyList()
    }

    private fun minimalDomain(id: String = "api") = RateLimitDomain(
        id = id,
        descriptors = listOf(
            RateLimitDescriptor(
                key = "k",
                rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 10),
            ),
        ),
    )

    private fun minimalRepository(vararg ids: String = arrayOf("api")) =
        StaticRateLimitDomainRepository(ids.map { minimalDomain(it) })

    private fun minimalRequest() = RateLimitRequest(
        domain = "api",
        descriptors = listOf(RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("k", "v")))),
    )

    @Test
    fun `build with domainRepository succeeds`() {
        val limiter = KLimiterBuilder.create().domainRepository(minimalRepository()).build()
        assertNotNull(limiter)
    }

    @Test
    fun `build without domainRepository throws`() {
        assertFailsWith<IllegalStateException> { KLimiterBuilder.create().build() }
    }

    @Test
    fun `maxCacheSize zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { KLimiterBuilder.create().maxCacheSize(0) }
    }

    @Test
    fun `maxCacheSize negative is rejected`() {
        assertFailsWith<IllegalArgumentException> { KLimiterBuilder.create().maxCacheSize(-1) }
    }

    @Test
    fun `negative gracePeriod is rejected`() {
        assertFailsWith<IllegalArgumentException> { KLimiterBuilder.create().gracePeriod((-1).seconds) }
    }

    @Test
    fun `operationFactory combined with domainRepository throws at build`() {
        assertFailsWith<IllegalStateException> {
            KLimiterBuilder.create()
                .domainRepository(minimalRepository())
                .operationFactory(noopFactory)
                .build()
        }
    }

    @Test
    fun `operationFactory combined with maxCacheSize throws at build`() {
        assertFailsWith<IllegalStateException> {
            KLimiterBuilder.create()
                .maxCacheSize(100)
                .operationFactory(noopFactory)
                .build()
        }
    }

    @Test
    fun `operationFactory combined with non-default gracePeriod throws at build`() {
        assertFailsWith<IllegalStateException> {
            KLimiterBuilder.create()
                .gracePeriod(60.seconds)
                .operationFactory(noopFactory)
                .build()
        }
    }

    @Test
    fun `operationFactory combined with custom keyGenerator throws at build`() {
        val generator = object : KeyGenerator {
            override fun generate(
                domain: String,
                entries: List<RateLimitDescriptorEntry>,
                windowDivider: Long,
                timeProvider: TimeProvider,
            ) = "key"
        }
        assertFailsWith<IllegalStateException> {
            KLimiterBuilder.create()
                .keyGenerator(generator)
                .operationFactory(noopFactory)
                .build()
        }
    }

    @Test
    fun `custom keyGenerator is invoked on shouldRateLimit`() = runTest {
        val called = AtomicBoolean(false)
        val generator = object : KeyGenerator {
            override fun generate(
                domain: String,
                entries: List<RateLimitDescriptorEntry>,
                windowDivider: Long,
                timeProvider: TimeProvider,
            ): String {
                called.set(true)
                return "test-key"
            }
        }
        val limiter = KLimiterBuilder.create()
            .domainRepository(minimalRepository())
            .keyGenerator(generator)
            .build()
        limiter.shouldRateLimit(minimalRequest())
        assertTrue(called.get(), "Custom KeyGenerator was not called")
    }
}
