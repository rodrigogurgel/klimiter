package io.klimiter.core.internal.port

import java.time.Instant

internal interface TimeProvider {
    fun now(): Instant
}