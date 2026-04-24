package io.klimiter.core.demo

import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.common.RateLimitTimeUnit
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.klimiter.core.api.rls.RateLimitDescriptor as RlsDescriptor

fun main(): Unit = runBlocking {
    val domain = RateLimitDomain(
        id = "api",
        descriptors = listOf(
            RateLimitDescriptor(
                key = "user_id",
                rule = RateLimitRule(
                    unit = RateLimitTimeUnit.SECOND,
                    requestsPerUnit = 5,
                )
            )
        )
    )

    val limiter = KLimiterBuilder.create()
        .addDomain(domain)
        .build()

    val request = RateLimitRequest(
        domain = "api",
        descriptors = listOf(
            RlsDescriptor(entries = listOf(RateLimitDescriptorEntry("user_id", "rodrigo"))),
        )
    )

    repeat(6) { i ->
        val response = limiter.shouldRateLimit(request)
        println("#${i + 1} -> ${response.overallCode} | statuses=${response.statuses}")
    }
    delay(1000)
    repeat(6) { i ->
        val response = limiter.shouldRateLimit(request)
        println("#${i + 1} -> ${response.overallCode} | statuses=${response.statuses}")
    }
}
