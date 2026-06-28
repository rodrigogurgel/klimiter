package io.github.rodrigogurgel.klimiter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class KlimiterApplication

fun main(args: Array<String>) {
    runApplication<KlimiterApplication>(*args)
}
