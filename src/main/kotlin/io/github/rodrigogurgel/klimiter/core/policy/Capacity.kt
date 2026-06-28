package io.github.rodrigogurgel.klimiter.core.policy

/**
 * Capacidade da política: `requests_per_unit`, quantas requisições são permitidas por uma
 * [RateLimitUnit] (§3.1). Value Object com invariante de positividade (R5 da ARQUITETURA.md).
 */
@JvmInline
value class Capacity(val requestsPerUnit: Int) {
    init {
        require(requestsPerUnit >= 1) {
            "capacidade (requests_per_unit) deve ser >= 1, recebido $requestsPerUnit"
        }
    }
}
