package io.github.rodrigogurgel.klimiter.core.port.outbound

import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot

/**
 * Porta de saída para o repositório de políticas (§8.1): serve leituras sem trava e sempre
 * consistentes. A implementação (adapter) carrega o arquivo e publica o snapshot de forma
 * visível entre threads; o core consome apenas esta interface.
 */
interface PolicyRepository {
    /** O snapshot de políticas vigente. Sempre consistente (nunca meio-atualizado). */
    fun current(): PolicySnapshot
}
