package io.klimiter.klimiterservice.config

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterFactory
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.api.spi.StaticRateLimitDomainRepository
import io.klimiter.redis.api.RedisKLimiterConfig
import io.klimiter.redis.api.RedisKLimiterFactory
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
        val repository = StaticRateLimitDomainRepository(listOf(domain))
        return when (properties.mode) {
            KLimiterProperties.Mode.LOCAL ->
                KLimiterFactory.inMemory(repository)

            KLimiterProperties.Mode.STANDALONE ->
                RedisKLimiterFactory.standalone(
                    uri = properties.redis.uris.first(),
                    domainRepository = repository,
                    config = redisConfig,
                )

            KLimiterProperties.Mode.CLUSTER ->
                RedisKLimiterFactory.cluster(
                    uris = properties.redis.uris,
                    domainRepository = repository,
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
