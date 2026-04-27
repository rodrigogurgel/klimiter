# Redis TTL inconsistency caused by clock skew in Windows + WSL + Docker

← [Troubleshooting](../TROUBLESHOOTING.md)

## Overview

In local environments that use **Windows + WSL + Docker Desktop**, Redis keys with TTL may appear to expire too early, live longer than expected, or behave inconsistently between test runs.

In this scenario, the issue may **not** be caused by Redis TTL itself, key overwrites, or eviction. A common root cause is **clock skew** between one or more layers of the environment:

- Windows host
- WSL distribution
- Docker runtime / container
- Redis process time source

Redis expiration is based on **absolute Unix timestamps**. If the system clock moves forward or backward unexpectedly, TTL-based behavior becomes unreliable.

---

## Symptoms

You may be facing this issue if one or more of the following happens:

- A key is created with `EX 60` or `PX 60000`, but disappears after only a few seconds.
- `PTTL` unexpectedly **increases** during a loop instead of only decreasing.
- `PEXPIRETIME` is initially correct, but later the key no longer exists much earlier than expected.
- `redis-cli TIME` appears to **jump backward** or forward unexpectedly.
- The same test works once and fails on the next run without any application code changes.

---

## What this is not

Before assuming clock skew, validate that the issue is **not** caused by one of the following:

- Another process deleting the key (`DEL`, `UNLINK`, `FLUSHALL`, `FLUSHDB`, scripts, etc.)
- Key overwrite by another `SET`
- `maxmemory` eviction
- Container/process restart
- Different Redis instance/endpoint than expected

If those have already been ruled out, clock instability becomes a strong suspect.

---

## Why it happens

Redis stores expiration internally using **absolute time**, not just a relative countdown.

That means the following are equivalent in practice:

- "expire in 60 seconds"
- "expire at exact Unix timestamp X"

If the environment clock moves:

- **forward**: the key may expire earlier than expected
- **backward**: `PTTL` may grow unexpectedly and the key may live longer than expected

This is especially relevant in environments using:

- WSL2
- Docker Desktop with WSL backend
- laptops resuming from sleep/hibernate
- host time re-synchronization while containers are already running

---

## How to diagnose

### 1. Validate Redis time directly

Run multiple sequential reads of Redis server time:

```bash
for i in $(seq 1 10); do
  docker compose exec redis redis-cli TIME
  sleep 1
done
```

Expected behavior:

- time should always move **forward**
- it should never jump backward

If `TIME` goes backward, the environment clock is unstable.

---

### 2. Validate TTL with an isolated key

Use a unique key to avoid collisions with other processes:

```bash
KEY="ttl-test:$$:$(date +%s)"
docker compose exec redis sh -c "
redis-cli SET $KEY 1 PX 10000 >/dev/null
echo initial_time=\$(redis-cli TIME | tr '\n' ' ')
echo initial_pexpiretime=\$(redis-cli PEXPIRETIME $KEY)
for i in \$(seq 1 12); do
  echo ----
  echo redis_time=\$(redis-cli TIME | tr '\n' ' ')
  echo pttl=\$(redis-cli PTTL $KEY)
  echo exists=\$(redis-cli EXISTS $KEY)
  sleep 1
done
"
```

Expected behavior:

- `redis_time` only moves forward
- `pttl` only decreases
- `exists=1` until expiration time is reached
- after expiration, `exists=0`

If `pttl` increases or `redis_time` jumps, suspect clock skew.

---

### 3. Check for external commands touching the key

In a separate terminal, run:

```bash
docker compose exec redis redis-cli MONITOR
```

Then execute your TTL test again.

If you see commands such as:

- `DEL`
- `UNLINK`
- `FLUSHALL`
- `FLUSHDB`
- another `SET`
- `EVAL` / Lua script touching the same key

then the key is being modified externally and the problem is not clock-related.

---

### 4. Check container logs for restart evidence

```bash
docker compose logs --tail=200 redis
```

Look for repeated startup messages such as:

- `Redis is starting`
- `Ready to accept connections`
- RDB/AOF reload sequences during the failing window

If Redis restarted, the issue may be process/container lifecycle instead of time drift.

---

### 5. Compare time across all environment layers

Check Windows, WSL, container, and Redis:

**Windows (PowerShell):**

```powershell
Get-Date -Format o
w32tm /query /status
w32tm /query /source
```

**WSL:**

```bash
date -u
date +%s
powershell.exe -NoProfile -Command "Get-Date -Format o"
```

**Container:**

```bash
docker compose exec redis sh -c 'date -u; date +%s'
```

**Redis:**

```bash
docker compose exec redis redis-cli TIME
```

These values should be aligned. Small execution differences are normal. Large differences or time jumps are not.

---

## Practical example of a clock skew signal

A strong sign of environment time instability is when this pattern happens:

1. `SET key PX 60000`
2. `PEXPIRETIME` shows expiration ~60 seconds in the future
3. a few seconds later, `redis-cli TIME` moves **backward**
4. `PTTL` suddenly becomes larger than before

That behavior indicates that Redis is still using absolute expiration time correctly, but the system time source being observed is unstable.

---

## Resolution

### Recommended first fix

Shut down WSL completely and restart the local stack:

```bash
wsl --shutdown
```

Then:

1. reopen WSL
2. restart Docker Desktop
3. start the containers again
4. rerun the Redis time and TTL tests

In many local setups, this is enough to restore clock synchronization.

---

### Additional WSL workaround

If the issue persists, try reloading the hardware/system clock inside WSL:

```bash
sudo hwclock -s
```

Depending on the environment, this may help resync WSL time with the host.

---

### Operational recommendation

If your machine was suspended, hibernated, or resumed after being idle for a long time, and Redis TTL tests start behaving strangely:

1. stop the local stack
2. run `wsl --shutdown`
3. reopen Docker Desktop and WSL
4. start the stack again
5. rerun the monotonic time test

---

## Validation after the fix

After restarting the environment, run this short validation:

```bash
KEY="ttl-test:$$:$(date +%s)"
docker compose exec redis sh -c "
redis-cli SET $KEY 1 PX 10000 >/dev/null
echo initial_time=\$(redis-cli TIME | tr '\n' ' ')
echo initial_pexpiretime=\$(redis-cli PEXPIRETIME $KEY)
for i in \$(seq 1 12); do
  echo redis_time=\$(redis-cli TIME | tr '\n' ' ')
  echo pttl=\$(redis-cli PTTL $KEY)
  echo exists=\$(redis-cli EXISTS $KEY)
  sleep 1
done
"
```

You can consider the issue resolved if:

- `redis_time` is monotonic
- `pttl` only decreases
- the key expires only when expected
- repeated runs produce consistent results

---

**See also:** [Redis Backend](../REDIS.md) — key TTL model (`windowSeconds + redisKeyGracePeriod`) and monitoring metrics.
