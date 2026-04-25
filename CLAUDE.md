# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

Multi-module Gradle Kotlin DSL build. Kotlin 2.3.0, JDK toolchain 21, Spring Boot 4.0.5. Modules registered in `settings.gradle.kts`: `klimiter-core`, `klimiter-redis`, `klimiter-service`.

- Build everything: `./gradlew build`
- Build a module: `./gradlew :klimiter-core:build -x test`
- Run all tests for a module: `./gradlew :klimiter-core:test`
- Run a single test: `./gradlew :klimiter-core:test --tests "io.klimiter.core.internal.coordinator.RateLimitCoordinatorTest"` (tests use `kotlin.test` + JUnit 5 platform)
- Run the demo: `./gradlew :klimiter-core:runDemo` → `io.klimiter.core.demo.MainKt`
- Run the gRPC service: `./gradlew :klimiter-service:bootRun` → listens on **gRPC port 9090** (`spring.grpc.server.port`). Spring Boot DevTools + docker-compose support are `developmentOnly`.
- Regenerate proto stubs: `./gradlew :klimiter-service:generateProto` (Java + Kotlin gRPC stubs from `klimiter-service/src/main/proto/klimiter.proto`, package `io.klimiter.klimiterservice.proto`).

## Architecture

Three modules with a clear layering: **klimiter-core** is the rate-limiter library, **klimiter-redis** is the Redis backend that implements the core SPI, and **klimiter-service** is the deployable gRPC service.

### klimiter-core — the limiter library

Public surface lives under `io.klimiter.core.api.*`; everything in `io.klimiter.core.internal.*` is `internal` to the module. Entry point is `KLimiterBuilder.create()` → `KLimiter.shouldRateLimit(request)` (suspending).

**Extension point:** `KLimiterBuilder.operationFactory(factory: RateLimitOperationFactory)` plugs in a custom backend (e.g. `klimiter-redis`) replacing the default in-memory one. When set, `maxCacheSize` and `gracePeriod` on the builder are ignored.

**SPI (`io.klimiter.core.api.spi`)** — the interfaces a custom backend must implement:
- `RateLimitOperationFactory` — creates `RateLimitOperation` instances for each request.
- `RateLimitOperation` — one atomic reservation; `execute()` returns `OK` or `OVER_LIMIT`, `rollback()` undoes the reservation and is idempotent.
- `KeyGenerator` — derives bucket cache keys (default: `CompositeKeyGenerator`).
- `TimeProvider` — clock source (default: `SystemTimeProvider`); inject a fixed instance in tests.

Request flow:

1. `DefaultKLimiter` calls `RateLimitOperationFactory.create(request)`, which for each `RateLimitDescriptor` in the request walks the `RateLimitDomain` tree via `findByPath` and materializes one `RateLimitOperation`.
2. `RateLimitCoordinator.execute(operations)` runs all operations sequentially with **all-or-nothing semantics**: on the first non-OK status it calls `rollback()` on every previously-reserved operation (rollback exceptions are swallowed via `runCatching`). Single-operation path avoids list allocation.
3. `InMemoryRateLimitOperation.execute()` is lock-free CAS over an `AtomicLong` from `InMemoryRateLimitStore`.

Design decisions that must not be casually changed:

- **Time-bucketed keys, not sliding windows.** `CompositeKeyGenerator` produces `klimiter|<domain>|<k>=<v>|...|<windowStart>`, where `windowStart = (now / windowSeconds) * windowSeconds`. Each new logical window gets a fresh bucket; the previous one expires. Separator is `|`; domain/key/value containing `|` is rejected — tested in `CompositeKeyGeneratorTest`.
- **`gracePeriod` on Caffeine TTL is load-bearing.** `InMemoryRateLimitStore` sets `expireAfterCreate = ttlSeconds + gracePeriod`. Without it, scheduler/GC jitter can let Caffeine recreate the same time-bucketed key within the same window — the **"concurrent-window leak"**, which doubles the effective limit. Default is 30s. The store also keeps a `recentlyCreated` side-cache that logs a `WARN` on the leak signature — preserve it when refactoring.

Descriptor matching (`api/config/MatchEngine.kt`) precedence: **exact (`key`+`value`)** → **wildcard (`key`+`value*`, longest prefix wins)** → **key-only (`key`, `value=null`)**. A descriptor with no `rule` and no `children` is *whitelisted* (no operation produced); `RateLimitRule(unlimited=true, requestsPerUnit=0)` also produces no operation.

