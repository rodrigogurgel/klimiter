# Redis Backend

The `klimiter-redis` module provides a distributed rate-limiting backend using Redis and a **lease pattern** that minimises Redis round-trips while maintaining configurable fairness across nodes.

## Overview

Without a shared backend, each service instance tracks its own counters independently — in a three-instance deployment, each node permits the full limit, giving you 3× the intended throughput in aggregate. The Redis backend solves this by sharing counters across all nodes through Redis.

## Lease pattern

Direct per-request Redis calls are expensive under high load. KLimiter uses a lease strategy to amortise the cost:

1. **Lease acquisition** — a node requests a slice of the window capacity from Redis (the "lease"). By default, each node requests 10% of the limit per renewal.
2. **Local consumption** — the node consumes its leased budget locally using a lock-free `AtomicLong`. No Redis I/O on the hot path.
3. **Renewal** — when the local budget is exhausted, the node acquires a new lease from Redis. A per-bucket `Mutex` coalesces concurrent renewal attempts into one Redis call.

### Example

Limit: 1000 requests/second, three nodes, `leasePercentage = 10`.

Each node requests 100 units per lease. On average, each node can serve 100 requests before contacting Redis. At 3000 requests/second across all nodes, each node makes ~30 Redis calls/second instead of 1000.

### Rollback behaviour

`rollback()` returns hits to the local budget only. It never contacts Redis. In an all-or-nothing batch that fails mid-way, up to one lease slice of capacity can leak. In practice this is acceptable: the next window resets the counter.

## Redis data model

Each rate-limit bucket is stored as a single Redis key:

```
<keyPrefix>:<klimiter|<domain>|<k>=<v>|...|<windowStart>>
```

Example:

```
klimiter:klimiter|default|user_id=alice|1745712000
```

The key TTL is set on first write to `windowSeconds + gracePeriod`. All Lua scripts operate on a single key, so Redis Cluster cross-slot errors do not apply.

## Lua scripts

All Redis mutations are performed with Lua scripts for atomicity:

```lua
-- ACQUIRE script (simplified)
local current = tonumber(redis.call('GET', KEYS[1])) or 0
local remaining = limit - current
local granted = math.min(remaining, requested)
if granted > 0 then
  redis.call('INCRBY', KEYS[1], granted)
  redis.call('EXPIRE', KEYS[1], ttl)
end
return granted
```

Scripts are loaded with `SCRIPT LOAD` on first use and recover transparently from `NOSCRIPT` errors (e.g. after a Redis restart or failover).

## Standalone setup

```kotlin
val limiter = RedisKLimiterFactory.standalone(
    uri = "redis://localhost:6379",
    domainRepository = domainRepository
)
```

Standalone mode supports Sentinel URIs:

```
redis-sentinel://sentinel1:26379,sentinel2:26379/mymaster
```

## Cluster setup

```kotlin
val limiter = RedisKLimiterFactory.cluster(
    uris = listOf(
        "redis://node1:7001",
        "redis://node2:7002",
        "redis://node3:7003"
    ),
    domainRepository = domainRepository
)
```

Provide at least one cluster node URI. Lettuce discovers the full topology automatically.

## Configuration

```kotlin
val config = RedisKLimiterConfig(
    leasePercentage = 10,       // fraction of limit per lease slice (1–100)
    keyPrefix = "klimiter",     // prefix for all Redis keys
    gracePeriod = 30.seconds,   // extra TTL beyond the logical window
    maxTrackedBuckets = 10_000  // max leased buckets tracked locally
)
```

### Tuning leasePercentage

| Value | Effect |
|---|---|
| Low (1–5) | More Redis round-trips, more accurate per-node fairness |
| Default (10) | Balanced for most workloads |
| High (25–50) | Fewer Redis round-trips, higher throughput, but one node can consume a larger fraction before others get a chance |

A good starting point: keep `leasePercentage * nodeCount ≤ 100` so the total capacity is well-distributed. With 10 nodes at 10% each, all 100% of capacity is potentially pre-distributed in one round of leases.

## Lifecycle

`CloseableKLimiter` wraps `KLimiter` and adds a `close()` method that releases Lettuce connections. Always close the limiter on application shutdown:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread { limiter.close() })
```

In Spring, declare the bean return type as `CloseableKLimiter` or register a destroy method:

```kotlin
@Bean(destroyMethod = "close")
fun rateLimiter(): CloseableKLimiter = RedisKLimiterFactory.standalone(...)
```

## Running Redis locally

```bash
# Standalone
docker compose --profile redis_standalone up -d

# Cluster (3 nodes)
docker compose --profile redis_cluster up -d
```

Redis Insight is available at `http://localhost:5540` in both profiles.

## Verify connectivity

```bash
# Standalone
docker compose exec redis-standalone redis-cli ping

# Cluster
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster info
```

## Key monitoring metrics

| Metric | What to watch |
|---|---|
| Redis `GET`/`INCRBY` call rate | Proxy for lease renewal frequency; should be `requestRate * leasePercentage / 100` per node |
| Cache hit rate on leased buckets | Low hit rate means very short leases; consider raising `leasePercentage` |
| Key TTL | Verify keys are expiring at `windowSeconds + gracePeriod` |
| `NOSCRIPT` error count | Should be zero under steady state; indicates Redis restart/failover |
