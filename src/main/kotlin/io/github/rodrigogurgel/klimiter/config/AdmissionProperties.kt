package io.github.rodrigogurgel.klimiter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Estratégia de admissão. `OFF`=sem interceptor; `FIXED`=teto constante; `ADAPTIVE`=auto-calibra. */
enum class AdmissionMode { OFF, FIXED, ADAPTIVE }

/**
 * Controle de admissão (load shedding) do servidor gRPC: protege a CPU do pod sob sobrecarga
 * recusando o excedente (`RESOURCE_EXHAUSTED`) em vez de enfileirar, mantendo o p99 dos admitidos no
 * SLO. Concern de transporte, ortogonal ao rate-limiting (não toca veredito nem invariantes §9).
 * Per-pod, sem coordenação entre réplicas → scale-invariant.
 *
 * @property mode estratégia: `off` (default, interceptor ausente), `fixed` ([maxInflight]) ou
 *   `adaptive` ([adaptive]). Sobrescrevível por `KLIMITER_ADMISSION_MODE`.
 * @property maxInflight teto de chamadas `ShouldRateLimit` simultâneas no modo **fixed**. Dimensione
 *   ABAIXO do produto de Little (`joelho × SLO` é a média; p99 ≈ 4-5× → use ~⅕): em 2 cores no LOW,
 *   ~50 segura p99≤15ms a 25k. Sobrescrevível por `KLIMITER_ADMISSION_MAX_INFLIGHT`.
 * @property adaptive parâmetros do modo **adaptive** (teto que se auto-ajusta pela latência —
 *   elimina a constante por-hardware).
 */
@ConfigurationProperties(prefix = "klimiter.admission")
data class AdmissionProperties(
    val mode: AdmissionMode = AdmissionMode.OFF,
    val maxInflight: Int = 0,
    val adaptive: Adaptive = Adaptive(),
) {
    init {
        require(maxInflight >= 0) { "max-inflight deve ser >= 0, recebido $maxInflight" }
        if (mode == AdmissionMode.FIXED) {
            require(maxInflight > 0) { "mode=fixed exige max-inflight > 0, recebido $maxInflight" }
        }
    }

    /**
     * @property target latência-alvo da MÉDIA da janela (knob que mapeia o SLO): o controlador corta
     *   o teto quando a média passa disso. Como p99 ≈ 4-5× a média, use ≈ SLO/5 (ex.: SLO p99 15ms →
     *   ~3ms). É o único knob ligado ao SLO; os demais são bordas.
     * @property initialLimit teto inicial até o controlador convergir.
     * @property minLimit / [maxLimit] limites do teto (clamp).
     * @property window intervalo de decisão (gatilho por janela, estilo CoDel): age em fila permanente.
     */
    data class Adaptive(
        val target: Duration = Duration.ofMillis(DEFAULT_TARGET_MILLIS),
        val initialLimit: Int = DEFAULT_INITIAL,
        val minLimit: Int = DEFAULT_MIN,
        val maxLimit: Int = DEFAULT_MAX,
        val window: Duration = Duration.ofMillis(DEFAULT_WINDOW_MILLIS),
    ) {
        init {
            require(minLimit >= 1) { "min-limit deve ser >= 1, recebido $minLimit" }
            require(maxLimit >= minLimit) { "max-limit ($maxLimit) deve ser >= min-limit ($minLimit)" }
            require(initialLimit in minLimit..maxLimit) {
                "initial-limit ($initialLimit) fora de [$minLimit, $maxLimit]"
            }
            require(!window.isNegative && !window.isZero) { "window deve ser > 0, recebido $window" }
            require(!target.isNegative && !target.isZero) { "target deve ser > 0, recebido $target" }
        }

        private companion object {
            const val DEFAULT_TARGET_MILLIS = 3L
            const val DEFAULT_INITIAL = 50
            const val DEFAULT_MIN = 4
            const val DEFAULT_MAX = 1000
            const val DEFAULT_WINDOW_MILLIS = 100L
        }
    }
}