Several fields on `RateLimitDescriptor` / `RateLimitRule` / `RateLimitResponse` are `@Deprecated("not implemented yet")` placeholders for the upstream Envoy RLS contract — keep them, don't implement them ad-hoc.

### klimiter-redis — Redis backend

Implements `RateLimitOperationFactory` (and `RateLimitOperation`) from the core SPI using Lettuce and a **lease pattern**: each process holds a local budget (a fraction of the window limit) and only contacts Redis when the budget runs out.

**Entry point:** `RedisRateLimitOperationFactories` (an `object`) — `standalone(domainRepository, connection, config)` or `cluster(domainRepository, connection, config)`. Both accept a `RedisKLimiterConfig` for tuning.

`RedisKLimiterConfig` — notable knobs:
- `leasePercentage` (1–100, default 10): fraction of the per-window limit each node requests per renewal. Lower = more Redis round-trips, fairer distribution. Higher = more throughput, worse fairness.
- `keyPrefix` (default `"klimiter"`): prepended to every Redis key as `<prefix>:<generated-key>` to share a Redis without collisions.
- `gracePeriod` / `maxTrackedBuckets`: same semantics as the in-memory counterparts.

**Hot path (per-request):** `RedisRateLimitOperation.execute()` does a CAS against a `LeasedBucket.remaining` (an `AtomicLong`). On exhaustion, it serializes on a **per-bucket `Mutex`** (so concurrent requests coalesce into one Redis trip), runs the Lua lease-acquire script, then retries. Rollback returns hits to the local pool only — it never contacts Redis, so up to one lease-slice can leak on a failed all-or-nothing batch.

**Redis interaction (`internal/script`):**
- `LuaScript` — wraps a Lua source with lazy `SCRIPT LOAD` and automatic `NOSCRIPT` recovery (transparent reload on Redis restart/failover).
- `LeaseScripts.ACQUIRE` — atomic Lua: reads the bucket counter, computes `min(limit - current, requested)`, increments by that amount, sets TTL on first write, returns units granted.

`RedisCommandExecutor` abstracts Lettuce's standalone vs. cluster surfaces (both use Reactive + `awaitSingle` to bridge into coroutines). All KLimiter Lua scripts target a single key, so cross-slot concerns in cluster mode don't apply.

### klimiter-service — gRPC adapter

Hexagonal layout under `io.klimiter.klimiterservice`:

- `adapter/input/grpc/RateLimitGrpcAdapter` — `ShouldRateLimit` RPC implementation. Maps proto ↔ domain via `RateLimitGrpcMappers`.
- `application/CheckRateLimitService` — implements `CheckRateLimitUseCase`. Folds `RateLimitKeyStatus` list into one overall decision (`OVER_LIMIT` > `ERROR` > `OK`).
- `adapter/output/klimiter/KLimiterCoreAdapter` — the only `RateLimitEnforcerPort` impl. Holds a **bounded Caffeine cache** (max 256 entries, 1h idle TTL) of `KLimiter` instances keyed by `RateLimitKey.key` name. On cache miss, builds a new `KLimiter` with the configured default rule. Wraps each call in `runCatching`; returns `ERROR` status on failure.
- `domain/{model,port}` — pure Kotlin domain types; no Spring or proto leakage.

Service config (`klimiter.*` in `application.yaml`, bound via `KLimiterProperties`): `domainId` (default `"default"`), `default.limit` (default **600**), `default.unit` (default **SECOND**).

Proto contract (`klimiter.proto`): `ShouldRateLimit(repeated KeyRequest{key, value, cost})` → `ShouldRateLimitResponse{overall_decision, repeated KeyStatus}`. `Decision` enum: `OK=0 / OVER_LIMIT=1 / ERROR=2`. `cost` maps to `hitsAddend` only when `> 0`.

## Conventions

- All public-facing limiter calls are `suspend`. `RateLimitOperation` implementations must not block on I/O — the in-process backend uses lock-free CAS; the Redis backend uses coroutine-based Lettuce.
- Internal types use Kotlin `internal` visibility; resist promoting to `public` without placing the type under `api/`.
- Logger is SLF4J; trace-level logs on the hot path are gated on `logger.isTraceEnabled`.
