# klimiter-service

Runnable Spring Boot 4 application that exposes KLimiter through a gRPC interface compatible with the [Envoy Rate Limit Service protocol](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/rate_limit_filter).

## Architecture

Hexagonal layout:

```
adapter/input/grpc      ← gRPC transport (RateLimitGrpcAdapter)
application             ← use case (CheckRateLimitService)
domain/model            ← pure Kotlin types
domain/port             ← input/output port interfaces
adapter/output/klimiter ← KLimiter integration (KLimiterCoreAdapter)
config                  ← Spring beans and properties binding
```

## Running locally

```bash
# In-memory mode (default)
./gradlew :klimiter-service:bootRun

# With a specific backend
KLIMITER_BACKEND_MODE=REDIS_STANDALONE \
KLIMITER_BACKEND_REDIS_URI=redis://localhost:6379 \
./gradlew :klimiter-service:bootRun
```

Ports:

| Port | Protocol | Purpose |
|---|---|---|
| 9090 | gRPC (plaintext) | Rate limit requests |
| 8080 | HTTP | Spring Boot Actuator |

## gRPC API

**Service:** `io.klimiter.RateLimitService`

**Method:** `ShouldRateLimit(RateLimitRequest) → RateLimitResponse`

```proto
message RateLimitRequest {
  string domain = 1;
  repeated RateLimitDescriptor descriptors = 2;
  uint32 hits_addend = 3;
}

message RateLimitDescriptor {
  repeated Entry entries = 1;
  RateLimitOverride limit = 2;
  google.protobuf.UInt64Value hits_addend = 8;
}

message RateLimitResponse {
  Code overall_code = 1;
  repeated DescriptorStatus statuses = 2;

  enum Code {
    UNKNOWN = 0;
    OK = 1;
    OVER_LIMIT = 2;
  }
}
```

### Example with grpcurl

```bash
grpcurl \
  -plaintext \
  -emit-defaults \
  -d '{
    "domain": "default",
    "descriptors": [
      {"entries": [{"key": "user_id", "value": "user_1"}]}
    ]
  }' \
  localhost:9090 \
  io.klimiter.RateLimitService.ShouldRateLimit
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_APPLICATION_NAME` | `klimiter-service` | Application name |
| `SERVER_PORT` | `8080` | HTTP port |
| `GRPC_PORT` | `9090` | gRPC port |
| `KLIMITER_BACKEND_MODE` | `IN_MEMORY` | Backend: `IN_MEMORY`, `REDIS_STANDALONE`, `REDIS_CLUSTER` |
| `KLIMITER_BACKEND_REDIS_URI` | `redis://localhost:6379` | Redis standalone URI |
| `KLIMITER_BACKEND_REDIS_URIS` | `redis://localhost:7001,...` | Redis cluster URIs (comma-separated) |
| `KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE` | `10` | Lease percentage (1–100) |
| `KLIMITER_BACKEND_REDIS_KEY_PREFIX` | `klimiter` | Redis key prefix |
| `LOGGING_LEVEL_ROOT` | `INFO` | Root log level |
| `TRACING_SAMPLING_PROBABILITY` | `0` | Trace sampling probability (0–1) |
| `OTLP_METRICS_EXPORT_ENABLED` | `false` | Enable OTLP metrics export |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP tracing endpoint |
| `OTLP_LOGGING_ENDPOINT` | `http://localhost:4318/v1/logs` | OTLP logging endpoint |

## Domain configuration

Domain rules are loaded from `config/klimiter-domains.yaml` (outside the JAR) or `classpath:klimiter-domains.yaml`:

```yaml
klimiter:
  domains:
    - id: default
      descriptors:
        - key: user_id
          rule:
            unit: SECOND
            requests-per-unit: 100
        - key: user_id
          value: premium
          rule:
            unit: SECOND
            requests-per-unit: 1000
```

## Health check

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{"status": "UP"}
```

## Docker

See [docs/DOCKER.md](../docs/DOCKER.md) for all Docker Compose profiles.

Quick start:

```bash
cp .env.example .env
docker compose --profile app_standalone up -d --build
```

## Building

```bash
./gradlew :klimiter-service:build -x detekt
./gradlew :klimiter-service:bootRun

# Regenerate proto stubs
./gradlew :klimiter-service:generateProto
```
