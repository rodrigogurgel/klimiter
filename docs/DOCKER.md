# Docker — KLimiter

This document describes how to run the project locally with Docker Compose, using Redis standalone, Redis Cluster, multiple application instances, and Nginx as a gRPC load balancer.

## Files involved

Expected structure:

```text
.
├── .env
├── .env.example
├── docker-compose.yaml
├── Dockerfile
├── docker/
│   ├── nginx/
│   │   └── nginx.conf
│   └── redis-cluster/
│       └── cluster-init.sh
└── klimiter-service/
```

## `.env`

The `.env` file contains the real values used locally by Docker Compose and the application.

Create `.env` from `.env.example`:

```bash
cp .env.example .env
```

Example:

```env
# Application
SPRING_APPLICATION_NAME=klimiter-service

# HTTP server
SERVER_PORT=8080

# gRPC server
GRPC_PORT=9090

# Actuator / Management
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,env,configprops
MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true

# Tracing
TRACING_SAMPLING_PROBABILITY=0

# OTLP Metrics
OTLP_METRICS_EXPORT_ENABLED=false

# OTLP Tracing
OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces

# OTLP Logging
OTLP_LOGGING_ENDPOINT=http://localhost:4318/v1/logs

# Logging
LOGGING_LEVEL_ROOT=INFO

# KLimiter backend
# Options: IN_MEMORY, REDIS_STANDALONE, REDIS_CLUSTER
KLIMITER_BACKEND_MODE=IN_MEMORY

# Redis standalone
KLIMITER_BACKEND_REDIS_URI=redis://localhost:6379

# Redis cluster
KLIMITER_BACKEND_REDIS_URIS=redis://localhost:7001,redis://localhost:7002,redis://localhost:7003

# Redis lease configuration
KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE=10
KLIMITER_BACKEND_REDIS_KEY_PREFIX=klimiter
```

## Difference between `.env` and `.env.example`

`.env.example` must be committed to Git as a configuration template.

`.env` must contain the real local environment values and must not be committed.

Add to `.gitignore`:

```gitignore
.env
```

## Using `env_file`

`docker-compose.yaml` uses:

```yaml
env_file:
  - .env
```

This injects the `.env` variables inside the application containers.

Some variables are overridden directly in each service's `environment` block, because `localhost` inside a Docker container refers to the container itself — not the host.

Example:

```yaml
environment:
  KLIMITER_BACKEND_MODE: REDIS_STANDALONE
  KLIMITER_BACKEND_REDIS_URI: redis://redis-standalone:6379
```

Inside a container, the application must reach Redis using the Docker service name:

```text
redis-standalone
redis-cluster-1
redis-cluster-2
redis-cluster-3
```

## Available profiles

Docker Compose is organised with the following profiles:

| Profile | Description |
|---|---|
| `redis_standalone` | Starts Redis standalone and Redis Insight |
| `redis_cluster` | Starts Redis Cluster and Redis Insight |
| `app_standalone` | Starts Redis standalone, Redis Insight, and one application instance |
| `app_cluster` | Starts Redis Cluster, Redis Insight, three application instances, and Nginx |

## Start Redis standalone

```bash
docker compose --profile redis_standalone up -d
```

This profile starts:

```text
redis-standalone
redis-insight
```

Access:

```text
Redis: localhost:6379
Redis Insight: http://localhost:5540
```

## Start Redis Cluster

```bash
docker compose --profile redis_cluster up -d
```

This profile starts:

```text
redis-cluster-1
redis-cluster-2
redis-cluster-3
redis-cluster-init
redis-insight
```

External access to nodes:

```text
redis-cluster-1: localhost:7001
redis-cluster-2: localhost:7002
redis-cluster-3: localhost:7003
```

Inside the Docker network, the application uses:

```text
redis://redis-cluster-1:7001
redis://redis-cluster-2:7002
redis://redis-cluster-3:7003
```

## Start application with Redis standalone

```bash
docker compose --profile app_standalone up -d --build
```

This profile starts:

```text
redis-standalone
redis-insight
app-standalone
```

Exposed ports:

```text
HTTP:         localhost:8080
gRPC:         localhost:9090
Redis Insight: http://localhost:5540
```

In this mode, the application uses:

