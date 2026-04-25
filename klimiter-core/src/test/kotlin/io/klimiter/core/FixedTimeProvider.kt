package io.klimiter.core

import io.klimiter.core.api.spi.TimeProvider
import java.time.Instant

internal class FixedTimeProvider(private val fixed: Instant) : TimeProvider {
    override fun now(): Instant = fixed
}
