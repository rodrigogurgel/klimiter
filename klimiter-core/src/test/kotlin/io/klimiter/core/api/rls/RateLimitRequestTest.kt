package io.klimiter.core.api.rls

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RateLimitRequestTest {

    private fun descriptor() = RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("k", "v")))

    @Test
    fun `blank domain is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimitRequest(domain = "  ", descriptors = listOf(descriptor()))
        }
    }

    @Test
    fun `empty descriptors list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimitRequest(domain = "api", descriptors = emptyList())
        }
    }
}
