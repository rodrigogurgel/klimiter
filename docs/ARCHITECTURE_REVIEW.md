# KLimiter Architecture Review

## Summary

The project structure is solid for a Kotlin library plus service:

- `klimiter-core` owns the public API, SPI, in-memory implementation, and coordination logic.
- `klimiter-redis` extends the core through the SPI and keeps Redis/Lettuce details outside the core.
- `klimiter-service` follows a recognizable ports-and-adapters layout with gRPC as input and KLimiter as output adapter.
- `klimiter-architecture-tests` is the right place for Konsist tests.

The main architectural risks are not the module boundaries themselves; they are stricter public API boundary choices, lifecycle management for Redis resources, and whether the coordinator semantics match the desired all-or-nothing behavior.

## Findings

### 1. Good: module boundaries are mostly correct

The Gradle modules follow the expected direction:

```text
klimiter-core       no dependency on redis/service
klimiter-redis      depends on core
klimiter-service    depends on core and redis
```

This is the right structure for a reusable library with optional distributed backend and an executable adapter.

### 2. Good: service uses ports-and-adapters shape

The service has:

```text
domain/model
domain/port/input
domain/port/output
application
adapter/input/grpc
adapter/output/klimiter
config
```

This is a good shape. It lets gRPC, protobuf, Spring, and KLimiter-specific mapping stay outside the domain model.

### 3. Warning: public API imports internal implementation

Current examples:

- `io.klimiter.core.api.KLimiterBuilder` imports `io.klimiter.core.internal.DefaultKLimiterBuilder`.
- `io.klimiter.redis.api.RedisKLimiterFactory` imports `io.klimiter.redis.internal.DefaultCloseableKLimiter`.
- `io.klimiter.redis.api.RedisRateLimitOperationFactories` imports internal Redis implementation classes.
- `io.klimiter.redis.api.RedisKLimiterConfig` imports `io.klimiter.redis.internal.lease.LeasedBucketStore` for the default grace period.

This is not automatically wrong in Kotlin, especially because `internal` is module-private and factories often instantiate internal classes. However, it weakens the conceptual boundary between public API and implementation.

Recommendation:

- Keep factory facades in `api` if you prefer simple public entrypoints.
- Avoid exposing internal constants through public config defaults.
- Move shared defaults to public constants if they are part of API semantics.

Example improvement:

```kotlin
object RedisKLimiterDefaults {
    const val DEFAULT_KEY_PREFIX: String = "klimiter"
    const val DEFAULT_LEASE_PERCENTAGE: Int = 10
    val DEFAULT_GRACE_PERIOD: Duration = 30.seconds
}
```

Then `RedisKLimiterConfig` does not need to import `LeasedBucketStore`.

### 4. Warning: coordinator behavior may not match full TCC semantics

`RateLimitCoordinator` executes operations sequentially and stops on the first non-OK result, rolling back only the previously successful operations.

This is valid for short-circuit rate limiting and is efficient.

However, if the intended TCC rule is: "execute/reserve all participants first, then decide commit/rollback after knowing every participant result", then the current coordinator is not semantically equivalent.

Decision needed:

- If performance and early rejection are preferred, keep the current short-circuit behavior.
- If you need complete per-descriptor status visibility or strict TCC across all participants, change the coordinator to execute every operation, collect all statuses, then rollback all successful reservations if any status is non-OK.

### 5. Warning: Redis lifecycle in Spring service

`RedisKLimiterFactory` returns `CloseableKLimiter`. The Spring bean method declares return type `KLimiter`.

Spring can still call destroy methods on beans if it can detect them, but declaring the bean as the narrower `KLimiter` type may make lifecycle expectations less obvious.

Recommendation:

- Either return `CloseableKLimiter` from Redis-specific bean paths, or register a destroy method explicitly.
- Alternatively, wrap Redis client lifecycle in a Spring-managed bean.

### 6. Warning: Redis Cluster and Lua key-slot safety

The project uses Redis Cluster command execution and Lua scripts. In Redis Cluster, scripts that operate on multiple keys require all keys to be in the same hash slot.

Recommendation:

- Ensure lease scripts operate on exactly one Redis key, or use hash tags in keys, for example `{klimiter:<bucket-id>}`.
- Add a Redis integration test that runs against a real cluster and validates no CROSSSLOT errors under multi-key scenarios.

### 7. Improvement: `api.spi` package naming

The project currently places SPI under:

```text
io.klimiter.core.api.spi
```

This is acceptable, but semantically noisy. SPI is public extension API, but not normal consumer API.

Possible alternatives:

```text
io.klimiter.core.spi
```

or:

```text
io.klimiter.core.extension
```

I would use `io.klimiter.core.spi` if the project is meant to be a library with pluggable backends.

### 8. Improvement: add Redis module tests

`klimiter-core` already has unit tests. `klimiter-redis` currently has no test directory in the exported structure.

Recommended Redis test categories:

- Lua script output shape parsing.
- NOSCRIPT reload behavior.
- Standalone Redis integration test with Testcontainers.
- Redis Cluster integration test for slot/script behavior.
- Lease percentage edge cases.
- Local leased bucket exhaustion and renewal.

### 9. Improvement: add service tests

`klimiter-service` currently has no test directory in the exported structure.

Recommended service test categories:

- `KLimiterProperties` binding.
- `KLimiterConfiguration` local/standalone/cluster mode selection.
- gRPC mapper tests.
- `CheckRateLimitService` use-case tests with fake `RateLimitEnforcerPort`.

## Recommended next steps

1. Run the architecture tests:

```bash
./gradlew :klimiter-architecture-tests:test
```

2. Keep `StrictApiBoundaryArchitectureTest` disabled until you decide whether public factory facades may instantiate internal implementations.
3. Fix `RedisKLimiterConfig` importing `LeasedBucketStore.DEFAULT_GRACE_PERIOD` from internal code — move defaults to a public `RedisKLimiterDefaults` object.
4. Decide whether `RateLimitCoordinator` should short-circuit (current) or execute all participants before rollback (strict TCC).
5. Add Redis integration tests before considering the distributed backend production-ready.
6. Add service tests (property binding, backend mode selection, gRPC mapper, use-case layer) before any production deployment.
