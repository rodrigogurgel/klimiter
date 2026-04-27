# Algorithms

**See also:** [Architecture](ARCHITECTURE.md) — component overview · [Flows](FLOWS.md) — sequence diagrams that trace these algorithms end-to-end

---

## 1. Rate limit decision — full flowchart

Covers both in-memory and Redis backends. The fork only happens after the bucket key is resolved.

```mermaid
flowchart TD
    A([RateLimitRequest]) --> B{Domain found\nin repository?}
    B -- No --> C([Return OK\nall descriptors pass])

    B -- Yes --> D[For each RateLimitRequestDescriptor\nin request.descriptors]

    D --> E[Build DescriptorPath list\nfrom descriptor.entries]
    E --> F[domain.findByPath *paths\nrecurse through descriptor tree]

    F --> G{Descriptor\nfound?}
    G -- No match --> H([Skip descriptor\nno operation produced])

    G -- Match --> I{Descriptor\nisWhitelisted?\nrule=null AND children=empty}
    I -- Yes --> H

    I -- No --> J{rule.unlimited\n= true?}
    J -- Yes --> H

    J -- No --> K[Resolve requestsPerUnit and unit\nfrom descriptor.limit override\nor rule]

    K --> L["CompositeKeyGenerator.generate\nklimiter|domain|k=v|…|windowStart\nwhere windowStart = now/window*window"]

    L --> M{Backend?}

    M -- In-memory --> N[InMemoryRateLimitStore\n.getOrCreate key ttlSeconds\nreturns existing AtomicLong\nor creates new one\nTTL = window + gracePeriod]

    M -- Redis --> O[LeasedBucketStore\n.getOrCreate key ttlSeconds\nreturns existing LeasedBucket\nor creates new one]

    N --> P[Create InMemoryRateLimitOperation\nkey, limit, hitsAddend, counter]
    O --> Q[Create RedisRateLimitOperation\nkey, limit, hitsAddend, bucket, executor]

    P --> R[RateLimitCoordinator\n.execute operations]
    Q --> R

    R --> S{operations\ncount?}
    S -- 0 --> T([RateLimitResponse OK\nstatuses = empty])
    S -- 1 --> U[op.execute — fast path]
    S -- N --> V[executeMultiple]

    U --> W{Status?}
    W -- OK --> X([RateLimitResponse OK\nwith status])
    W -- OVER_LIMIT --> Y([RateLimitResponse OVER_LIMIT\nwith status])

    V --> Z[Execute ALL operations\ncollect all RateLimitStatus results]
    Z --> AA[resolve overallCode\nOVER_LIMIT wins over OK]
    AA --> AB{overallCode\n= OK?}
    AB -- Yes --> AC([RateLimitResponse OK\nall statuses])
    AB -- No --> AD[rollbackSuccessful\nfor each result.code == OK\ncall op.rollback — swallow exceptions]
    AD --> AE([RateLimitResponse OVER_LIMIT\nall statuses])
```

---

## 2. Descriptor matching algorithm

Called by `MatchEngine.bestMatch(path)` on each level of the descriptor tree. Precedence is fixed: **exact → wildcard → key-only**.

```mermaid
flowchart TD
    A([bestMatch path on List of RateLimitDescriptor]) --> B{matchExact\npath.value != null\nfind descriptor where\nkey=path.key AND value=path.value}

    B -- found --> Z([Return descriptor])
    B -- not found --> C{matchWildcard\npath.value != null\nfind descriptors where\nkey=path.key AND value ends with '*'\npick longest prefix that\npath.value starts with}

    C -- found --> Z
    C -- not found --> D{matchKeyOnly\nfind descriptor where\nkey=path.key AND value=null}

    D -- found --> Z
    D -- not found --> E([Return null\nno match])
```

Multi-entry descriptors (e.g. `[user_id=u1, plan=free]`) are matched by walking the tree recursively: the first entry is matched against `domain.descriptors`, the second against the matched descriptor's `children`, and so on.

```mermaid
flowchart TD
    A([findByPath paths]) --> B{paths empty?}
    B -- Yes --> C([Return null])
    B -- No --> D[head = paths.first\ntail = paths.drop 1]
    D --> E[descriptors.bestMatch head]
    E --> F{Match found?}
    F -- No --> G([Return null])
    F -- Yes --> H{tail empty?}
    H -- Yes --> I([Return matched descriptor])
    H -- No --> J[matched.findByPath *tail\nrecurse into children]
    J --> K([Return result])
```

---

## 3. Time-bucketed key generation

`CompositeKeyGenerator` produces keys that encode the current rate-limit window so that each window maps to a distinct counter bucket.

```mermaid
flowchart TD
    A([generate domain entries windowDivider]) --> B[Validate: domain not blank\nentries not empty\nno pipe char in domain/key/value]
    B --> C[windowStart =\nnow.epochSecond / windowDivider * windowDivider]
    C --> D["Build key:\nklimiter | domain | k1=v1 | k2=v2 | … | windowStart"]
    D --> E{Redis backend?}
    E -- No --> F([Return key])
    E -- Yes --> G["Prefix: keyPrefix:key\ne.g. klimiter:klimiter|default|user_id=u1|1700000000"]
    G --> F
```

The `|` separator is rejected in domain names, keys, and values because it would make the composite key ambiguous. This is enforced at key-generation time with an `IllegalArgumentException`.

---

## 4. In-memory reservation — CAS loop

