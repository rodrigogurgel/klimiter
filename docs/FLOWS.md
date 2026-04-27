# Request Flows

All rate-limit checks are coroutine-suspended (`suspend`). The diagrams below use the actual class names from the source tree.

**See also:** [Architecture](ARCHITECTURE.md) — component overview · [Algorithms](ALGORITHMS.md) — decision flowcharts and state machines

---

## 1. Happy path — single descriptor, in-memory backend

The coordinator's **single-operation fast path** skips list allocation entirely.

```mermaid
sequenceDiagram
    autonumber
    participant C  as Client (Envoy)
    participant GA as RateLimitGrpcAdapter
    participant UC as CheckRateLimitService
    participant CA as KLimiterCoreAdapter
    participant KL as DefaultKLimiter
    participant OF as DefaultRateLimitOperationFactory
    participant CO as RateLimitCoordinator
    participant OP as InMemoryRateLimitOperation

    C->>GA: ShouldRateLimit(RateLimitRequest)
    GA->>GA: request.toCoreRequest()\n(proto → core domain, hitsAddend default=1)
    GA->>UC: check(coreRequest)
    UC->>CA: enforce(coreRequest)

    CA->>KL: kLimiter.shouldRateLimit(request)\n(wrapped in runCatching)
    KL->>OF: operationFactory.create(request)

    Note over OF: 1. findById(domain) — found<br/>2. bestMatch path: exact → wildcard → key-only<br/>3. CompositeKeyGenerator.generate() →<br/>   "klimiter|domain|k=v|windowStart"<br/>4. InMemoryRateLimitStore.getOrCreate(key, ttl)
    OF-->>KL: [InMemoryRateLimitOperation]

    KL->>CO: RateLimitCoordinator.execute([op])
    Note over CO: Single-op fast path — no list allocation

    CO->>OP: execute()
    Note over OP: CAS loop:<br/>  current = counter.get()<br/>  projected = current + hitsAddend<br/>  projected ≤ max → compareAndSet(current, projected)<br/>  reserved = hitsAddend
    OP-->>CO: RateLimitStatus(OK, limitRemaining, durationUntilReset)

    CO-->>KL: RateLimitResponse(overallCode=OK, [status])
    KL-->>CA: RateLimitResponse(OK)
    CA-->>UC: RateLimitResponse(OK)
    UC-->>GA: RateLimitResponse(OK)
    GA->>GA: coreResponse.toProtoResponse()
    GA-->>C: RateLimitResponse(overall_code=OK)
```

---

## 2. Rollback path — multi-descriptor, second descriptor over limit

The coordinator **always executes all operations first**, then resolves the overall code, then rolls back every reservation that succeeded.

```mermaid
sequenceDiagram
    autonumber
    participant C   as Client
    participant KL  as DefaultKLimiter
    participant OF  as DefaultRateLimitOperationFactory
    participant CO  as RateLimitCoordinator
    participant OP1 as InMemoryRateLimitOperation[0]
    participant OP2 as InMemoryRateLimitOperation[1]

    C->>KL: shouldRateLimit(request — 2 descriptors)
    KL->>OF: create(request)
    OF-->>KL: [op1, op2]

    KL->>CO: execute([op1, op2])
    Note over CO: executeMultiple path

    CO->>OP1: execute()
    Note over OP1: projected ≤ max<br/>compareAndSet succeeds<br/>reserved = hitsAddend
    OP1-->>CO: RateLimitStatus(OK)

    CO->>OP2: execute()
    Note over OP2: projected > max<br/>reserved stays 0
    OP2-->>CO: RateLimitStatus(OVER_LIMIT)

    Note over CO: resolve([OK, OVER_LIMIT])<br/>→ overallCode = OVER_LIMIT<br/><br/>rollbackSuccessful: only ops<br/>where result.code == OK

    CO->>OP1: rollback()
    Note over OP1: counter.addAndGet(-reserved)<br/>reserved = 0
    OP1-->>CO: (done)

    Note over CO: op2 not rolled back<br/>(reserved == 0, nothing to undo)

    CO-->>KL: RateLimitResponse(OVER_LIMIT, [OK, OVER_LIMIT])
    KL-->>C: RateLimitResponse(OVER_LIMIT)
```

> **Note on rollback failure**: each `rollback()` call is wrapped in `runCatching`. A failing rollback is logged as `WARN` but does not prevent the remaining rollbacks from running.

---

## 3. Redis backend — local budget available (no Redis round-trip)

The hot path is a single CAS against `LeasedBucket.localRemaining`. Redis is never contacted.

