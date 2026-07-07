# syntax=docker/dockerfile:1

# ---- build: compila o bootJar com a toolchain JDK 21 ----
# Build na arquitetura nativa ($BUILDPLATFORM): o bootJar é bytecode portável, então
# não precisa emular a arquitetura alvo — só a imagem JRE final é por-arch.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-ubi10-minimal AS build
WORKDIR /workspace

# Arquivos de build primeiro (camada estável → melhor cache); depois o código-fonte.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src

# Cache do Gradle entre builds (BuildKit). Os testes rodam no CI, não na imagem.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar -x test

# Extrai as camadas do bootJar (jarmode tools, Spring Boot 4) para cache eficiente de layers.
RUN cp build/libs/*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- probe: binário estático do health check gRPC (grpc.health.v1.Health) ----
# Tag pinada (não `latest`): build reprodutível; o Dependabot propõe os bumps.
FROM curlimages/curl:8.21.0 AS probe
ARG TARGETARCH
ARG GRPC_HEALTH_PROBE_VERSION=v0.4.52
RUN curl -fsSL -o /tmp/grpc_health_probe \
    "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-${TARGETARCH}" \
    && chmod +x /tmp/grpc_health_probe

# ---- runtime: JRE 21 enxuta (UBI minimal: menor superfície/CVEs), usuário não-root ----
FROM eclipse-temurin:21-jre-ubi10-minimal AS runtime
WORKDIR /application

# `-U` cria o grupo homônimo junto (UBI minimal traz shadow-utils).
RUN useradd --system --user-group --no-create-home klimiter

COPY --from=probe /tmp/grpc_health_probe /usr/local/bin/grpc_health_probe

# Camadas da menos → mais volátil (maximiza o reaproveitamento de cache entre deploys).
COPY --from=build --chown=klimiter:klimiter /workspace/extracted/dependencies/ ./
COPY --from=build --chown=klimiter:klimiter /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=klimiter:klimiter /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=klimiter:klimiter /workspace/extracted/application/ ./

USER klimiter

# Porta gRPC (spring.grpc.server.port, default 9090). O tuning de JVM/GC entra via JAVA_TOOL_OPTIONS
# (no compose), fora da imagem. As políticas (config/policies/policies.yaml) são montadas em runtime;
# ausentes no boot, o serviço sobe em pass-through (DESIGN-CONCEITUAL.md §8).
EXPOSE 9090

# Health check via gRPC (grpc.health.v1.Health, exposto pelo Spring gRPC). `start-period` cobre o
# boot (incl. conexão ao Redis); fica SERVING quando os health indicators do Actuator estão UP.
# Em Kubernetes, prefira a probe gRPC nativa (campo `grpc:`) — o binário aqui serve docker/compose.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD ["grpc_health_probe", "-addr=127.0.0.1:9090"]

ENTRYPOINT ["java", "-jar", "application.jar"]
