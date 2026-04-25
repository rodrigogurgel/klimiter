package io.klimiter.redis.api

import io.klimiter.core.api.KLimiter
import io.klimiter.core.api.KLimiterBuilder
import io.klimiter.core.api.config.RateLimitDomain
import io.klimiter.redis.internal.DefaultCloseableKLimiter
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient
import kotlin.time.Duration

/**
 * Top-level factory for creating [KLimiter] instances in the three supported backends.
 *
 * Usage:
 * ```
 * // In-memory (no external dependency)
 * val limiter = KLimiters.inMemory(listOf(domain))
 *
 * // Redis standalone — use try-with-resources or close() on shutdown
 * KLimiters.standalone("redis://localhost:6379", listOf(domain)).use { limiter -> ... }
 *
 * // Redis cluster
 * KLimiters.cluster(listOf("redis://node1:6379", "redis://node2:6379"), listOf(domain)).use { limiter -> ... }
 * ```
 */
object KLimiters {

    /**
     * In-memory backend backed by Caffeine. No external dependency; suitable for single-node
     * deployments or local development.
     *
     * @param domains rate-limit domain configurations.
     * @param maxCacheSize maximum number of active buckets; null = unbounded.
     * @param gracePeriod extra TTL beyond the window to absorb GC/scheduler jitter.
     */
    fun inMemory(
        domains: Collection<RateLimitDomain>,
        maxCacheSize: Long? = null,
        gracePeriod: Duration? = null,
    ): KLimiter {
        val builder = KLimiterBuilder.create().addDomains(domains)
        maxCacheSize?.let { builder.maxCacheSize(it) }
        gracePeriod?.let { builder.gracePeriod(it) }
        return builder.build()
    }

    /**
     * Redis standalone (or Sentinel) backend. Pass a Sentinel-aware URI for Sentinel mode;
     * Lettuce resolves the master transparently.
     *
     * The returned [CloseableKLimiter] owns the Lettuce client; call [CloseableKLimiter.close]
     * (or use `use {}`) to release connections on shutdown.
     *
     * @param uri Redis URI, e.g. `redis://localhost:6379` or `redis://password@host:6379/0`.
     * @param domains rate-limit domain configurations.
     * @param config tuning knobs for the lease-based backend.
     */
    fun standalone(
        uri: String,
        domains: Collection<RateLimitDomain>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): CloseableKLimiter {
        val client = RedisClient.create(uri)
        val factory = RedisRateLimitOperationFactory.standalone(
            domains = domains,
            connection = client.connect(),
            config = config,
        )
        val limiter = KLimiterBuilder.create().operationFactory(factory).build()
        return DefaultCloseableKLimiter(limiter, client::shutdown)
    }

    /**
     * Redis Cluster backend. Each URI in [uris] is used as a seed node for topology discovery.
     * One seed is enough; more seeds improve resilience to node failures at startup.
     *
     * The returned [CloseableKLimiter] owns the Lettuce cluster client; call
     * [CloseableKLimiter.close] (or use `use {}`) to release connections on shutdown.
     *
     * @param uris seed node URIs, e.g. `["redis://node1:6379", "redis://node2:6379"]`.
     * @param domains rate-limit domain configurations.
     * @param config tuning knobs for the lease-based backend.
     */
    fun cluster(
        uris: List<String>,
        domains: Collection<RateLimitDomain>,
        config: RedisKLimiterConfig = RedisKLimiterConfig(),
    ): CloseableKLimiter {
        val client = RedisClusterClient.create(uris.map { RedisURI.create(it) })
        val factory = RedisRateLimitOperationFactory.cluster(
            domains = domains,
            connection = client.connect(),
            config = config,
        )
        val limiter = KLimiterBuilder.create().operationFactory(factory).build()
        return DefaultCloseableKLimiter(limiter, client::shutdown)
    }
}
