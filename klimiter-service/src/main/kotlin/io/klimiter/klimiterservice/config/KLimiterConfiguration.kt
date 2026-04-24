package io.klimiter.klimiterservice.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KLimiterProperties::class)
class KLimiterConfiguration
