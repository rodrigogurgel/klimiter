# syntax=docker/dockerfile:1.7

# Build context: project root
# Build:
#   docker build -t klimiter-service .
#
# Run:
#   docker run --rm -p 9090:9090 -p 8080:8080 klimiter-service

# ─── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY gradlew .
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY settings.gradle.kts build.gradle.kts gradle.properties ./

COPY klimiter-core/build.gradle.kts klimiter-core/
COPY klimiter-redis/build.gradle.kts klimiter-redis/
COPY klimiter-service/build.gradle.kts klimiter-service/
COPY klimiter-architecture-tests/build.gradle.kts klimiter-architecture-tests/

COPY config/ config/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :klimiter-service:dependencies \
    --configuration runtimeClasspath \
    -x detekt \
    --no-daemon

COPY klimiter-core/src klimiter-core/src
COPY klimiter-redis/src klimiter-redis/src
COPY klimiter-service/src klimiter-service/src
COPY klimiter-architecture-tests/src klimiter-architecture-tests/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :klimiter-service:bootJar \
    -x test \
    -x detekt \
    --no-daemon

RUN mkdir -p /workspace/extracted \
 && JAR_FILE="$(find klimiter-service/build/libs -name '*.jar' ! -name '*plain.jar' | head -n 1)" \
 && java -Djarmode=tools \
      -jar "$JAR_FILE" \
      extract \
      --layers \
      --launcher \
      --destination /workspace/extracted


# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends wget ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --no-create-home app

WORKDIR /app

COPY --from=builder --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/application/ ./

USER app

EXPOSE 9090
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -Dspring.jmx.enabled=false -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]