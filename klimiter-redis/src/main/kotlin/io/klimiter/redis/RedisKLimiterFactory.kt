package io.klimiter.redis

import io.klimiter.core.KLimiterBuilder
import io.klimiter.core.api.spi.RateLimitDomainRepository
import io.klimiter.redis.api.CloseableKLimiter
import io.klimiter.redis.api.RedisKLimiterConfig
import io.klimiter.redis.internal.DefaultCloseableKLimiter
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient

object RedisKLimiterFactory {

    /**
     * Redis standalone (or Sentinel) backend. Pass a Sentinel-aware URI for Sentinel mode;
     * Lettuce resolves the master transparently.
     *
     * The returned [CloseableKLimiter] owns the Lettuce client; call [CloseableKLimiter.close]
     * (or use `use {}`) to release connections on shutdown.
     *
     * @param uri Redis URI, e.g. `redis://localhost:6379` or `redis://password@host:6379/0`.
     * @param domainRepository source of rate-limit domain configurations.
     * @param config tuning knobs for the lease-based backend.
     */
    fun standalone(
        uri: String,
        domainRepository: RateLimitDomainRepository,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): CloseableKLimiter {
        val client = RedisClient.create(uri)

        val factory = RedisRateLimitOperationFactory.standalone(
            domainRepository = domainRepository,
            connection = client.connect(),
            config = config,
        )

        val limiter = KLimiterBuilder.create()
            .operationFactory(factory)
            .build()

        return DefaultCloseableKLimiter(
            delegate = limiter,
            onClose = client::shutdown,
        )
    }

    /**
     * Redis Cluster backend. Each URI in [uris] is used as a seed node for topology discovery.
     * One seed is enough; more seeds improve resilience to node failures at startup.
     *
     * The returned [CloseableKLimiter] owns the Lettuce cluster client; call
     * [CloseableKLimiter.close] (or use `use {}`) to release connections on shutdown.
     *
     * @param uris seed node URIs, e.g. `["redis://node1:6379", "redis://node2:6379"]`.
     * @param domainRepository source of rate-limit domain configurations.
     * @param config tuning knobs for the lease-based backend.
     */
    fun cluster(
        uris: List<String>,
        domainRepository: RateLimitDomainRepository,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): CloseableKLimiter {
        require(uris.isNotEmpty()) { "uris must not be empty" }

        val client = RedisClusterClient.create(
            uris.map { RedisURI.create(it) },
        )

        val factory = RedisRateLimitOperationFactory.cluster(
            domainRepository = domainRepository,
            connection = client.connect(),
            config = config,
        )

        val limiter = KLimiterBuilder.create()
            .operationFactory(factory)
            .build()

        return DefaultCloseableKLimiter(
            delegate = limiter,
            onClose = client::shutdown,
        )
    }
}
