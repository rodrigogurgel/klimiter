# Testing

## Unit tests

Tests use `kotlin.test` with the JUnit 5 platform. No Spring context is loaded for core tests.

### Run all tests

```bash
./gradlew test
```

### Run tests for a specific module

```bash
./gradlew :klimiter-core:test
./gradlew :klimiter-redis:test
./gradlew :klimiter-service:test
```

### Run a single test class

```bash
./gradlew :klimiter-core:test --tests "io.klimiter.core.internal.coordinator.RateLimitCoordinatorTest"
```

### Run with continuous rebuild

```bash
./gradlew :klimiter-core:test --continuous
```

### Skip detekt during test runs

```bash
./gradlew :klimiter-core:test -x detekt
```

## Architecture tests

The [`klimiter-architecture-tests`](../klimiter-architecture-tests/README.md) module uses [Konsist](https://docs.konsist.lemonappdev.com/) to validate module boundaries and package structure.

```bash
./gradlew :klimiter-architecture-tests:test
```

Rules enforced:

- `klimiter-core` has no dependency on Redis, Spring, or gRPC
- Core SPI (`api.spi`) does not import core internal implementation
- `klimiter-redis` does not depend on `klimiter-service`
- Service domain does not depend on adapters, Spring, gRPC, core, or Redis
- Service application layer does not depend on transport or backend adapters
- Hexagonal package layout exists in the service module

The `StrictApiBoundaryArchitectureTest` is disabled intentionally. Public factory facades in `api` instantiate internal implementations by design — the strict boundary check would fail on them. Enable it only if you move all internal instantiation behind a different mechanism.

## Testing time-sensitive behaviour

`TimeProvider` is part of the SPI precisely to enable deterministic tests. Inject a fixed-clock instance when testing window boundaries:

```kotlin
val fixedClock = FixedTimeProvider(Instant.parse("2024-01-01T00:00:00Z"))

val limiter = KLimiterBuilder()
    .domainRepository(domainRepository)
    .timeProvider(fixedClock)
    .build()
```

Advance the clock to simulate the next window:

```kotlin
fixedClock.advance(1.seconds)
```

## Load tests

Load tests live in `klimiter-load-test/` and use [k6](https://k6.io/) for gRPC load generation.

### Prerequisites

```bash
pip3 install -r klimiter-load-test/requirements.txt
# k6 must be installed separately: https://k6.io/docs/get-started/installation/
```

### Run with default script

```bash
cd klimiter-load-test
python3 runner.py run
```

### Run with a custom script

```bash
python3 runner.py run --script k6/another-test.js
```

### Rebuild HTML report index

```bash
python3 runner.py rebuild-index
```

Reports are stored in `klimiter-load-test/runs/`. The HTML index supports light/dark theme toggle via `localStorage`.

### Load test configuration

Edit `klimiter-load-test/k6/rate-limit.js` to adjust virtual users, duration, and request parameters. The request payload lives in `requests/rate-limit-request.json`.

Make sure the gRPC service is running before starting a load test:

```bash
docker compose --profile app_cluster up -d --build
```

## Recommended tests to add

The following test categories are missing and should be added before considering the project production-ready:

### klimiter-redis

- Lua script output shape parsing
- `NOSCRIPT` reload behaviour (simulate Redis restart)
- Standalone Redis integration test with Testcontainers
- Redis Cluster integration test (slot distribution, script behaviour)
- Lease percentage edge cases (0%, 100%, fractional results)
- Local leased bucket exhaustion and renewal under concurrent load

### klimiter-service

- `KLimiterProperties` binding (verify environment variable mapping)
- `KLimiterConfiguration` — backend mode selection (in-memory, standalone, cluster)
- gRPC mapper tests (proto ↔ domain round-trip)
- `CheckRateLimitService` use-case tests with a fake `RateLimitEnforcerPort`

## Static analysis

Detekt with ktlint rules runs as part of the build:

```bash
./gradlew detekt
```

To skip during development:

```bash
./gradlew build -x detekt
```

Configuration: `config/detekt/detekt.yml`
