package io.klimiter.klimiterservice.config

import io.klimiter.core.api.common.RateLimitTimeUnit
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "klimiter")
data class KLimiterProperties(
    val domainId: String = "default",
    val stripesCount: Int = 128,
    val default: DefaultRule = DefaultRule(),
) {
    data class DefaultRule(
        val limit: Int = 60,
        val unit: RateLimitTimeUnit = RateLimitTimeUnit.SECOND,
    )
}
