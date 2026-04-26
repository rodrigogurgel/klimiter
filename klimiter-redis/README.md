# klimiter-redis

Redis-backed distributed implementation of the `klimiter-core` SPI. Implements `RateLimitOperationFactory` using Lettuce and a **lease pattern** that keeps Redis round-trips low while maintaining fair distribution across nodes.

## Dependencies

```
klimiter-core
lettuce-core
kotlinx-coroutines-core
kotlinx-coroutines-reactive
caffeine
```

## Usage

### Standalone (or Sentinel)

```kotlin
val limiter: CloseableKLimiter = RedisKLimiterFactory.standalone(
    uri = "redis://localhost:6379",
    domainRepository = StaticRateLimitDomainRepository(listOf(domain))
)

// Use it
val response = limiter.shouldRateLimit(request)

// Close when done (releases Lettuce connections)
limiter.close()
```

### Redis Cluster

```kotlin
val limiter: CloseableKLimiter = RedisKLimiterFactory.cluster(
    uris = listOf(
        "redis://node1:7001",
        "redis://node2:7002",
        "redis://node3:7003"
    ),
    domainRepository = domainRepository
)
```

### With custom config

```kotlin
val config = RedisKLimiterConfig(
    leasePercentage = 5,
    keyPrefix = "myapp",
    gracePeriod = 30.seconds,
    maxTrackedBuckets = 10_000
)

val limiter = RedisKLimiterFactory.standalone(
    uri = "redis://localhost:6379",
    domainRepository = domainRepository,
    config = config
)
```

## Configuration reference

| Parameter | Default | Description |
|---|---|---|
| `leasePercentage` | `10` | Fraction (1–100) of the window limit each node requests per renewal. Lower = more Redis trips, fairer distribution. Higher = more throughput, less fairness. |
| `keyPrefix` | `"klimiter"` | Prepended to every Redis key as `<prefix>:<generated-key>`. Use to share a Redis instance without collisions. |
| `gracePeriod` | `30s` | Extra TTL beyond the logical window, same semantics as the in-memory grace period. |
| `maxTrackedBuckets` | *(varies)* | Maximum number of leased buckets tracked locally by Caffeine. |

## Lease pattern

Each node holds a **local budget** (a fraction of the window limit). The hot path is a lock-free CAS against a local `AtomicLong` — no Redis I/O.

When the local budget is exhausted:

1. A per-bucket `Mutex` serialises concurrent renewals (so only one coroutine contacts Redis).
2. The Lua `ACQUIRE` script atomically reads the bucket counter, computes `min(limit - current, requested)`, increments, sets TTL on first write, and returns units granted.
3. The coroutine retries its reservation against the newly acquired budget.

**Rollback** returns hits to the local pool only. It never contacts Redis, so up to one lease-slice can leak on a failed all-or-nothing batch.

## Key format

```
<keyPrefix>:<klimiter|<domain>|<k>=<v>|...|<windowStart>>
```

All KLimiter Lua scripts operate on a single key, so Redis Cluster cross-slot concerns do not apply.

## Lifecycle

`CloseableKLimiter` extends `KLimiter` with a `close()` method that releases Lettuce connections. In Spring, declare the return type as `CloseableKLimiter` (or register an explicit destroy method) so the container cleans up on shutdown.

## Building and testing

```bash
./gradlew :klimiter-redis:build -x detekt
./gradlew :klimiter-redis:test
```
