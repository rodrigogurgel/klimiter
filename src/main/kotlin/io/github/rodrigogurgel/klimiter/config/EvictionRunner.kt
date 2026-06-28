package io.github.rodrigogurgel.klimiter.config

import io.github.rodrigogurgel.klimiter.core.application.LocalBudget
import io.github.rodrigogurgel.klimiter.core.port.outbound.Clock
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

/**
 * Evicção periódica do índice local de buckets (§4.2): com o ciclo de vida do container
 * (`@PostConstruct`/`@PreDestroy`). Suspende entre varreduras (não bloqueia thread).
 */
@Component
class EvictionRunner(
    private val budget: LocalBudget,
    private val clock: Clock,
    private val properties: EvictionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @PostConstruct
    fun start() {
        job = scope.launch {
            while (isActive) {
                delay(properties.interval.toMillis().milliseconds)
                val removed = budget.evictExpired(clock.nowMillis())
                if (removed > 0) {
                    log.atDebug()
                        .addKeyValue("removed", removed)
                        .addKeyValue("remaining", budget.size())
                        .setMessage("evicção de buckets")
                        .log()
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job?.cancel()
    }
}
