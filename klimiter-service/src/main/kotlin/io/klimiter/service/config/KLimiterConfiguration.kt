package io.klimiter.service.config

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterFactory
import io.klimiter.core.api.config.RateLimitDescriptor
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.core.api.config.RateLimitRule
import io.klimiter.core.spi.RateLimitDomainRepository
import io.klimiter.core.spi.StaticRateLimitDomainRepository
import io.klimiter.redis.RedisKLimiterFactory
import io.klimiter.redis.api.RedisKLimiterConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(KLimiterProperties::class)
class KLimiterConfiguration {

    @Bean
    fun rateLimitDomainRepository(properties: KLimiterProperties): RateLimitDomainRepository {
        val domains = properties.domains.map { it.toDomain() }
        logger.info("Rate-limit domain repository loaded domains={}", domains.map { it.id })
        return StaticRateLimitDomainRepository(domains)
    }

    @Bean
    fun kLimiter(domainRepository: RateLimitDomainRepository, properties: KLimiterProperties): KLimiter {
        val redis = properties.backend.redis
        val redisConfig = RedisKLimiterConfig(
            leasePercentage = redis.leasePercentage,
            keyPrefix = redis.keyPrefix,
        )
        logger.info("Configuring KLimiter backend mode={}", properties.backend.mode)
        return when (properties.backend.mode) {
            KLimiterProperties.BackendMode.IN_MEMORY ->
                KLimiterFactory.inMemory(domainRepository)

            KLimiterProperties.BackendMode.REDIS_STANDALONE -> {
                logger.info("Connecting to Redis standalone uri={}", redis.uri)
                RedisKLimiterFactory.standalone(
                    uri = redis.uri,
                    domainRepository = domainRepository,
                    config = redisConfig,
                )
            }

            KLimiterProperties.BackendMode.REDIS_CLUSTER -> {
                logger.info("Connecting to Redis cluster seeds={}", redis.uris)
                RedisKLimiterFactory.cluster(
                    uris = redis.uris,
                    domainRepository = domainRepository,
                    config = redisConfig,
                )
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(KLimiterConfiguration::class.java)
    }
}

private fun KLimiterProperties.DomainProperties.toDomain(): RateLimitDomain =
    RateLimitDomain(id = id, descriptors = descriptors.map { it.toDescriptor() })

private fun KLimiterProperties.DescriptorProperties.toDescriptor(): RateLimitDescriptor = RateLimitDescriptor(
    key = key,
    value = value,
    rule = rule?.toRule(),
    children = children.map { it.toDescriptor() },
)

private fun KLimiterProperties.RuleProperties.toRule(): RateLimitRule = RateLimitRule(
    unit = unit,
    requestsPerUnit = requestsPerUnit,
    name = name,
    unlimited = unlimited,
)
