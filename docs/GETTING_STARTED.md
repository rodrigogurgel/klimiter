# Getting Started

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21+ |
| Docker + Compose | for Redis profiles |
| grpcurl | optional, for manual testing |

## Clone and build

```bash
git clone <repo-url>
cd klimiter

# Build all modules (skip detekt if you want faster first build)
./gradlew build -x detekt
```

## Run the in-memory demo

The demo spins up an in-memory limiter and makes several requests to show the allow/deny flow:

```bash
./gradlew :klimiter-core:runDemo
```

Expected output shows a series of `OK` responses followed by `OVER_LIMIT` once the bucket is exhausted.

## Run the gRPC service

### Option A — directly with Gradle

```bash
./gradlew :klimiter-service:bootRun
```

The service starts on gRPC port **9090** and HTTP port **8080** (Actuator) using the in-memory backend.

### Option B — Docker Compose (standalone Redis)

```bash
cp .env.example .env
docker compose --profile app_standalone up -d --build
```

This starts Redis standalone, Redis Insight (UI), and one instance of the application.

## Send your first request

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

Expected response:

```json
{
  "overallCode": "OK",
  "statuses": [
    {
      "code": "OK",
      "currentLimit": {
        "requestsPerUnit": 600,
        "unit": "SECOND"
      }
    }
  ]
}
```

## Check health

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{"status": "UP"}
```

## Configure domain rules

The service loads domain rules from `config/klimiter-domains.yaml` (relative to the working directory) or `classpath:klimiter-domains.yaml`. Create the file to override the default rule:

```yaml
klimiter:
  domains:
    - id: default
      descriptors:
        - key: user_id
          rule:
            unit: SECOND
            requests-per-unit: 100
        - key: plan
          value: free
          rule:
            unit: MINUTE
            requests-per-unit: 60
        - key: plan
          value: pro
          rule:
            unit: MINUTE
            requests-per-unit: 6000
```

Restart the service to pick up new rules.

## Next steps

- [Configuration](CONFIGURATION.md) — full reference for domain rules and environment variables
- [Redis Backend](REDIS.md) — switch to Redis standalone or cluster
- [Docker Deployment](DOCKER.md) — multi-instance cluster with Nginx
- [Testing](TESTING.md) — run unit, architecture, and load tests
