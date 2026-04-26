package io.klimiter.core.api

import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.spi.KeyGenerator
import io.klimiter.core.spi.StaticRateLimitDomainRepository
import io.klimiter.core.spi.TimeProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KLimiterFactoryTest {

    private val domain = RateLimitDomain(
        id = "api",
        descriptors = listOf(
            RateLimitDescriptor(
                key = "user",
                rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 5),
            ),
        ),
    )
    private val repo = StaticRateLimitDomainRepository(listOf(domain))
    private val request = RateLimitRequest(
        domain = "api",
        descriptors = listOf(RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("user", "alice")))),
    )

    @Test
    fun `inMemory with only repository returns a working limiter`() = runTest {
        val limiter = KLimiterFactory.inMemory(repo)
        assertNotNull(limiter)
        assertEquals(RateLimitCode.OK, limiter.shouldRateLimit(request).overallCode)
    }

    @Test
    fun `inMemory with maxCacheSize returns a working limiter`() = runTest {
        val limiter = KLimiterFactory.inMemory(repo, maxCacheSize = 100)
        assertEquals(RateLimitCode.OK, limiter.shouldRateLimit(request).overallCode)
    }

    @Test
    fun `inMemory with gracePeriod returns a working limiter`() = runTest {
        val limiter = KLimiterFactory.inMemory(repo, gracePeriod = 10.seconds)
        assertEquals(RateLimitCode.OK, limiter.shouldRateLimit(request).overallCode)
    }

    @Test
    fun `inMemory with custom keyGenerator invokes it`() = runTest {
        var invoked = false
        val generator = object : KeyGenerator {
            override fun generate(
                domain: String,
                entries: List<RateLimitDescriptorEntry>,
                windowDivider: Long,
                timeProvider: TimeProvider,
            ): String {
                invoked = true
                return "custom-key"
            }
        }
        KLimiterFactory.inMemory(repo, keyGenerator = generator).shouldRateLimit(request)
        assertTrue(invoked)
    }
}
