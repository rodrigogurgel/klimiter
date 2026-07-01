package io.github.rodrigogurgel.klimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Proteção contra saturação por *load shedding* (§ short-circuit): rejeita chamadas com
 * `UNAVAILABLE` antes de tocar o hot path quando o processo se aproxima do joelho de CPU. Dois
 * mecanismos independentes e opcionais (ambos `enabled=false` por default), aplicáveis juntos:
 *
 * - [cpu]: portão por carga de CPU (limiar fixo). Sinal simples, porém atrasado (~1s) e ruidoso —
 *   bom como rede de segurança grosseira, ruim como controle fino.
 * - [concurrency]: limitador adaptativo (Gradient2) que infere a saturação pela latência (RTT) e
 *   ajusta o teto de chamadas em voo sozinho, sem limiar mágico. Mecanismo principal.
 *
 * Prefixo `klimiter.shed`; env `KLIMITER_SHED_*`.
 */
@ConfigurationProperties(prefix = "klimiter.shed")
data class ShedProperties(val cpu: Cpu = Cpu(), val concurrency: Concurrency = Concurrency()) {
    /**
     * Portão por carga de CPU do processo.
     *
     * @property enabled liga o portão (`KLIMITER_SHED_CPU_ENABLED`).
     * @property threshold fração de CPU do processo `[0.0, 1.0]` a partir da qual as chamadas são
     *   cortadas (`KLIMITER_SHED_CPU_THRESHOLD`). Leituras inválidas (`< 0`, ex.: JVM sem suporte)
     *   nunca cortam — *fail open*.
     * @property sampleInterval período de amostragem da carga fora do hot path; o interceptor lê o
     *   último valor cacheado (`KLIMITER_SHED_CPU_SAMPLE_INTERVAL`).
     */
    data class Cpu(
        val enabled: Boolean = false,
        val threshold: Double = DEFAULT_THRESHOLD,
        val sampleInterval: Duration = Duration.ofMillis(DEFAULT_SAMPLE_INTERVAL_MILLIS),
    ) {
        init {
            require(threshold in 0.0..1.0) { "cpu.threshold deve estar entre 0.0 e 1.0, recebido $threshold" }
            require(!sampleInterval.isNegative && !sampleInterval.isZero) {
                "cpu.sample-interval deve ser positivo, recebido $sampleInterval"
            }
        }

        private companion object {
            const val DEFAULT_THRESHOLD = 0.90
            const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 250L
        }
    }

    /**
     * Limitador de concorrência adaptativo (Gradient2): o teto de chamadas em voo cresce enquanto o
     * RTT fica estável e encolhe quando a latência sobe (sinal de saturação), cortando o excedente.
     *
     * @property enabled liga o limitador (`KLIMITER_SHED_CONCURRENCY_ENABLED`).
     * @property initialLimit teto inicial de chamadas em voo antes de o algoritmo convergir
     *   (`KLIMITER_SHED_CONCURRENCY_INITIAL_LIMIT`).
     * @property minLimit piso do teto — nunca corta abaixo disto, evitando fechar sob ruído de RTT
     *   (`KLIMITER_SHED_CONCURRENCY_MIN_LIMIT`).
     * @property maxConcurrency teto máximo absoluto que o algoritmo pode alcançar
     *   (`KLIMITER_SHED_CONCURRENCY_MAX_CONCURRENCY`).
     * @property rttTolerance multiplicador de tolerância do RTT: `1.0` é agressivo, valores maiores
     *   (ex.: `1.5`) toleram mais jitter antes de encolher (`KLIMITER_SHED_CONCURRENCY_RTT_TOLERANCE`).
     */
    data class Concurrency(
        val enabled: Boolean = false,
        val initialLimit: Int = DEFAULT_INITIAL_LIMIT,
        val minLimit: Int = DEFAULT_MIN_LIMIT,
        val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        val rttTolerance: Double = DEFAULT_RTT_TOLERANCE,
    ) {
        init {
            require(minLimit >= 1) { "concurrency.min-limit deve ser >= 1, recebido $minLimit" }
            require(initialLimit in minLimit..maxConcurrency) {
                "concurrency.initial-limit deve estar entre min-limit ($minLimit) e " +
                    "max-concurrency ($maxConcurrency), recebido $initialLimit"
            }
            require(rttTolerance >= 1.0) { "concurrency.rtt-tolerance deve ser >= 1.0, recebido $rttTolerance" }
        }

        private companion object {
            const val DEFAULT_INITIAL_LIMIT = 100
            const val DEFAULT_MIN_LIMIT = 20
            const val DEFAULT_MAX_CONCURRENCY = 1000
            const val DEFAULT_RTT_TOLERANCE = 1.5
        }
    }
}
