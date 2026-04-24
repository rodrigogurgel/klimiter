package io.klimiter.core.internal.infra.time

import io.klimiter.core.internal.port.TimeProvider
import java.time.Instant

internal object SystemTimeProvider: TimeProvider {
    override fun now(): Instant = Instant.now()
}