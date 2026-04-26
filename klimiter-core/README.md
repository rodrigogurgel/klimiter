# klimiter-core

The core rate-limiting library. Provides the public API, SPI contracts, in-memory backend, and coordination logic. Has no dependency on Redis, Spring, or gRPC.

## Responsibilities

- Expose `KLimiter` as the main entry point for rate-limit checks
- Expose `KLimiterFactory` and `KLimiterBuilder` for constructing limiter instances
- Define request/response models under `api.rls`
- Define domain configuration models under `api.config`
- Define extension points under `api.spi` for custom backends
- Provide the default in-memory backend (lock-free CAS via Caffeine)
- Coordinate multiple rate-limit operations with rollback semantics

## Dependencies

```
Kotlin standard library
kotlinx-coroutines-core
slf4j-api
caffeine
```

## Usage

### 1. Define a domain

```kotlin
val domain = RateLimitDomain(
    id = "default",
    descriptors = listOf(
        RateLimitDescriptor(
            key = "user_id",
            rule = RateLimitRule(
                unit = RateLimitTimeUnit.SECOND,
                requestsPerUnit = 100
            )
        ),
        RateLimitDescriptor(
            key = "user_id",
            value = "premium_user",
            rule = RateLimitRule(
                unit = RateLimitTimeUnit.SECOND,
                requestsPerUnit = 1000
            )
        )
    )
)
```

### 2. Build a limiter

```kotlin
val limiter: KLimiter = KLimiterFactory.inMemory(
    StaticRateLimitDomainRepository(listOf(domain))
)
```

Or with the builder for more control:

```kotlin
val limiter: KLimiter = KLimiterBuilder()
    .domainRepository(StaticRateLimitDomainRepository(listOf(domain)))
    .maxCacheSize(10_000)
    .gracePeriod(30.seconds)
    .build()
```

### 3. Check a request

```kotlin
val response: RateLimitResponse = limiter.shouldRateLimit(
    RateLimitRequest(
        domain = "default",
        descriptors = listOf(
            RateLimitRequestDescriptor(
                entries = listOf(
                    RateLimitDescriptorEntry(key = "user_id", value = "user_1")
                )
            )
        )
    )
)

when (response.overallCode) {
    RateLimitCode.OK -> println("request allowed")
    RateLimitCode.OVER_LIMIT -> println("rate limited")
    else -> println("unknown")
}
```

## Descriptor matching

Matching precedence (highest to lowest):

1. **Exact** — `key` + `value` match
2. **Wildcard** — `key` + `value*` prefix match (longest prefix wins)
3. **Key-only** — `key` only (`value` is `null`)

A descriptor with no `rule` and no `children` is **whitelisted** (no operation produced). A rule with `unlimited = true` or `requestsPerUnit = 0` also produces no operation.

## SPI — custom backends

Plug in a custom backend by implementing `RateLimitOperationFactory` and passing it to the builder:

```kotlin
val limiter = KLimiterBuilder()
    .domainRepository(domainRepository)
    .operationFactory(myCustomFactory)
    .build()
```

When `operationFactory` is set, `maxCacheSize` and `gracePeriod` on the builder are ignored (the custom factory manages its own resources).

### SPI interfaces

| Interface | Purpose |
|---|---|
| `RateLimitOperationFactory` | Creates `RateLimitOperation` instances for each request |
| `RateLimitOperation` | One atomic reservation; `execute()` returns OK/OVER_LIMIT, `rollback()` is idempotent |
| `RateLimitDomainRepository` | Looks up a `RateLimitDomain` by ID |
| `KeyGenerator` | Derives bucket cache keys (default: `CompositeKeyGenerator`) |
| `TimeProvider` | Clock source (default: `SystemTimeProvider`; inject a fixed instance in tests) |

## Key design decisions

**Time-bucketed keys, not sliding windows.** `CompositeKeyGenerator` produces keys of the form `klimiter|<domain>|<k>=<v>|...|<windowStart>` where `windowStart = (now / windowSeconds) * windowSeconds`. Each logical window gets a fresh bucket; the previous one expires naturally.

**Grace period is load-bearing.** `InMemoryRateLimitStore` sets `expireAfterCreate = windowSeconds + gracePeriod` (default 30 s). Without it, GC or scheduler jitter can recreate the same time-bucketed key within the same window, doubling the effective limit (the "concurrent-window leak"). Do not remove this without understanding the implication.

**All-or-nothing coordinator.** `RateLimitCoordinator` executes operations sequentially and rolls back all previously successful reservations on the first non-OK result. Rollback exceptions are swallowed.

## Building and testing

```bash
# Build (skip detekt if needed)
./gradlew :klimiter-core:build -x detekt

# Run all tests
./gradlew :klimiter-core:test

# Run a specific test class
./gradlew :klimiter-core:test --tests "io.klimiter.core.internal.coordinator.RateLimitCoordinatorTest"

# Run the demo
./gradlew :klimiter-core:runDemo
```
