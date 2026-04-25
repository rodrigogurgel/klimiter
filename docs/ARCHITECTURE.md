# KLimiter Architecture

## Overview

KLimiter is organized as a multi-module Gradle project with a clean separation between reusable rate-limit logic, Redis-backed distributed execution, and a Spring/gRPC service adapter.

Current modules:

```text
klimiter
├── klimiter-core
├── klimiter-redis
├── klimiter-service
└── klimiter-architecture-tests
```

## Module responsibilities

### `klimiter-core`

Purpose: reusable Kotlin library containing the public API, configuration model, SPI contracts, in-memory backend, and core orchestration.

Main packages:

```text
io.klimiter.core.api
io.klimiter.core.api.config
io.klimiter.core.api.rls
io.klimiter.core.api.spi
io.klimiter.core.internal
io.klimiter.core.internal.coordinator
io.klimiter.core.internal.infra.store
io.klimiter.core.internal.operation
```

Responsibilities:

- Expose `KLimiter` as the main API.
- Expose `KLimiterBuilder` and `KLimiterFactory` for construction.
- Expose request/response models under `api.rls`.
- Expose rate-limit configuration models under `api.config`.
- Expose extension points under `api.spi`.
- Provide the default in-memory backend using Caffeine.
- Coordinate multiple rate-limit operations with rollback semantics.

Expected dependencies:

- Kotlin standard library.
- Coroutines.
- SLF4J API.
- Caffeine for the in-memory implementation.

Forbidden dependencies:

- Redis/Lettuce.
- Spring.
- gRPC/protobuf.
- `klimiter-service`.

### `klimiter-redis`

Purpose: distributed Redis-backed implementation of the core SPI.

Main packages:

```text
io.klimiter.redis.api
io.klimiter.redis.internal
io.klimiter.redis.internal.command
io.klimiter.redis.internal.lease
io.klimiter.redis.internal.script
```

Responsibilities:

- Expose Redis construction APIs through `RedisKLimiterFactory` and `RedisRateLimitOperationFactory`.
- Implement `RateLimitOperationFactory` using Redis leases.
- Support standalone/Sentinel-style connections and Redis Cluster connections.
- Hide Lettuce command execution, Lua script loading, and leased bucket tracking behind internal packages.

Expected dependencies:

- `klimiter-core`.
- Lettuce.
- Coroutines/reactive bridge.
- Caffeine for local leased bucket tracking.

Forbidden dependencies:

- `klimiter-service`.
- Spring service-layer code.

### `klimiter-service`

Purpose: runnable Spring Boot application exposing KLimiter through gRPC.

Main packages:

```text
io.klimiter.klimiterservice
io.klimiter.klimiterservice.config
io.klimiter.klimiterservice.domain.model
io.klimiter.klimiterservice.domain.port.input
io.klimiter.klimiterservice.domain.port.output
io.klimiter.klimiterservice.application
io.klimiter.klimiterservice.adapter.input.grpc
io.klimiter.klimiterservice.adapter.output.klimiter
```

Responsibilities:

- Configure a `KLimiter` bean in local, standalone Redis, or cluster mode.
- Expose gRPC input adapter.
- Map protobuf requests/responses to service-domain models.
- Keep application use cases independent of transport and backend details.
- Delegate enforcement to a domain output port.

Expected architecture style: hexagonal/ports-and-adapters.

## Dependency direction

Allowed module dependency direction:

```text
klimiter-service ──▶ klimiter-redis ──▶ klimiter-core
       │                                   ▲
       └───────────────────────────────────┘
```

Rules:

- `klimiter-core` must not depend on `klimiter-redis` or `klimiter-service`.
- `klimiter-redis` may depend on `klimiter-core`, but not on `klimiter-service`.
- `klimiter-service` may depend on both `klimiter-core` and `klimiter-redis`.
- Domain and application code in `klimiter-service` should not depend directly on gRPC, protobuf, Redis, or core implementation details.

## Core request flow

```text
RateLimitRequest
   │
   ▼
KLimiter.shouldRateLimit
   │
   ▼
RateLimitOperationFactory.create(request)
   │
   ▼
List<RateLimitOperation>
   │
   ▼
RateLimitCoordinator.execute(operations)
   │
   ├── no operation: OK
   ├── one operation: execute directly
   └── multiple operations: execute sequentially and rollback previous reservations on failure
```

## Redis backend flow

```text
RedisKLimiterFactory
   │
   ▼
RedisRateLimitOperationFactory
   │
   ├── StandaloneRedisCommandExecutor
   └── ClusterRedisCommandExecutor
   │
   ▼
RedisRateLimitOperation
   │
   ├── LeasedBucketStore
   ├── LeasedBucket
   ├── LeaseScripts
   └── LuaScript
```

The Redis backend uses a lease-based strategy: a node obtains a local share of capacity from Redis, consumes that share locally, and renews synchronously when needed.

## Service flow

```text
gRPC request
   │
   ▼
RateLimitGrpcAdapter
   │
   ▼
CheckRateLimitUseCase
   │
   ▼
CheckRateLimitService
   │
   ▼
RateLimitEnforcerPort
   │
   ▼
KLimiterCoreAdapter
   │
   ▼
KLimiter
```

This is a good ports-and-adapters shape. The service domain remains small and independent, while external details are isolated in adapters and configuration.

## Architectural test strategy

The generated `klimiter-architecture-tests` module validates:

- Expected module existence.
- Core has no Redis/Spring/gRPC dependencies.
- Core SPI does not import core internal implementation.
- Redis does not depend on service.
- Service domain does not depend on adapters, Spring, gRPC, core, or Redis.
- Service application layer does not depend on transport/backend adapters.
- Hexagonal package layout exists.

A strict API-boundary test is included but disabled because the current project intentionally uses API facade objects/builders that instantiate internal implementations.
