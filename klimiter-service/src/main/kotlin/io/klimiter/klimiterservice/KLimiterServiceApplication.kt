package io.klimiter.klimiterservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KLimiterServiceApplication

fun main(args: Array<String>) {
    runApplication<KLimiterServiceApplication>(*args)
}
