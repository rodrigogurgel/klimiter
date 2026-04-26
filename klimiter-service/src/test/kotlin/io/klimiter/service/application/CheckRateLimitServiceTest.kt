package io.klimiter.service.application

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.rls.RateLimitResponse
import io.klimiter.service.domain.port.output.RateLimitEnforcerPort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckRateLimitServiceTest {

    private val request = RateLimitRequest(
        domain = "api",
        descriptors = listOf(RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("k", "v")))),
    )

    @Test
    fun `check delegates to enforcer and returns its response`() = runTest {
        val expected = RateLimitResponse(RateLimitCode.OK)
        val enforcer = object : RateLimitEnforcerPort {
            override suspend fun enforce(request: RateLimitRequest): RateLimitResponse = expected
        }
        val response = CheckRateLimitService(enforcer).check(request)
        assertEquals(expected, response)
    }
}