`InMemoryRateLimitOperation.execute()` is entirely lock-free. The loop retries only on lost CAS races, not on limit decisions.

```mermaid
flowchart TD
    A([execute]) --> B[max = limit.requestsPerUnit]
    B --> C[current = counter.get]
    C --> D[projected = current + hitsAddend]
    D --> E{projected > max?}
    E -- Yes --> F([Return OVER_LIMIT\nreserved unchanged = 0])
    E -- No --> G{counter.compareAndSet\ncurrent → projected}
    G -- CAS lost\nanother thread raced --> C
    G -- CAS won --> H[reserved = hitsAddend]
    H --> I([Return OK\nreserved = hitsAddend])
```

```mermaid
flowchart TD
    A([rollback]) --> B{reserved == 0?}
    B -- Yes --> C([No-op — idempotent])
    B -- No --> D["counter.addAndGet(−reserved)"]
    D --> E[reserved = 0]
    E --> F([Done])
```

---

## 5. Redis lease algorithm

`RedisRateLimitOperation.execute()` has three decision levels before contacting Redis. The Lua script is only reached when the local budget is fully exhausted and no concurrent renewal already refreshed it.

```mermaid
flowchart TD
    A([execute]) --> B{hitsAddend ≤ 0?}
    B -- credit or no-op --> C["localRemaining.addAndGet(−hitsAddend)\n(returns credit to local pool)"]
    C --> D([Return OK])

    B -- positive --> E{hitsAddend > max?}
    E -- Yes, single request\nexceeds window limit --> F([Return OVER_LIMIT])

    E -- No --> G[tryReserve\nCAS loop on localRemaining\ncurrent ≥ hitsAddend\ncompareAndSet current → current−hitsAddend]

    G -- CAS won --> H[reserved = hitsAddend]
    H --> I([Return OK\nno Redis contact])

    G -- budget < hitsAddend --> J[bucket.mutex.withLock ...]

    J --> K[tryReserve — second check\nanother coroutine may have\nrenewed while we waited]
    K -- CAS won --> H

    K -- still exhausted --> L["acquireLease(max)\nleaseSize = max(hitsAddend,\nceil(max × leasePercentage / 100))"]

    L --> M["LuaScript.execute EVALSHA\nkeys=[key] args=[max, leaseSize, ttl]"]
    M --> N{granted > 0?}

    N -- Yes --> O["localRemaining.addAndGet(granted)\ndistributedRemaining.set(remaining)"]
    O --> P[tryReserve — final attempt]
    P -- CAS won --> H
    P -- failed\nedge case: another renewal\nclaimed the fresh budget --> Q([Return OVER_LIMIT\nglobally exhausted])

    N -- No, limit reached --> Q
```

**Lua script logic** (`LeaseScripts.LEASE_ACQUIRE`):

```mermaid
flowchart TD
    L1([KEYS 1=key ARGV 1=limit 2=requested 3=ttl]) --> L2["leasedSoFar = GET key (or 0)"]
    L2 --> L3{leasedSoFar ≥ limit?}
    L3 -- Yes --> L4([Return 0, 0])
    L3 -- No --> L5["available = limit − leasedSoFar\ngranted = min(available, requested)\nINCRBY key granted"]
    L5 --> L6{PTTL key < 0\nno TTL set yet?}
    L6 -- Yes --> L7[EXPIRE key ttl]
    L7 --> L8(["Return [granted, limit − leasedSoFar − granted]"])
    L6 -- No --> L8
```

---

## 6. Reservation lifecycle — state machine

Each `RateLimitOperation` instance is created per-request and discarded after the coordinator returns. The `reserved` field tracks whether this instance holds an active reservation.

```mermaid
stateDiagram-v2
    direction LR

    [*] --> Idle : OperationFactory.create()\nreserved = 0

    Idle --> Reserved : execute() → OK\nCAS / lease succeeds\nreserved = hitsAddend

    Idle --> Denied : execute() → OVER_LIMIT\nprojected > max or budget exhausted\nreserved stays 0

    Reserved --> RolledBack : rollback() called by coordinator\ncounter.addAndGet(−reserved) or localRemaining.addAndGet(reserved)\nreserved = 0

    Reserved --> Released : coordinator returns OK response\noperation reference dropped\n(no explicit transition needed)

    Denied --> Released : coordinator returns OVER_LIMIT response\noperation reference dropped

    RolledBack --> Released : coordinator returns OVER_LIMIT response\noperation reference dropped

    Released --> [*]
```

**Key invariants:**

- `rollback()` is idempotent: if `reserved == 0` it is a no-op.
- The coordinator only calls `rollback()` on operations whose `execute()` returned `OK` (i.e. those that transitioned to `Reserved`).
- For the Redis backend, `rollback()` never contacts Redis — it only returns hits to the local `LeasedBucket`. At most one lease-slice can be leaked on a failed multi-descriptor batch.
- An operation in `Denied` state has `reserved == 0`; calling `rollback()` on it is safe and is a no-op.

---

## 7. Overall code resolution

The coordinator delegates to `RateLimitOverallCodeResolver.resolve(statuses)`.

```mermaid
flowchart TD
    A([resolve statuses]) --> B{Any status\n= OVER_LIMIT?}
    B -- Yes --> C([OVER_LIMIT])
    B -- No --> D{All statuses\n= OK?}
    D -- Yes --> E([OK])
    D -- No --> F([UNKNOWN\nmixed or empty])
```
