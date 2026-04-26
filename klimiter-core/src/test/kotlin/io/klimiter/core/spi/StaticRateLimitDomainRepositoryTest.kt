package io.klimiter.core.spi

import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.config.RateLimitTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StaticRateLimitDomainRepositoryTest {

    private fun domain(id: String) = RateLimitDomain(
        id = id,
        descriptors = listOf(
            RateLimitDescriptor(
                key = "k",
                rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 10),
            ),
        ),
    )

    @Test
    fun `findById returns the domain when present`() {
        val d = domain("api")
        val repo = StaticRateLimitDomainRepository(listOf(d))
        assertEquals(d, repo.findById("api"))
    }

    @Test
    fun `findById returns null when domain is absent`() {
        val repo = StaticRateLimitDomainRepository(listOf(domain("api")))
        assertNull(repo.findById("unknown"))
    }

    @Test
    fun `duplicate domain id throws at construction`() {
        assertFailsWith<IllegalArgumentException> {
            StaticRateLimitDomainRepository(listOf(domain("api"), domain("api")))
        }
    }
}
