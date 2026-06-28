package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuração do carregamento de políticas. Liga o serviço ao arquivo externo (§8).
 *
 * @property path caminho do arquivo de políticas. Relativo ao diretório de trabalho ou absoluto.
 *   Default: `config/policies/policies.yaml`. Sobrescrevível por `KLIMITER_POLICIES_PATH`.
 * @property reloadDebounce janela de silêncio do hot reload: eventos do filesystem em rajada
 *   (rename/replace dos editores) são coalescidos num único reload após este intervalo.
 *   Default: 200ms. Sobrescrevível por `KLIMITER_POLICIES_RELOAD_DEBOUNCE`.
 */
@ConfigurationProperties(prefix = "klimiter.policies")
data class PolicyProperties(
    val path: String = "config/policies/policies.yaml",
    val reloadDebounce: Duration = Duration.ofMillis(DEFAULT_RELOAD_DEBOUNCE_MILLIS),
) {
    private companion object {
        const val DEFAULT_RELOAD_DEBOUNCE_MILLIS = 200L
    }
}
