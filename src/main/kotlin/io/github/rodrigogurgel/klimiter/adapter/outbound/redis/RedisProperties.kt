package io.github.rodrigogurgel.klimiter.adapter.outbound.redis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Conexão com o contador global em Redis (§4).
 *
 * @property uri URI do Redis (`redis://host:porta`, ou `rediss://` p/ TLS). Sobrescrevível por
 *   `KLIMITER_REDIS_URI`. No modo cluster, é o endpoint-semente (basta um nó/o configuration endpoint).
 * @property poolSize nº de conexões multiplexadas sempre abertas (round-robin, §4/PA-2).
 *   Sobrescrevível por `KLIMITER_REDIS_POOL_SIZE`.
 * @property keyPrefix prefixo das chaves de janela `prefixo:dimensão:valor:início` (§3.2).
 *   Sobrescrevível por `KLIMITER_REDIS_KEY_PREFIX`.
 * @property cluster usa o client de **cluster** (Redis Cluster / ElastiCache cluster mode enabled),
 *   com descoberta de topologia e roteamento por slot. Os scripts são single-key (KEYS[1]), então não
 *   há CROSSSLOT. `false` = standalone. Sobrescrevível por `KLIMITER_REDIS_CLUSTER`.
 * @property commandTimeout timeout de cada comando ao Redis (vira o command timeout default do
 *   Lettuce; default da lib é 60s, veneno no hot path). Estourar → `RedisCommandTimeoutException`,
 *   que o [BatchEvaluator] degrada para `UNKNOWN` por item (§7.4). Sobrescrevível por
 *   `KLIMITER_REDIS_COMMAND_TIMEOUT`.
 */
@ConfigurationProperties(prefix = "klimiter.redis")
data class RedisProperties(
    val uri: String = "redis://localhost:6379",
    val poolSize: Int = DEFAULT_POOL_SIZE,
    val keyPrefix: String = "klimiter",
    val cluster: Boolean = false,
    val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT,
) {
    private companion object {
        const val DEFAULT_POOL_SIZE = 8
        val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofMillis(250)
    }
}
