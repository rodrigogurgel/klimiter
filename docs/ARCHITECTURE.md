# Architecture

KLimiter is a multi-module Gradle project. The three core modules have a strict layering: **klimiter-core** is the rate-limiter library, **klimiter-redis** is an optional Redis backend implementing the core SPI, and **klimiter-service** is the deployable Spring Boot gRPC service.

---

## C4 — System Context

```mermaid
C4Context
    title System Context — KLimiter

    Person_Ext(client, "API Client", "Envoy proxy or any application that needs distributed rate limiting")
    System(klimiter, "KLimiter Service", "Coroutine-native rate limiter implementing the Envoy RLS protocol")
    System_Ext(redis, "Redis", "Optional distributed counter store (standalone or cluster)")

    Rel(client, klimiter, "ShouldRateLimit", "gRPC :9090")
    Rel(klimiter, redis, "Lease acquire / counter increment", "Lettuce reactive")
```

---

## C4 — Container Diagram

```mermaid
C4Container
    title Container Diagram — KLimiter

    Person_Ext(client, "API Client", "e.g. Envoy proxy")

    System_Boundary(b, "KLimiter") {
        Container(svc, "klimiter-service", "Spring Boot 4 / Kotlin 2.3", "Deployable gRPC service. Wires the library to HTTP/gRPC transport and Spring lifecycle.")
        Container(core, "klimiter-core", "Kotlin library", "Public API, SPI contracts, in-memory Caffeine backend, all-or-nothing coordinator.")
        Container(rmod, "klimiter-redis", "Kotlin library", "Redis SPI implementation using a local-lease pattern to minimise round-trips.")
        Container(archtests, "klimiter-architecture-tests", "Konsist test module", "Verifies module-boundary rules at compile time.")
    }

    System_Ext(redis, "Redis", "Standalone or Cluster")

    Rel(client, svc, "ShouldRateLimit", "gRPC :9090")
    Rel(svc, core, "KLimiterFactory / KLimiter API")
    Rel(svc, rmod, "RedisKLimiterFactory (optional, controlled by BackendMode)")
    Rel(rmod, core, "implements RateLimitOperationFactory SPI")
    Rel(rmod, redis, "EVALSHA / SCRIPT LOAD", "Lettuce reactive + coroutine bridge")
```

---

## C4 — Component Diagram: klimiter-service

```mermaid
C4Component
    title Component Diagram — klimiter-service

    Container_Boundary(cb, "klimiter-service") {
        Component(grpc, "RateLimitGrpcAdapter", "@GrpcService", "Receives ShouldRateLimit RPC. Maps proto ↔ core domain types via RateLimitGrpcMappers. Returns UNKNOWN on unhandled exceptions.")
        Component(app, "CheckRateLimitService", "@Service / CheckRateLimitUseCase", "Single application use case. Delegates unconditionally to the output port.")
        Component(ca, "KLimiterCoreAdapter", "@Component / RateLimitEnforcerPort", "Calls kLimiter.shouldRateLimit() wrapped in runCatching. Returns RateLimitCode.UNKNOWN on failure.")
        Component(cfg, "KLimiterConfiguration", "@Configuration", "Reads KLimiterProperties and wires the KLimiter bean: IN_MEMORY, REDIS_STANDALONE, or REDIS_CLUSTER. Builds StaticRateLimitDomainRepository.")
        Component(props, "KLimiterProperties", "@ConfigurationProperties(klimiter.*)", "Typed binding for backend mode, Redis URI/URIs, lease percentage, key prefix, and domain descriptor trees.")
    }

    Container_Ext(core, "klimiter-core", "Public KLimiter API + SPI")
    Container_Ext(rmod, "klimiter-redis", "Redis factory + SPI implementation")

    Rel(grpc, app, "check(CoreRateLimitRequest)")
    Rel(app, ca, "enforce(CoreRateLimitRequest)")
    Rel(ca, core, "kLimiter.shouldRateLimit(request)")
    Rel(cfg, core, "KLimiterFactory.inMemory(domainRepository)")
    Rel(cfg, rmod, "RedisKLimiterFactory.standalone/cluster(uri, domainRepository, config)")
    Rel(cfg, props, "reads")
```

---

## C4 — Component Diagram: klimiter-core