```env
KLIMITER_BACKEND_MODE=REDIS_STANDALONE
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

## Start application in cluster mode with Nginx

```bash
docker compose --profile app_cluster up -d --build
```

This profile starts:

```text
redis-cluster-1
redis-cluster-2
redis-cluster-3
redis-cluster-init
redis-insight
app-1
app-2
app-3
nginx
```

Exposed ports:

```text
gRPC via Nginx: localhost:9090
Redis Insight:  http://localhost:5540
```

In this mode, the application uses:

```env
KLIMITER_BACKEND_MODE=REDIS_CLUSTER
KLIMITER_BACKEND_REDIS_URIS=redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

Nginx distributes gRPC calls across:

```text
app-1:9090
app-2:9090
app-3:9090
```

## Test health check

For the standalone application:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP"
}
```

In `app_cluster` mode, the HTTP port of each application instance is not exposed directly. Instances are reachable only inside the Docker network.

## Test gRPC with `grpcurl`

Example to test the gRPC port:

```bash
grpcurl \
  -plaintext \
  -emit-defaults \
  -d '{"descriptors":[{"entries":[{"key":"user_id","value":"user_1"}]}],"domain":"default"}' \
  localhost:9090 \
  io.klimiter.RateLimitService.ShouldRateLimit
```

In the `app_standalone` profile, the call goes directly to the application.

In the `app_cluster` profile, the call goes to Nginx, which load-balances across instances.

## Rebuild applications

When you change application code or the Dockerfile:

```bash
docker compose --profile app_standalone up -d --build
```

or:

```bash
docker compose --profile app_cluster up -d --build
```

To force a rebuild with no cache:

```bash
docker compose build --no-cache
```

Then start again:

```bash
docker compose --profile app_cluster up -d
```

## Stop containers

Stop project containers:

```bash
docker compose down
```

Stop and remove volumes:

```bash
docker compose down -v
```

Use `-v` when you want to fully wipe local Redis data.

## View logs

All services:

```bash
docker compose logs -f
```

Standalone application:

```bash
docker compose logs -f app-standalone
```

Cluster applications:

```bash
docker compose logs -f app-1 app-2 app-3
```

Nginx:

```bash
docker compose logs -f nginx
```

Redis Cluster init:

```bash
docker compose logs -f redis-cluster-init
```

## View active containers

```bash
docker compose ps
```

## Run commands in Redis standalone

```bash
docker compose exec redis-standalone redis-cli ping
```

Expected result:

```text
PONG
```

## Run commands in Redis Cluster

Enter a node:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001
```

View cluster info:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster info
```

View cluster nodes:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster nodes
```

## Redis Insight

Redis Insight starts in the following profiles:

```text
redis_standalone
redis_cluster
app_standalone
app_cluster
```

Access:

```text
http://localhost:5540
```

Suggested connections:

### Standalone

```text
Host:  redis-standalone
Port:  6379
Alias: standalone
```

### Cluster

```text
Host:  redis-cluster-1
Port:  7001
Alias: cluster-node-1
```

## OTLP observability

By default in the local `.env`:

```env
OTLP_METRICS_EXPORT_ENABLED=false
OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces
OTLP_LOGGING_ENDPOINT=http://localhost:4318/v1/logs
```

If a collector or Grafana LGTM stack is running inside Docker Compose, do not use `localhost` inside the application. Use the Docker service name:

```env
OTLP_TRACING_ENDPOINT=http://otel-lgtm:4318/v1/traces
OTLP_LOGGING_ENDPOINT=http://otel-lgtm:4318/v1/logs
```

## Note on `localhost`

Outside Docker:

```env
KLIMITER_BACKEND_REDIS_URI=redis://localhost:6379
```

Inside Docker:

```env
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

Outside Docker, `localhost` refers to your machine. Inside a container, `localhost` refers to the container itself. That is why `docker-compose.yaml` overrides Redis URIs in the application service definitions.

## Recommended strategy

Use `.env` for operational configuration:

```text
ports
logs
observability
backend mode
Redis URI
key prefix
lease percentage
```

Keep complex domain rules in YAML:

```yaml
klimiter:
  domains:
    - id: default
      descriptors:
        - key: user_id
          rule:
            unit: SECOND
            requests-per-unit: 100
```

Nested objects and lists are hard to maintain as environment variables. For rate-limit rules, YAML is more readable and less error-prone.

## Quick reference

Redis standalone:

```bash
docker compose --profile redis_standalone up -d
```

Redis Cluster:

```bash
docker compose --profile redis_cluster up -d
```

App standalone:

```bash
docker compose --profile app_standalone up -d --build
```

App cluster with Nginx:

```bash
docker compose --profile app_cluster up -d --build
```

Stop everything:

```bash
docker compose down
```

Stop and wipe volumes:

```bash
docker compose down -v
```