```mermaid
sequenceDiagram
    autonumber
    participant KL  as DefaultKLimiter
    participant CO  as RateLimitCoordinator
    participant OP  as RedisRateLimitOperation
    participant BK  as LeasedBucket

    KL->>CO: execute([redisOp])
    CO->>OP: execute()

    Note over OP: hitsAddend > 0 and hitsAddend ≤ max<br/>→ enter main path

    OP->>BK: tryReserve()\nCAS loop on localRemaining:\n  current ≥ hitsAddend\n  compareAndSet(current, current − hitsAddend)
    BK-->>OP: true (budget available)

    Note over OP: reserved = hitsAddend

    OP-->>CO: RateLimitStatus(OK,\n  limitRemaining = localRemaining + distributedRemaining)
    CO-->>KL: RateLimitResponse(OK)
```

---

## 4. Redis backend — local budget exhausted, lease renewal

When the local budget runs dry, concurrent callers coalesce behind a per-bucket `Mutex` so that exactly one renewal goes to Redis.

```mermaid
sequenceDiagram
    autonumber
    participant OP   as RedisRateLimitOperation
    participant BK   as LeasedBucket
    participant LUA  as LuaScript
    participant EXEC as RedisCommandExecutor
    participant RD   as Redis

    OP->>BK: tryReserve() — CAS on localRemaining
    BK-->>OP: false (localRemaining < hitsAddend)

    Note over OP: Enter bucket.mutex.withLock { ... }

    OP->>BK: tryReserve() — second attempt inside lock\n(another caller may have renewed while we waited)
    BK-->>OP: false (still exhausted)

    Note over OP: acquireLease(max)\n  leaseSize = max(hitsAddend, ceil(max × leasePercentage / 100))

    OP->>LUA: execute(LEASE_ACQUIRE, keys=[key],\n  args=[max, leaseSize, windowSeconds+10s])
    LUA->>EXEC: evalsha(sha, MULTI, keys, args)
    EXEC->>RD: EVALSHA sha key max leaseSize ttl

    Note over RD: Lua atomically:<br/>  leasedSoFar = GET key (or 0)<br/>  if leasedSoFar ≥ limit: return [0, 0]<br/>  granted = min(leaseSize, limit − leasedSoFar)<br/>  INCRBY key granted<br/>  if PTTL key < 0: EXPIRE key ttl<br/>  return [granted, limit − leasedSoFar − granted]

    RD-->>EXEC: [granted, remaining]
    EXEC-->>LUA: [granted, remaining]
    LUA-->>OP: [granted, remaining]

    alt granted > 0
        OP->>BK: localRemaining.addAndGet(granted)
        OP->>BK: distributedRemaining.set(remaining)
        OP->>BK: tryReserve()
        BK-->>OP: true
        OP-->>OP: reserved = hitsAddend\nstatus = OK
    else globally exhausted (granted == 0)
        OP-->>OP: reserved = 0\nstatus = OVER_LIMIT
    end

    OP-->>OP: release mutex
```

---

## 5. Redis backend — NOSCRIPT recovery

If Redis evicts the cached Lua script (restart, failover, SCRIPT FLUSH), the `LuaScript` wrapper recovers transparently.

```mermaid
sequenceDiagram
    autonumber
    participant OP   as RedisRateLimitOperation
    participant LUA  as LuaScript
    participant EXEC as RedisCommandExecutor
    participant RD   as Redis

    OP->>LUA: execute(LEASE_ACQUIRE, keys, args)
    Note over LUA: cachedSha != null — use existing SHA

    LUA->>EXEC: evalsha(sha, MULTI, keys, args)
    EXEC->>RD: EVALSHA sha ...
    RD-->>EXEC: RedisNoScriptException (script not cached)
    EXEC-->>LUA: throws RedisNoScriptException

    Note over LUA: cachedSha.set(null)\nreload script

    LUA->>EXEC: scriptLoad(source)
    EXEC->>RD: SCRIPT LOAD <lua source>
    RD-->>EXEC: newSha
    EXEC-->>LUA: newSha

    LUA->>EXEC: evalsha(newSha, MULTI, keys, args)
    EXEC->>RD: EVALSHA newSha ...
    RD-->>EXEC: [granted, remaining]
    EXEC-->>LUA: [granted, remaining]
    LUA-->>OP: [granted, remaining]
```

---

## 6. Domain not found — unconditional pass-through

If the request carries a `domain` that has no matching `RateLimitDomain` in the repository, the operation factory returns an empty list and the coordinator immediately responds `OK`.

```mermaid
sequenceDiagram
    autonumber
    participant KL  as DefaultKLimiter
    participant OF  as DefaultRateLimitOperationFactory
    participant CO  as RateLimitCoordinator

    KL->>OF: create(request)
    Note over OF: domainRepository.findById(domain) → null\nlog DEBUG "Domain not found, all descriptors will pass through"
    OF-->>KL: []

    KL->>CO: execute([])
    Note over CO: Empty list fast path
    CO-->>KL: RateLimitResponse(OK, statuses=[])
    KL-->>KL: (return OK)
```
