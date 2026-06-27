package io.github.rodrigogurgel.klimiter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KlimiterApplication

fun main(args: Array<String>) {
    runApplication<KlimiterApplication>(*args)
}
