package io.klimiter.core.spi

import java.time.Instant

object SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}
