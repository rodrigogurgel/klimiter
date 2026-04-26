package io.klimiter.service.adapter.output.klimiter

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.rls.RateLimitResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KLimiterCoreAdapterTest {

    private val request = RateLimitRequest(
        domain = "api",
        descriptors = listOf(RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("k", "v")))),
    )

    @Test
    fun `enforce returns limiter response on success`() = runTest {
        val expected = RateLimitResponse(RateLimitCode.OK)
        val limiter = object : KLimiter {
            override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse = expected
        }
        assertEquals(expected, KLimiterCoreAdapter(limiter).enforce(request))
    }

    @Test
    fun `enforce returns UNKNOWN response when limiter throws`() = runTest {
        data class UnknownRuntimeException(override val message: String) : RuntimeException(message)
        val limiter = object : KLimiter {
            override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse =
                throw UnknownRuntimeException("simulated failure")
        }
        val response = KLimiterCoreAdapter(limiter).enforce(request)
        assertEquals(RateLimitCode.UNKNOWN, response.overallCode)
    }
}
