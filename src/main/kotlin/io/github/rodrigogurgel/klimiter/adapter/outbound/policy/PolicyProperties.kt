package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuração do carregamento de políticas. Liga o serviço ao arquivo externo (§8).
 *
 * @property path caminho do arquivo de políticas. Relativo ao diretório de trabalho ou absoluto.
 *   Default: `config/policies/policies.yaml`. Sobrescrevível por `KLIMITER_POLICIES_PATH`.
 */
@ConfigurationProperties(prefix = "klimiter.policies")
data class PolicyProperties(val path: String = "config/policies/policies.yaml")
