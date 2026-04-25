package io.klimiter.core.internal

import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.spi.StaticRateLimitDomainRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultKLimiterTest {

    private fun buildLimiter(limit: Int = 5) = KLimiterBuilder.create()
        .domainRepository(
            StaticRateLimitDomainRepository(
                listOf(
                    RateLimitDomain(
                        id = "api",
                        descriptors = listOf(
                            RateLimitDescriptor(
                                key = "user_id",
                                rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = limit),
                            ),
                        ),
                    ),
                ),
            ),
        )
        .build()

    private fun request(key: String = "user_id", value: String = "alice", domain: String = "api") = RateLimitRequest(
        domain = domain,
        descriptors = listOf(RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry(key, value)))),
    )

    @Test
    fun `request within limit returns OK`() = runTest {
        val response = buildLimiter().shouldRateLimit(request())
        assertEquals(RateLimitCode.OK, response.overallCode)
    }

    @Test
    fun `request beyond limit returns OVER_LIMIT`() = runTest {
        val limiter = buildLimiter(limit = 5)
        repeat(5) { limiter.shouldRateLimit(request()) }
        val response = limiter.shouldRateLimit(request())
        assertEquals(RateLimitCode.OVER_LIMIT, response.overallCode)
    }

    @Test
    fun `request to unknown domain is allowed`() = runTest {
        val response = buildLimiter().shouldRateLimit(request(domain = "unknown"))
        assertEquals(RateLimitCode.OK, response.overallCode)
    }

    @Test
    fun `statuses list reflects all matched descriptors`() = runTest {
        val limiter = KLimiterBuilder.create()
            .domainRepository(
                StaticRateLimitDomainRepository(
                    listOf(
                        RateLimitDomain(
                            id = "api",
                            descriptors = listOf(
                                RateLimitDescriptor(
                                    key = "user_id",
                                    rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 10),
                                ),
                                RateLimitDescriptor(
                                    key = "ip",
                                    rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 100),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .build()
        val twoDescriptorRequest = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("user_id", "alice"))),
                RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1"))),
            ),
        )
        val response = limiter.shouldRateLimit(twoDescriptorRequest)
        assertEquals(RateLimitCode.OK, response.overallCode)
        assertEquals(2, response.statuses.size)
    }

    @Test
    fun `partial failure rolls back the first descriptor`() = runTest {
        val limiter = KLimiterBuilder.create()
            .domainRepository(
                StaticRateLimitDomainRepository(
                    listOf(
                        RateLimitDomain(
                            id = "api",
                            descriptors = listOf(
                                RateLimitDescriptor(
                                    key = "user_id",
                                    rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 10),
                                ),
                                RateLimitDescriptor(
                                    key = "ip",
                                    rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 1),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .build()

        val ipDescriptor = RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1")))
        val singleIpRequest = RateLimitRequest(domain = "api", descriptors = listOf(ipDescriptor))

        limiter.shouldRateLimit(singleIpRequest)

        val twoDescriptorRequest = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("user_id", "alice"))),
                ipDescriptor,
            ),
        )
        val failedResponse = limiter.shouldRateLimit(twoDescriptorRequest)
        assertEquals(RateLimitCode.OVER_LIMIT, failedResponse.overallCode)

        val userOnlyRequest = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("user_id", "alice"))),
            ),
        )
        val soloResponse = limiter.shouldRateLimit(userOnlyRequest)
        assertEquals(RateLimitCode.OK, soloResponse.overallCode)
        assertEquals(9, soloResponse.statuses.single().limitRemaining)
    }
}
