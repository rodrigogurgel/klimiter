package io.klimiter.core.api.rls

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RateLimitRequestDescriptorTest {

    @Test
    fun `empty entries list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimitRequestDescriptor(entries = emptyList())
        }
    }
}
