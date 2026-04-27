# Troubleshooting

## Service won't start

### Port already in use

```
Caused by: java.net.BindException: Address already in use
```

Check what is using the port:

```bash
lsof -i :9090
lsof -i :8080
```

Change the port via environment variable:

```bash
GRPC_PORT=9091 SERVER_PORT=8081 ./gradlew :klimiter-service:bootRun
```

### Missing JDK 21

```
error: invalid source release 21
```

The project requires JDK 21. The Gradle toolchain will try to download it automatically. If your environment blocks downloads, install JDK 21 manually and set `JAVA_HOME`.

### Domain config file not found

The service starts fine without a domain config file — it falls back to the default rule (600 requests/second). If you expect rules from a file but they are not applying, check:

```bash
ls config/klimiter-domains.yaml     # relative to working directory
```

The YAML key must be `klimiter.domains`, not `domains`:

```yaml
klimiter:
  domains:
    - id: default
      ...
```

## Rate limits not being enforced

### Wrong domain ID

Requests use a `domain` field. The domain must match the `id` in your configuration. Default is `"default"`.

### Descriptor not matching

Enable `TRACE` logging to see matching decisions:

```bash
LOGGING_LEVEL_ROOT=TRACE ./gradlew :klimiter-service:bootRun
```

Matching is case-sensitive. `user_id=Alice` does not match a rule for `user_id=alice`.

### Multiple instances without Redis

In `IN_MEMORY` mode, each instance tracks counters independently. If you run three instances and expect a shared 100 req/s limit, each instance will allow 100 req/s — giving you 300 req/s aggregate. Switch to `REDIS_STANDALONE` or `REDIS_CLUSTER` to share state.

### Lease accumulation across nodes

With Redis backend, each node holds a local lease slice. A node can accept up to `leasePercentage` % of the window limit before contacting Redis. If exact per-request accuracy is required, reduce `leasePercentage` at the cost of more Redis calls.

## Redis connection issues

### Connection refused

```
io.lettuce.core.RedisConnectionException: Unable to connect to localhost:6379
```

Check that Redis is running:

```bash
docker compose --profile redis_standalone up -d
docker compose exec redis-standalone redis-cli ping
```

Verify the URI in your environment:

```bash
echo $KLIMITER_BACKEND_REDIS_URI
```

Inside Docker containers, `localhost` refers to the container itself, not the host. Use the service name:

```bash
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

### Redis Cluster not forming

Check the cluster init container logs:

```bash
docker compose logs redis-cluster-init
```

Verify cluster state:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster info
```

`cluster_state:ok` confirms the cluster is healthy. If it shows `cluster_state:fail`, wait a few seconds and retry — the init container may still be forming the cluster.

### NOSCRIPT errors

```
NOSCRIPT No matching script
```

This happens after a Redis restart or failover. The Lua scripts are transparently reloaded on the next call. If the error persists beyond one request, check Redis logs for script loading failures.

## gRPC errors

### UNAVAILABLE — service not ready

```
StatusRuntimeException: UNAVAILABLE: io exception
```

The service is not reachable. Check:

1. Service is running: `curl http://localhost:8080/actuator/health`
2. gRPC port is correct (default 9090)
3. No TLS mismatch — the service uses plaintext by default; `grpcurl` needs `-plaintext`

### UNKNOWN — rate limit ERROR status

The service returns `overall_code: UNKNOWN` or a status of `ERROR` when an internal exception occurs. Check service logs for the root cause. Common causes: Redis connection failure, missing domain config.

### Request not matching any descriptor

If no descriptor matches, the request is treated as whitelisted (no operation produced) and returns `OK`. Add a catch-all key-only descriptor if you want to limit all unmatched traffic:

```yaml
- key: remote_address
  rule:
    unit: SECOND
    requests-per-unit: 1000
```

## Performance issues

### High Redis call rate

If Redis CPU is elevated, the node is renewing leases too frequently. Increase `leasePercentage`:

```bash
KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE=25
```

### High coordinator latency

The coordinator executes operations sequentially. If a request has many descriptors (e.g. ten entries in a single request), each one adds a Redis round-trip on lease exhaustion. Batch fewer descriptors per request or increase the lease size.

### Caffeine key expiry delay

The in-memory store uses `expireAfterCreate = windowSeconds + gracePeriod`. Old buckets linger for up to `gracePeriod` (default 30 s) after their window ends. This is intentional — see the "concurrent-window leak" note in [klimiter-core/README.md](../klimiter-core/README.md). Reduce `gracePeriod` only if memory is a concern and you understand the trade-off.

### Redis key TTL shorter than expected

The Redis key TTL is `windowSeconds + redisKeyGracePeriod`. This is controlled by `RedisKLimiterConfig.redisKeyGracePeriod` (default 10 s), which is intentionally separate from `gracePeriod` (the Caffeine local-cache grace, default 30 s). The Redis TTL only needs a small buffer to absorb in-flight lease renewals at the window boundary; it does not need to guard against the concurrent-window leak that drives the larger Caffeine value.

If keys expire before the window ends, increase `redisKeyGracePeriod`:

```kotlin
RedisKLimiterConfig(redisKeyGracePeriod = 5.seconds)
```

## Build issues

### Detekt violations

```
> Task :klimiter-core:detekt FAILED
```

Fix the reported violations or skip for now:

```bash
./gradlew build -x detekt
```

### Proto stubs out of date

If the gRPC adapter fails to compile after editing the proto file:

```bash
./gradlew :klimiter-service:generateProto
```

Then rebuild:

```bash
./gradlew :klimiter-service:build -x detekt
```

## Checking logs

```bash
# Service running via Gradle
./gradlew :klimiter-service:bootRun 2>&1 | tee service.log

# Docker standalone
docker compose logs -f app-standalone

# Docker cluster
docker compose logs -f app-1 app-2 app-3

# Redis Cluster init
docker compose logs redis-cluster-init
```

## Load testing and high-volume traffic

Detailed guides in [`docs/troubleshooting/`](./troubleshooting/):

| Guide | When to use |
|---|---|
| [High-volume load testing](./troubleshooting/HIGH_VOLUME_LOAD_TESTING.md) | k6 + gRPC + Nginx + Docker: tuning and symptom interpretation |
| [Redis Cluster local connection](./troubleshooting/REDIS_CLUSTER_LOCAL_APP_CONNECTION.md) | App can't reach Redis Cluster nodes from outside Docker |
| [Redis TTL clock skew](./troubleshooting/REDIS_TTL_CLOCK_SKEW.md) | Keys expire too early or too late in Windows + WSL + Docker |

### Capturing full gRPC responses for debugging

Use `--save-responses` to write every gRPC response body to a file alongside the normal results:

```bash
python3 runner.py run --save-responses
```

This creates `runs/<run_id>/responses.jsonl`. Each line is a JSON log envelope from k6; the `msg` field contains the serialized response. To inspect:

```bash
cat runs/<run_id>/responses.jsonl | python3 -c "
import sys, json
for line in sys.stdin:
    row = json.loads(line)
    inner = json.loads(row['msg'])
    if inner.get('type') == 'response':
        print(json.dumps(inner, indent=2))
        break
"
```

At high request rates (e.g. 4000 req/s) this file grows quickly. Use only for short diagnostic runs.
