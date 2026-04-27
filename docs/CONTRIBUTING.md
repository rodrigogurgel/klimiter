# KLimiter

KLimiter is a Kotlin coroutine-native rate-limiting library with an optional Redis-distributed backend and a deployable gRPC service. It implements the [Envoy Rate Limit Service (RLS)](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/rate_limit_filter) protocol and exposes a pluggable SPI so you can bring your own backend.

## Features

* **Coroutine-native** — all rate-limit checks are `suspend` functions, no blocking I/O
* **In-memory backend** — lock-free CAS with Caffeine; ready for single-node deployments
* **Redis backend** — distributed rate limiting using a lease pattern that minimises Redis round-trips
* **gRPC service** — Spring Boot 4 application implementing the Envoy RLS proto contract
* **Pluggable SPI** — implement `RateLimitOperationFactory` to wire in any backend
* **Hierarchical descriptors** — exact, wildcard, and key-only matching with nested rule trees
* **All-or-nothing semantics** — multi-descriptor requests roll back cleanly on the first failure

## Modules

| Module | Description |
| --- | --- |
| [`klimiter-core`](../klimiter-core/README.md) | Public API, SPI contracts, in-memory backend, coordination logic |
| [`klimiter-redis`](../klimiter-redis/README.md) | Redis-backed distributed implementation using the lease pattern |
| [`klimiter-service`](../klimiter-service/README.md) | Spring Boot gRPC service wrapping the library |
| [`klimiter-architecture-tests`](../klimiter-architecture-tests/README.md) | Konsist architecture validation tests |
| [`klimiter-load-test`](../klimiter-load-test/README.md) | k6 gRPC load tests with HTML report generation |

## Quick start

### In-memory (single node)

```kotlin
val domain = RateLimitDomain(
    id = "default",
    descriptors = listOf(
        RateLimitDescriptor(
            key = "user_id",
            rule = RateLimitRule(unit = RateLimitTimeUnit.SECOND, requestsPerUnit = 100)
        )
    )
)

val limiter = KLimiterFactory.inMemory(
    StaticRateLimitDomainRepository(listOf(domain))
)

val response = limiter.shouldRateLimit(
    RateLimitRequest(
        domain = "default",
        descriptors = listOf(
            RateLimitRequestDescriptor(
                entries = listOf(RateLimitDescriptorEntry(key = "user_id", value = "user_1"))
            )
        )
    )
)
```

### Redis standalone

```kotlin
val limiter = RedisKLimiterFactory.standalone(
    uri = "redis://localhost:6379",
    domainRepository = domainRepository
)
```

### Redis cluster

```kotlin
val limiter = RedisKLimiterFactory.cluster(
    uris = listOf("redis://node1:7001", "redis://node2:7002", "redis://node3:7003"),
    domainRepository = domainRepository
)
```

### gRPC service (Docker)

```bash
cp .env.example .env
docker compose --profile app_standalone up -d --build
```

Test with `grpcurl`:

```bash
grpcurl \
  -plaintext \
  -emit-defaults \
  -d '{"descriptors":[{"entries":[{"key":"user_id","value":"user_1"}]}],"domain":"default"}' \
  localhost:9090 \
  io.klimiter.RateLimitService.ShouldRateLimit
```

## Development Tooling

The project ships with both a `Makefile` and a `Taskfile.yml` — pick whichever you prefer. Both expose exactly the same commands.

### Make

```bash
make help              # list all available targets with descriptions
make env               # create .env from .env.example
make app-standalone    # build and start the app with Redis standalone
make app-cluster       # build and start the app cluster with Redis Cluster + Nginx
make grpcurl           # send a test gRPC request
make health            # check /actuator/health
make down              # stop and remove all containers
make logs              # follow logs (all services)
```

### Task ([go-task](https://taskfile.dev))

```bash
task                   # list all available tasks with descriptions
task env               # create .env from .env.example
task app-standalone    # build and start the app with Redis standalone
task app-cluster       # build and start the app cluster with Redis Cluster + Nginx
task grpcurl           # send a test gRPC request
task health            # check /actuator/health
task down              # stop and remove all containers
task logs              # follow logs (all services)
```

> See `Makefile` or `Taskfile.yml` at the repository root for the full list of targets.

## Documentation

| Document | Description |
| --- | --- |
| [Getting Started](docs/GETTING_STARTED.md) | Build, run, and send your first request |
| [Development](docs/DEVELOPMENT.md) | Local setup, build commands, tests, and tooling |
| [Configuration](docs/CONFIGURATION.md) | Domain rules, environment variables, backend modes |
| [Architecture](docs/ARCHITECTURE.md) | C4 component diagrams across all modules |
| [Flows](docs/FLOWS.md) | Sequence diagrams: happy path, rollback, Redis lease renewal |
| [Algorithms](docs/ALGORITHMS.md) | Rate limit decision flowchart, descriptor matching, state machine |
| [Architecture Review](docs/ARCHITECTURE_REVIEW.md) | Findings, risks, and recommended next steps |
| [Redis Backend](docs/REDIS.md) | Lease pattern, standalone vs cluster, tuning |
| [Docker Deployment](docs/DOCKER.md) | Docker Compose profiles and networking |
| [Testing](docs/TESTING.md) | Unit, architecture, and load tests |
| [Release](docs/RELEASE.md) | Versioning, changelog automation, and release steps |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues and debug tips |
| [Contributing](CONTRIBUTING.md) | Commit conventions, branch model, and PR checklist |

## Tech stack

* Kotlin 2.3.0 / JVM 21
* Spring Boot 4.0.5
* gRPC + protobuf (Spring gRPC 1.0.2)
* Lettuce 6.8.2 (Redis client)
* Caffeine 3.2.3
* Kotlinx Coroutines 1.10.2
* Detekt + ktlint (static analysis)
* Konsist (architecture tests)
