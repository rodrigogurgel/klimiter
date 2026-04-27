# Development Guide

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21+ | Gradle downloads the toolchain automatically |
| Docker + Compose | any recent | required for Redis profiles and load tests |
| git-cliff | 2.x | required only for changelog generation |
| grpcurl | any | optional, for manual gRPC calls |
| k6 | any | optional, for load tests |
| Task (go-task) | 3.x | optional; alternative to `make` |

Install git-cliff: <https://git-cliff.org/docs/installation>
Install go-task: <https://taskfile.dev/installation>

## Clone and first build

```bash
git clone <repo-url>
cd klimiter

# First build — skip detekt to speed things up
./gradlew build -x detekt
```

The Gradle wrapper downloads the correct Gradle version. JDK toolchain provisioning is automatic.

## Module layout

```
klimiter
├── klimiter-core                 Rate-limiter library (public API + in-memory backend)
├── klimiter-redis                Redis SPI implementation (lease pattern)
├── klimiter-service              Spring Boot gRPC service
├── klimiter-architecture-tests   Konsist module-boundary tests
└── klimiter-load-test            Standalone k6 + Python load test harness (not a Gradle module)
```

## Build commands

```bash
# Build all modules
./gradlew build

# Build a single module (skip tests)
./gradlew :klimiter-core:build -x test

# Skip static analysis during active development
./gradlew build -x detekt
```

## Running the service locally

### In-memory backend (no Redis needed)

```bash
./gradlew :klimiter-service:bootRun
```

The service starts on gRPC port **9090** and HTTP port **8080** (Actuator).

### Redis standalone via Docker Compose

```bash
cp .env.example .env
# set KLIMITER_BACKEND_MODE=REDIS_STANDALONE in .env
docker compose --profile app_standalone up -d --build
```

### Redis cluster via Docker Compose

```bash
cp .env.example .env
# set KLIMITER_BACKEND_MODE=REDIS_CLUSTER in .env
docker compose --profile app_cluster up -d --build
```

## Development Tooling (Make / Task)

The repository ships with a `Makefile` and a `Taskfile.yml` at the root. Both expose the same commands — pick whichever fits your workflow.

### Listing available commands

```bash
make help    # Makefile targets with descriptions
task         # Taskfile tasks with descriptions (requires go-task 3.x)
```

### Environment setup

```bash
make env          # copy .env.example → .env (skips if .env already exists)
task env          # same via Taskfile
```

### Starting the application

```bash
# Standalone (app + Redis standalone)
make app-standalone
task app-standalone

# Cluster (app × 3 + Redis Cluster + Nginx)
make app-cluster
task app-cluster
```

### Building images

```bash
make build              # build compose images
make build-no-cache     # build without layer cache
make rebuild-standalone # rebuild + force-recreate standalone profile
make rebuild-cluster    # rebuild + force-recreate cluster profile

# Taskfile equivalents
task build
task build-no-cache
task rebuild-standalone
task rebuild-cluster
```

### Logs

```bash
make logs                    # follow all logs
make logs-app                # app-standalone + app-1/2/3
make logs-standalone         # standalone app only
make logs-cluster            # cluster app instances
make logs-nginx              # Nginx
make logs-redis-cluster-init # Redis Cluster init container

# Taskfile equivalents (same names)
task logs
task logs-app
# ...
```

### Stopping

```bash
make down           # stop and remove containers (keeps volumes)
make down-volumes   # stop and remove containers + volumes
make clean          # alias for down-volumes

task down
task down-volumes
task clean
```

### Diagnostics

```bash
make health    # curl /actuator/health
make grpcurl   # send a sample ShouldRateLimit gRPC request
make ps        # list compose services

make redis-standalone-ping   # ping Redis standalone
make redis-cluster-info      # show cluster info
make redis-cluster-nodes     # show cluster nodes

# Taskfile equivalents (same names)
task health
task grpcurl
task redis-standalone-ping
# ...
```

### Changelog / Release

```bash
make changelog          # generate CHANGELOG.md via git-cliff
make changelog-check    # verify CHANGELOG.md is up to date
make release-dry-run    # print changelog without writing

# Taskfile equivalents
task changelog
task changelog:check
task changelog:dry-run
```

## In-memory demo

```bash
./gradlew :klimiter-core:runDemo
```

Produces a series of `OK` responses followed by `OVER_LIMIT` once the bucket is exhausted.

## Tests

```bash
# All tests (all modules)
./gradlew test

# Single module
./gradlew :klimiter-core:test
./gradlew :klimiter-redis:test
./gradlew :klimiter-service:test

# Single test class
./gradlew :klimiter-core:test --tests "io.klimiter.core.internal.coordinator.RateLimitCoordinatorTest"

# Continuous mode (reruns on source change)
./gradlew :klimiter-core:test --continuous

# Architecture boundary tests
./gradlew :klimiter-architecture-tests:test
```

Tests use `kotlin.test` with the JUnit 5 platform. No Spring context is loaded for core tests.

Inject `TimeProvider` (a fixed-clock implementation) to write deterministic tests for time-bucketed behaviour:

```kotlin
val fixedClock = FixedTimeProvider(Instant.parse("2024-01-01T00:00:00Z"))
val limiter = KLimiterBuilder()
    .domainRepository(domainRepository)
    .timeProvider(fixedClock)
    .build()

// advance time to the next window
fixedClock.advance(1.seconds)
```

## Static analysis

```bash
# Run Detekt + ktlint rules
./gradlew detekt

# Skip during development (re-run before submitting a PR)
./gradlew build -x detekt
```

Configuration: `config/detekt/detekt.yml`.

## Code coverage

Kover is wired to the root project. The `klimiter-redis` module is excluded from the aggregate report because it requires a live Redis instance (Testcontainers integration tests are not yet wired).

```bash
./gradlew koverHtmlReport
# report: build/reports/kover/html/index.html
```

## Regenerating proto stubs

```bash
./gradlew :klimiter-service:generateProto
```

Proto source: `klimiter-service/src/main/proto/klimiter.proto`
Generated package: `io.klimiter.service.proto`

## Environment variables

All service configuration is driven by environment variables (or `application.yaml`). Copy `.env.example` to `.env` and adjust as needed. Key variables:

| Variable | Default | Description |
| --- | --- | --- |
| `KLIMITER_BACKEND_MODE` | `IN_MEMORY` | `IN_MEMORY`, `REDIS_STANDALONE`, or `REDIS_CLUSTER` |
| `KLIMITER_BACKEND_REDIS_URI` | `redis://localhost:6379` | Used when mode is `REDIS_STANDALONE` |
| `KLIMITER_BACKEND_REDIS_URIS` | `redis://localhost:7001,...` | Comma-separated, used when mode is `REDIS_CLUSTER` |
| `KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE` | `10` | Local budget fraction (1–100) |
| `GRPC_PORT` | `9090` | gRPC server port |
| `SERVER_PORT` | `8080` | HTTP (Actuator) port |

See [CONFIGURATION.md](CONFIGURATION.md) for the full reference.
