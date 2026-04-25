package io.klimiter.klimiterservice.config

import io.klimiter.core.api.common.RateLimitTimeUnit
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "klimiter")
data class KLimiterProperties(
    val domainId: String = "default",
    val key: String = "user_id",
    val mode: Mode = Mode.LOCAL,
    val default: DefaultRule = DefaultRule(),
    val redis: RedisConfig = RedisConfig(),
) {
    enum class Mode { LOCAL, STANDALONE, CLUSTER }

    data class DefaultRule(val limit: Int = 60, val unit: RateLimitTimeUnit = RateLimitTimeUnit.SECOND)

    data class RedisConfig(
        val uris: List<String> = listOf("redis://localhost:6379"),
        val leasePercentage: Int = 10,
        val keyPrefix: String = "klimiter",
    )
}
