package io.klimiter.klimiterservice.config

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.redis.api.KLimiters
import io.klimiter.redis.api.RedisKLimiterConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KLimiterProperties::class)
class KLimiterConfiguration {

    @Bean
    fun kLimiter(properties: KLimiterProperties): KLimiter {
        val domain = buildDomain(properties)
        val redisConfig = buildRedisConfig(properties)
        return when (properties.mode) {
            KLimiterProperties.Mode.LOCAL ->
                KLimiters.inMemory(listOf(domain))

            KLimiterProperties.Mode.STANDALONE ->
                KLimiters.standalone(
                    uri = properties.redis.uris.first(),
                    domains = listOf(domain),
                    config = redisConfig,
                )

            KLimiterProperties.Mode.CLUSTER ->
                KLimiters.cluster(
                    uris = properties.redis.uris,
                    domains = listOf(domain),
                    config = redisConfig,
                )
        }
    }

    private fun buildDomain(properties: KLimiterProperties) = RateLimitDomain(
        id = properties.domainId,
        descriptors = listOf(
            RateLimitDescriptor(
                key = properties.key,
                rule = RateLimitRule(
                    unit = properties.default.unit,
                    requestsPerUnit = properties.default.limit,
                ),
            ),
        ),
    )

    private fun buildRedisConfig(properties: KLimiterProperties) = RedisKLimiterConfig(
        keyPrefix = properties.redis.keyPrefix,
        leasePercentage = properties.redis.leasePercentage,
    )
}
