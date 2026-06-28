package io.github.rodrigogurgel.klimiter.adapter.outbound.clock

import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import org.springframework.stereotype.Component

/** Implementação da porta [Clock] com o relógio do sistema (§3.2). */
@Component
class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