```mermaid
C4Component
    title Component Diagram — klimiter-core

    Container_Boundary(cb, "klimiter-core") {
        Component(pub, "KLimiter / KLimiterFactory", "api (public surface)", "KLimiter is the main suspend entry point. KLimiterFactory provides static convenience constructors.")
        Component(builder, "KLimiterBuilder", "public builder", "Fluent builder. Accepts optional operationFactory; if absent, builds the in-memory backend. Also accepts domainRepository, keyGenerator, timeProvider, maxCacheSize, gracePeriod.")
        Component(defkl, "DefaultKLimiter", "internal", "Delegates to RateLimitOperationFactory.create() then RateLimitCoordinator.execute(). No state of its own.")
        Component(coord, "RateLimitCoordinator", "internal object", "Runs all operations sequentially. On any non-OK result, rolls back every previously-OK reservation. Single-op fast path avoids list allocation.")
        Component(resolver, "RateLimitOverallCodeResolver", "internal object", "OVER_LIMIT wins over OK; all-OK → OK; mixed-unknown → UNKNOWN.")
        Component(factory, "DefaultRateLimitOperationFactory", "internal", "Per-request: looks up domain, walks descriptor tree via MatchEngine, generates time-bucketed key via CompositeKeyGenerator, retrieves AtomicLong counter from InMemoryRateLimitStore.")
        Component(store, "InMemoryRateLimitStore", "internal", "Caffeine cache keyed by bucket key. TTL = windowSeconds + gracePeriod (default 30 s). Side-cache detects concurrent-window leak and logs WARN.")
        Component(op, "InMemoryRateLimitOperation", "internal", "Lock-free CAS loop on AtomicLong. Tracks reserved hits for idempotent rollback.")
        Component(spi, "RateLimitOperationFactory / RateLimitOperation", "api.spi (public)", "Extension points. Implement these to plug in a custom backend.")
        Component(keygen, "CompositeKeyGenerator", "spi (public)", "Produces klimiter|domain|k=v|…|windowStart. Rejects '|' in domain/key/value.")
        Component(timeprov, "TimeProvider / SystemTimeProvider", "spi (public)", "Clock abstraction. Inject FixedTimeProvider in tests for deterministic window behaviour.")
    }

    Rel(pub, builder, "delegates construction to")
    Rel(builder, defkl, "builds DefaultKLimiter(operationFactory)")
    Rel(defkl, factory, "create(request)")
    Rel(defkl, coord, "execute(operations)")
    Rel(coord, resolver, "resolve(statuses)")
    Rel(factory, store, "getOrCreate(key, ttlSeconds)")
    Rel(factory, keygen, "generate(domain, entries, windowDivider)")
    Rel(factory, op, "creates per descriptor")
    Rel(factory, spi, "implements RateLimitOperationFactory")
    Rel(op, spi, "implements RateLimitOperation")
```

---

## C4 — Component Diagram: klimiter-redis

```mermaid
C4Component
    title Component Diagram — klimiter-redis

    Container_Boundary(cb, "klimiter-redis") {
        Component(factory, "RedisKLimiterFactory", "public object", "Entry point. standalone() and cluster() convenience constructors. Returns a CloseableKLimiter that owns the Lettuce connection.")
        Component(opfact, "RedisRateLimitOperationFactory", "internal / RateLimitOperationFactory", "Same domain-lookup + descriptor-matching logic as the in-memory factory. Prefixes the generated key with keyPrefix. Looks up LeasedBucket from LeasedBucketStore.")
        Component(op, "RedisRateLimitOperation", "internal / RateLimitOperation", "Hot path: CAS on LeasedBucket.localRemaining. On exhaustion: acquires bucket.mutex, retries, then calls acquireLease() → LuaScript. Rollback returns hits to local pool only.")
        Component(bucket, "LeasedBucket", "internal data class", "Per-key local state: localRemaining (AtomicLong), distributedRemaining (AtomicLong), mutex (Mutex).")
        Component(bstore, "LeasedBucketStore", "internal", "Caffeine cache of LeasedBuckets, TTL = windowSeconds + gracePeriod. Evicts stale windows automatically.")
        Component(lua, "LuaScript", "internal", "Wraps Lua source with lazy SCRIPT LOAD and EVALSHA. Transparent NOSCRIPT reload on Redis restart or failover.")
        Component(scripts, "LeaseScripts.LEASE_ACQUIRE", "internal object", "Atomic Lua: reads leased-so-far, grants min(requested, remaining), INCRBY, EXPIRE if no TTL. Returns [granted, remaining].")
        Component(exec, "RedisCommandExecutor", "internal interface", "Abstracts Lettuce standalone vs. cluster surfaces. StandaloneRedisCommandExecutor and ClusterRedisCommandExecutor implement it.")
        Component(cfg, "RedisKLimiterConfig", "api (public)", "leasePercentage (default 10), keyPrefix (default 'klimiter'), gracePeriod, maxTrackedBuckets.")
    }

    Container_Ext(core, "klimiter-core SPI")
    System_Ext(redis, "Redis")

    Rel(factory, opfact, "creates RedisRateLimitOperationFactory")
    Rel(factory, exec, "creates StandaloneRedisCommandExecutor or ClusterRedisCommandExecutor")
    Rel(factory, cfg, "reads")
    Rel(opfact, op, "creates per descriptor")
    Rel(opfact, bstore, "getOrCreate(key, ttl)")
    Rel(op, bucket, "CAS on localRemaining / mutex.withLock")
    Rel(op, lua, "execute(LEASE_ACQUIRE, keys, args)")
    Rel(lua, exec, "evalsha / scriptLoad")
    Rel(exec, redis, "EVALSHA / SCRIPT LOAD / GET", "Lettuce reactive")
    Rel(opfact, core, "implements RateLimitOperationFactory")
    Rel(op, core, "implements RateLimitOperation")
```

---

## Module dependency direction

```
klimiter-service ──▶ klimiter-redis ──▶ klimiter-core
       │                                     ▲
       └─────────────────────────────────────┘
```

Rules enforced by `klimiter-architecture-tests` (Konsist):

- `klimiter-core` must not depend on Redis, Spring, or gRPC.
- `klimiter-redis` must not depend on `klimiter-service`.
- Service domain (`domain.model`, `domain.port`) must not import adapters, Spring, gRPC, core, or Redis directly.
- Service application layer must not import transport or backend adapters.
- Hexagonal package layout (`adapter`, `application`, `domain`) must exist in the service module.
