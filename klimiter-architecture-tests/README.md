# klimiter-architecture-tests

Konsist-based architecture tests that validate module boundaries, package structure, and naming conventions across all KLimiter modules. These tests run as part of the regular build and fail the pipeline if any rule is violated.

## Dependencies

```
konsist
kotlin-test + JUnit 5 platform
```

This module has no runtime dependency on any KLimiter module. It reads compiled class files from sibling modules via `Konsist.scopeFromDirectory(...)`.

## Running

```bash
./gradlew :klimiter-architecture-tests:test
```

## Test classes

### `KonsistSmokeArchitectureTest`

Verifies that Konsist can read source files from each module scope. Fails fast if the directory paths in `ArchitectureTestSupport` become stale.

### `CoreModuleArchitectureTest`

| Rule | What it enforces |
|---|---|
| No Redis dependency | `klimiter-core` files must not import `io.klimiter.redis.*` |
| No service dependency | `klimiter-core` files must not import `io.klimiter.service.*` |
| No Spring dependency | `klimiter-core` files must not import `org.springframework.*` |
| No Lettuce dependency | `klimiter-core` files must not import `io.lettuce.*` |
| API isolation | `api.*` packages must not import Redis or service packages |
| SPI purity | `api.spi.*` must not import `core.internal.*` |
| Internal visibility | All classes under `core.internal` must be `internal` or `private` |

### `RedisModuleArchitectureTest`

| Rule | What it enforces |
|---|---|
| Correct namespace | All files live under `io.klimiter.redis.*` |
| No service dependency | `klimiter-redis` must not import `io.klimiter.service.*` |
| No Spring dependency | `klimiter-redis` must not import `org.springframework.*` |
| API surface clean | `redis.api.*` must not expose Lettuce types |
| Internal visibility | All classes under `redis.internal` must be `internal` or `private` |
| Command executor placement | Classes ending in `RedisCommandExecutor` must be in `redis.internal.command` |
| Lua script placement | Objects ending in `Scripts` must be in `redis.internal.script` |

### `ServiceModuleArchitectureTest`

| Rule | What it enforces |
|---|---|
| Domain isolation (Spring) | `service.domain.*` must not import `org.springframework.*` |
| Domain isolation (gRPC) | `service.domain.*` must not import `io.klimiter.service.proto.*` |
| Application layer isolation | `service.application.*` must not import adapters, config, or proto packages |
| gRPC adapter placement | Classes ending in `GrpcAdapter` must be in `service.adapter.input.grpc` |
| Config class placement | Classes ending in `Configuration` or `Properties` must be in `service.config` |
| Input port placement | Interfaces ending in `UseCase` must be in `service.domain.port.input` |
| Output port placement | Interfaces ending in `Port` must be in `service.domain.port.output` |

### `NamingConventionArchitectureTest`

| Rule | What it enforces |
|---|---|
| No `I`-prefix interfaces | Interfaces must not follow the `IFoo` Hungarian notation pattern |
| No test helpers in production | Production source sets must not contain classes named `*Test`, `*Fake`, `*Stub`, or `*Mock` |
| Factory suffix | Any class with `Factory` in the name must end with `Factory` |
| Config class suffix | Config-package classes must end with `Configuration`, `Properties`, or `Application` |

### `ProjectStructureArchitectureTest`

| Rule | What it enforces |
|---|---|
| Core package layout | `klimiter-core` must contain `api.*`, `spi.*`, and `internal.*` packages |
| Redis package layout | `klimiter-redis` must contain `api.*` and `internal.*` packages |
| Service package layout | `klimiter-service` must contain `adapter.*`, `application.*`, `config.*`, and `domain.*` packages |

### `StrictApiBoundaryArchitectureTest` _(disabled by design)_

This test checks that `api` packages do not import `internal` packages (in both `core` and `redis`). It is intentionally disabled because public factory facades in `api` instantiate internal implementations by design — the strict boundary check would fail on them. Enable it only if all internal instantiation is moved behind a different mechanism.

## Adding new rules

1. Pick or create the relevant test class (use `ArchitectureTestSupport` to get the right scope).
2. Write a `@Test` function using Konsist's `assertTrue` / `assertFalse` on a filtered file/class/interface collection.
3. Keep each test focused on a single invariant — Konsist error messages reference the violating declaration, so narrow tests give clearer failures.
