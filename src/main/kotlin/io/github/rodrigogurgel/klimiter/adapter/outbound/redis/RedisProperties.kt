package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Conexão com o contador global em Redis (§4).
 *
 * @property uri URI do Redis (`redis://host:porta`). Sobrescrevível por `KLIMITER_REDIS_URI`.
 * @property poolSize nº de conexões multiplexadas sempre abertas (round-robin, §4/PA-2).
 *   Sobrescrevível por `KLIMITER_REDIS_POOL_SIZE`.
 * @property keyPrefix prefixo das chaves de janela `prefixo:dimensão:valor:início` (§3.2).
 *   Sobrescrevível por `KLIMITER_REDIS_KEY_PREFIX`.
 */
@ConfigurationProperties(prefix = "klimiter.redis")
data class RedisProperties(
    val uri: String = "redis://localhost:6379",
    val poolSize: Int = DEFAULT_POOL_SIZE,
    val keyPrefix: String = "klimiter",
) {
    private companion object {
        const val DEFAULT_POOL_SIZE = 8
    }
}
