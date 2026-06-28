package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Amortização do lease de alta prioridade (§5): em vez de arrendar exatamente N, arrenda-se um
 * bloco maior cujo excedente vira crédito local. O tamanho do bloco vem da política, em um de
 * dois modos mutuamente exclusivos — ou ausente ([None]).
 */
sealed interface Prefetch {
    /** Unidades de prefetch resolvidas para uma [capacity] (§5). */
    fun units(capacity: Capacity): Int

    /** Sem prefetch: arrenda apenas o necessário. */
    data object None : Prefetch {
        override fun units(capacity: Capacity): Int = 0
    }

    /** Bloco como percentual (1–100) da capacidade. */
    data class Percent(val value: Int) : Prefetch {
        init {
            require(value in 1..PERCENT_MAX) { "prefetch.percent deve estar entre 1 e $PERCENT_MAX, recebido $value" }
        }

        override fun units(capacity: Capacity): Int = (capacity.requestsPerUnit.toLong() * value / PERCENT_MAX).toInt()

        private companion object {
            const val PERCENT_MAX = 100
        }
    }

    /** Bloco em unidades absolutas. */
    data class Count(val value: Int) : Prefetch {
        init {
            require(value >= 0) { "prefetch.count deve ser >= 0, recebido $value" }
        }

        override fun units(capacity: Capacity): Int = value
    }
}
