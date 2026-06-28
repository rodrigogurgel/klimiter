package io.github.rodrigogurgel.klimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Varredura periódica do índice local de buckets (§4.2): remove buckets de janelas já vencidas,
 * limitando a memória sob valores de alta cardinalidade. Sobrescrevível por
 * `KLIMITER_EVICTION_INTERVAL`.
 */
@ConfigurationProperties(prefix = "klimiter.eviction")
data class EvictionProperties(val interval: Duration = Duration.ofSeconds(DEFAULT_INTERVAL_SECONDS)) {
    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 60L
    }
}
