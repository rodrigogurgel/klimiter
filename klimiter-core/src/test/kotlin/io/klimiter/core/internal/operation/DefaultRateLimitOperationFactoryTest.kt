package io.klimiter.core.internal.operation

import io.klimiter.core.FixedTimeProvider
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitOverride
import io.klimiter.core.api.rls.RateLimitRequest
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.spi.CompositeKeyGenerator
import io.klimiter.core.api.spi.StaticRateLimitDomainRepository
import io.klimiter.core.internal.infra.store.InMemoryRateLimitStore
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultRateLimitOperationFactoryTest {

    private val clock = FixedTimeProvider(Instant.parse("2026-04-24T12:00:00Z"))
    private val rule = RateLimitRule(unit = RateLimitTimeUnit.MINUTE, requestsPerUnit = 100)
    private val domain = RateLimitDomain(
        id = "api",
        descriptors = listOf(RateLimitDescriptor(key = "user_id", rule = rule)),
    )

    private fun buildFactory(
        domainRepository: StaticRateLimitDomainRepository = StaticRateLimitDomainRepository(listOf(domain)),
    ) = DefaultRateLimitOperationFactory(
        domainRepository = domainRepository,
        store = InMemoryRateLimitStore(),
        keyGenerator = CompositeKeyGenerator,
        timeProvider = clock,
    )

    private fun request(vararg descriptors: RateLimitRequestDescriptor, domain: String = "api") = RateLimitRequest(
        domain = domain,
        descriptors = descriptors.toList(),
    )

    private fun descriptor(key: String = "user_id", value: String = "alice") =
        RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry(key, value)))

    @Test
    fun `unknown domain returns empty list`() {
        val ops = buildFactory().create(request(descriptor(), domain = "unknown"))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `matching rule returns one operation`() {
        val ops = buildFactory().create(request(descriptor()))
        assertEquals(1, ops.size)
    }

    @Test
    fun `whitelisted descriptor returns empty list`() {
        val whitelistedDomain = RateLimitDomain(
            id = "api",
            descriptors = listOf(RateLimitDescriptor(key = "user_id")),
        )
        val ops = buildFactory(StaticRateLimitDomainRepository(listOf(whitelistedDomain))).create(request(descriptor()))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `unlimited rule returns empty list`() {
        val unlimitedDomain = RateLimitDomain(
            id = "api",
            descriptors = listOf(
                RateLimitDescriptor(
                    key = "user_id",
                    rule = RateLimitRule(unit = RateLimitTimeUnit.MINUTE, requestsPerUnit = 0, unlimited = true),
                ),
            ),
        )
        val ops = buildFactory(StaticRateLimitDomainRepository(listOf(unlimitedDomain))).create(request(descriptor()))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `two descriptors produce two operations`() {
        val twoDescriptorDomain = RateLimitDomain(
            id = "api",
            descriptors = listOf(
                RateLimitDescriptor(key = "user_id", rule = rule),
                RateLimitDescriptor(key = "ip", rule = rule),
            ),
        )
        val req = request(
            RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("user_id", "alice"))),
            RateLimitRequestDescriptor(entries = listOf(RateLimitDescriptorEntry("ip", "10.0.0.1"))),
            domain = "api",
        )
        val ops = buildFactory(StaticRateLimitDomainRepository(listOf(twoDescriptorDomain))).create(req)
        assertEquals(2, ops.size)
    }

    @Test
    fun `same key within same window reuses shared counter`() = runTest {
        val factory = buildFactory()
        val req = request(descriptor())
        val op1 = factory.create(req).single()
        val op2 = factory.create(req).single()
        op1.execute()
        val status = op2.execute()
        assertEquals(98, status.limitRemaining)
    }

    @Test
    fun `per-descriptor limit override is applied`() = runTest {
        val req = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(
                    entries = listOf(RateLimitDescriptorEntry("user_id", "alice")),
                    limit = RateLimitOverride(requestsPerUnit = 10, unit = RateLimitTimeUnit.SECOND),
                ),
            ),
        )
        val status = buildFactory().create(req).single().execute()
        assertEquals(RateLimitCode.OK, status.code)
        assertEquals(9, status.limitRemaining)
        assertEquals(10, status.currentLimit!!.requestsPerUnit)
    }

    @Test
    fun `hitsAddend from descriptor overrides request-level addend`() = runTest {
        val req = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(
                    entries = listOf(RateLimitDescriptorEntry("user_id", "alice")),
                    hitsAddend = 5L,
                ),
            ),
            hitsAddend = 1,
        )
        val status = buildFactory().create(req).single().execute()
        assertEquals(95, status.limitRemaining)
    }

    @Test
    fun `isNegativeHits inverts the addend`() = runTest {
        val factory = buildFactory()
        val req = request(descriptor())
        factory.create(req).single().execute()
        factory.create(req).single().execute()

        val refundReq = RateLimitRequest(
            domain = "api",
            descriptors = listOf(
                RateLimitRequestDescriptor(
                    entries = listOf(RateLimitDescriptorEntry("user_id", "alice")),
                    hitsAddend = 2L,
                    isNegativeHits = true,
                ),
            ),
        )
        val status = factory.create(refundReq).single().execute()
        assertEquals(RateLimitCode.OK, status.code)
        assertEquals(100, status.limitRemaining)
    }
}
