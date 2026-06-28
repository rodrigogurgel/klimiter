package io.github.rodrigogurgel.klimiter.support

import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.ReservePath
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.port.outbound.RateLimitMetrics

/** [RateLimitMetrics] que apenas grava as chamadas, para asserções nos testes do core. */
class RecordingRateLimitMetrics : RateLimitMetrics {
    val reserves = mutableListOf<Pair<Priority, Status>>()
    val highPaths = mutableListOf<ReservePath>()
    var shortCircuits = 0
        private set
    var refunds = 0
        private set

    override fun reserve(priority: Priority, status: Status) {
        reserves += priority to status
    }

    override fun reserveHighPath(path: ReservePath) {
        highPaths += path
    }

    override fun batchShortCircuited() {
        shortCircuits++
    }

    override fun batchRefunded() {
        refunds++
    }
}
