# Configuration

## Domain rules

Domain rules drive rate-limit decisions. They are loaded from:

1. `config/klimiter-domains.yaml` — file relative to the working directory (preferred for runtime overrides)
2. `classpath:klimiter-domains.yaml` — bundled inside the JAR

Neither file is required. If neither exists, the service uses its built-in default rule (600 requests/second per domain).

### Schema

```yaml
klimiter:
  domains:
    - id: <domain-id>
      descriptors:
        - key: <entry-key>
          value: <entry-value>      # optional; omit for key-only match
          rule:
            unit: SECOND            # SECOND | MINUTE | HOUR | DAY | WEEK | MONTH | YEAR
            requests-per-unit: 100
            name: optional-label    # informational only
          children:                 # nested descriptors for hierarchical rules
            - key: <child-key>
              rule:
                unit: MINUTE
                requests-per-unit: 1000
```

### Matching precedence

For each descriptor entry in a request, the engine walks the configured descriptors in this order:

1. **Exact match** — `key` and `value` both match
2. **Wildcard match** — `key` matches and the configured `value` is a prefix of the request value (longest prefix wins)
3. **Key-only match** — `key` matches and no `value` is configured

A descriptor that matches but has no `rule` and no `children` is **whitelisted** — no rate-limit operation is produced for it.

### Unlimited rule

```yaml
- key: internal_service
  rule:
    unlimited: true
    requests-per-unit: 0    # required field, value ignored
```

### Hierarchical descriptors

Children are evaluated after the parent matches. This lets you express composite rules:

```yaml
- key: user_id
  rule:
    unit: MINUTE
    requests-per-unit: 1000
  children:
    - key: endpoint
      value: /search
      rule:
        unit: SECOND
        requests-per-unit: 10
```

A request carrying `user_id=u1` AND `endpoint=/search` will be checked against both the `user_id` minute limit and the `endpoint` per-second limit. All operations must pass (all-or-nothing).

## Environment variables

All variables have defaults; no required variables.

### Application

| Variable | Default | Description |
|---|---|---|
| `SPRING_APPLICATION_NAME` | `klimiter-service` | Application name in logs and metrics |
| `SERVER_PORT` | `8080` | HTTP server port (Actuator) |
| `GRPC_PORT` | `9090` | gRPC server port |

### Backend mode

| Variable | Default | Options |
|---|---|---|
| `KLIMITER_BACKEND_MODE` | `IN_MEMORY` | `IN_MEMORY`, `REDIS_STANDALONE`, `REDIS_CLUSTER` |

### Redis (used when `KLIMITER_BACKEND_MODE` is not `IN_MEMORY`)

| Variable | Default | Description |
|---|---|---|
| `KLIMITER_BACKEND_REDIS_URI` | `redis://localhost:6379` | Standalone Redis URI |
| `KLIMITER_BACKEND_REDIS_URIS` | `redis://localhost:7001,...` | Comma-separated cluster node URIs |
| `KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE` | `10` | Lease slice per node (1–100); lower = fairer, higher = faster |
| `KLIMITER_BACKEND_REDIS_KEY_PREFIX` | `klimiter` | Key prefix for all Redis keys |

### Observability

| Variable | Default | Description |
|---|---|---|
| `LOGGING_LEVEL_ROOT` | `INFO` | Root log level |
| `TRACING_SAMPLING_PROBABILITY` | `0` | Trace sampling (0 = off, 1 = all) |
| `OTLP_METRICS_EXPORT_ENABLED` | `false` | Enable OTLP metrics push |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP traces endpoint |
| `OTLP_LOGGING_ENDPOINT` | `http://localhost:4318/v1/logs` | OTLP logs endpoint |

### Actuator

| Variable | Default | Description |
|---|---|---|
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics,env,configprops` | Exposed Actuator endpoints |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true` | Enable liveness/readiness probes |

## Backend modes

### IN_MEMORY

The default. Uses Caffeine for local bucket storage. No external dependencies. Suitable for single-node deployments or development.

Each node tracks its own counters independently, so in a multi-instance setup each node enforces the full limit rather than a shared limit.

### REDIS_STANDALONE

All nodes share counters in a single Redis instance. Provides accurate distributed rate limiting. Uses the lease pattern to reduce Redis round-trips.

```bash
KLIMITER_BACKEND_MODE=REDIS_STANDALONE
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

### REDIS_CLUSTER

Same as standalone but uses Redis Cluster topology. All Lua scripts operate on a single key, so cross-slot concerns do not apply.

```bash
KLIMITER_BACKEND_MODE=REDIS_CLUSTER
KLIMITER_BACKEND_REDIS_URIS=redis://node1:7001,redis://node2:7002,redis://node3:7003
```

## `.env` file

Copy `.env.example` to `.env` for local development:

```bash
cp .env.example .env
```

The service loads `.env` automatically when present alongside the JAR. Docker Compose also injects it via `env_file`.

Do not commit `.env` to source control. Only `.env.example` belongs in the repository.

## KLimiter library (non-service usage)

When using `klimiter-core` or `klimiter-redis` as a library directly (not via the Spring service), configure the limiter programmatically:

```kotlin
// In-memory
val limiter = KLimiterBuilder()
    .domainRepository(domainRepository)
    .maxCacheSize(10_000)
    .gracePeriod(30.seconds)
    .build()

// Redis with custom config
val config = RedisKLimiterConfig(
    leasePercentage = 5,
    keyPrefix = "myapp"
)
val limiter = RedisKLimiterFactory.standalone(
    uri = "redis://localhost:6379",
    domainRepository = domainRepository,
    config = config
)
```
