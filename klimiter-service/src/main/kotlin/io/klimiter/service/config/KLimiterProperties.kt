package io.klimiter.service.config

import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.redis.api.RedisKLimiterConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "klimiter")
data class KLimiterProperties(
    val backend: BackendProperties = BackendProperties(),
    val domains: List<DomainProperties> = emptyList(),
) {
    enum class BackendMode { IN_MEMORY, REDIS_STANDALONE, REDIS_CLUSTER }

    data class BackendProperties(
        val mode: BackendMode = BackendMode.IN_MEMORY,
        val redis: RedisProperties = RedisProperties(),
    )

    data class RedisProperties(
        val uri: String = "SET REDIS CONNECTION URI",
        val uris: List<String> = listOf("SET REDIS CONNECTION URIS"),
        val leasePercentage: Int = RedisKLimiterConfig.DEFAULT_LEASE_PERCENTAGE,
        val keyPrefix: String = RedisKLimiterConfig.DEFAULT_KEY_PREFIX,
    )

    data class DomainProperties(val id: String, val descriptors: List<DescriptorProperties> = emptyList())

    data class DescriptorProperties(
        val key: String,
        val value: String? = null,
        val rule: RuleProperties? = null,
        val children: List<DescriptorProperties> = emptyList(),
    )

    data class RuleProperties(
        val unit: RateLimitTimeUnit,
        val requestsPerUnit: Int,
        val name: String? = null,
        val unlimited: Boolean = false,
    )
}
