package io.klimiter.core.api.spi

import java.time.Instant

/**
 * Supplies the current wall-clock instant. Abstracted so external implementations (tests,
 * custom operation factories) can inject a deterministic clock. The default is
 * [SystemTimeProvider].
 */
interface TimeProvider {
    fun now(): Instant
}
