# syntax=docker/dockerfile:1
# Build context: project root
# Build: docker build -t klimiter-service .
# Run:   docker run --rm -p 9090:9090 -p 8080:8080 klimiter-service

# ─── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Gradle wrapper — layer invalidated only when wrapper version changes
COPY gradlew .
COPY gradle/ gradle/
RUN chmod +x gradlew

# Build scripts — layer invalidated when any *.gradle.kts or *.properties changes
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY klimiter-core/build.gradle.kts    klimiter-core/
COPY klimiter-redis/build.gradle.kts   klimiter-redis/
COPY klimiter-service/build.gradle.kts klimiter-service/

# Warm the Gradle dependency cache before touching source.
# This layer is reused on code-only changes (most rebuilds).
RUN ./gradlew :klimiter-service:dependencies --configuration runtimeClasspath \
        -x detekt -q --no-daemon 2>/dev/null || true

# Source — invalidated on every commit, but only rebuilds this layer + below
COPY klimiter-core/src    klimiter-core/src
COPY klimiter-redis/src   klimiter-redis/src
COPY klimiter-service/src klimiter-service/src

RUN ./gradlew :klimiter-service:bootJar -x test -x detekt -q --no-daemon

# Extract Spring Boot layered jar so Docker can cache stable deps separately
RUN java -Djarmode=tools \
        -jar klimiter-service/build/libs/klimiter-service-0.0.1-SNAPSHOT.jar \
        extract --layers --launcher \
        --destination /workspace/extracted

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

# wget is needed only for the HEALTHCHECK; install before dropping to non-root
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 app \
 && useradd  --system --uid 1001 --gid app --no-create-home app

WORKDIR /app

# Layers ordered most-stable → least-stable.
# On a code-only change only the last COPY is cache-invalidated.
COPY --from=builder --chown=app:app /workspace/extracted/dependencies/          ./
COPY --from=builder --chown=app:app /workspace/extracted/spring-boot-loader/    ./
COPY --from=builder --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/application/           ./

USER app

# 9090 → gRPC  |  8080 → Spring Boot Actuator (health / metrics / info)
EXPOSE 9090
EXPOSE 8080

# JVM tuning for low-latency gRPC under sustained load:
#
#   UseZGC + ZGenerational — sub-millisecond GC pauses; avoids latency stalls on
#       the gRPC hot path. Generational mode (stable since JDK 21) dramatically
#       reduces pause frequency compared to non-generational ZGC.
#
#   AlwaysPreTouch — commits all heap pages at JVM startup instead of on first
#       access. Eliminates page-fault latency spikes during the load ramp-up phase
#       of stress tests, where cold pages would otherwise cause irregular first-hit
#       latencies and invalidate p99 measurements.
#
#   DisableExplicitGC — blocks System.gc() calls. Netty issues explicit GCs to
#       reclaim direct memory; under ZGC these are no-ops anyway, but disabling
#       them prevents any accidental pause from third-party code.
#
#   MaxRAMPercentage=75 — caps heap at 75 % of container RAM, leaving headroom
#       for Netty off-heap buffers (used by gRPC), ZGC's own metadata, and OS
#       page cache. Pair this with --memory on docker run / resources.limits in k8s.
#
#   urandom — avoids /dev/random entropy exhaustion blocking SecureRandom during
#       TLS handshake init; common cause of cold-start latency spikes.
#
# Override any flag at runtime via: docker run -e JAVA_TOOL_OPTIONS="..." ...
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false -Dfile.encoding=UTF-8"

# Checks the actuator liveness probe; adjust start-period to match actual startup time.
HEALTHCHECK --interval=10s --timeout=5s --start-period=25s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# exec-form ENTRYPOINT: JVM is PID 1 and receives SIGTERM directly,
# which triggers Spring Boot's graceful shutdown.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
