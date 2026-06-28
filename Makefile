SHELL := /bin/bash
.DEFAULT_GOAL := help

# Atalhos para a stack local e os testes de carga (ver scripts/load-test/README.md).
# Build, testes e codegen do serviço são via Gradle (`./gradlew ...`), não aqui.

# --- Teste de comportamento (k6 -> OpenTelemetry -> Grafana/LGTM) ----------
# Sobrescreva qualquer variável: make load-test DURATION=5m BAIXA_PRIORIDADE_RPS=1000
OTEL_ENDPOINT        ?= localhost:4317
K6_EXPORT_INTERVAL   ?= 1s
DURATION             ?= 15m
ONLINE_MIN_RPS       ?= 0
ONLINE_MAX_RPS       ?= 80
ONLINE_WAVE_PERIOD   ?= 5m
BAIXA_PRIORIDADE_RPS ?= 1000
DISTINCT_KEYS        ?= 1

# --- Teste de saturação (ghz) ---------------------------------------------
SAT_TARGET        ?= localhost:9090
PRIORITY          ?= PRIORITY_HIGH
SAT_DURATION      ?= 20s
KNEE_MS           ?= 15
SAT_STEPS         ?= 1000 2000 4000 6000 8000 9000 10000 11000 12000
# Espaço de chaves por dimensão (1 = chave fixa/quente; alto = mede o caminho ao Redis).
SAT_ACCOUNT_KEYS  ?= 50000
SAT_SOURCE_KEYS   ?= 100
SAT_FLOW_KEYS     ?= 1

BEHAVIOR_SCRIPT   := scripts/load-test/behavior/evaluate-online-variavel.js
SATURATION_SCRIPT := scripts/load-test/performance/saturation-ghz.sh

# `sat-server`: serviço PINADO em 2 cores, OTel off (harness confiável — ver docs/SATURACAO.md;
# NÃO use o `cpus`/cpuset do Docker, que distorce o joelho). Precisa do Redis no ar.
SAT_CPUS     ?= 0,1
# Pinning real só no Linux (taskset). No macOS não há afinidade de CPU em userland: cai para a JVM
# limitada por -XX:ActiveProcessorCount=2 (sem isolamento de core; o joelho fica menos confiável).
SAT_PIN      := $(shell command -v taskset >/dev/null 2>&1 && echo "taskset -c $(SAT_CPUS)")
SAT_JVM      ?= -XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=2 -Xms512m -Xmx768m
SAT_REDIS    ?= redis://localhost:6379
SAT_POLICIES ?= scripts/load-test/policies.sample.yaml
SAT_OTEL_OFF ?= -Dspring.autoconfigure.exclude=org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration -Dmanagement.tracing.enabled=false -Dmanagement.otlp.metrics.export.enabled=false -Dotel.sdk.disabled=true

.PHONY: help up up-cluster down logs logs-cluster sat-server saturation load-test sonar-local sonar-reset

# --- SonarQube local (profile 'sonar' do docker-compose) -------------------
SONAR_URL      ?= http://localhost:9000
SONAR_PASSWORD ?= Klimiter-Local-2026    # política do Sonar: >=12, maiúscula+minúscula+dígito+especial

## help: lista os alvos disponíveis
help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'

## up: sobe a stack local STANDALONE (klimiter + Redis + RedisInsight + Grafana/LGTM) e (re)builda
up:
	COMPOSE_PROFILES=standalone docker compose up -d --build

## up-cluster: sobe a stack local CLUSTER (klimiter-cluster + 3 nós Redis); NÃO sobe o standalone
up-cluster:
	COMPOSE_PROFILES=redis-cluster docker compose up -d --build

## down: derruba a stack, qualquer modo (use `make down ARGS=-v` para apagar os volumes)
down:
	COMPOSE_PROFILES=standalone,redis-cluster docker compose down $(ARGS)

## logs: acompanha os logs do serviço klimiter (standalone)
logs:
	docker compose logs -f klimiter

## logs-cluster: acompanha os logs do serviço klimiter-cluster
logs-cluster:
	docker compose logs -f klimiter-cluster

## sat-server: serviço pinado em 2 cores, OTel off, políticas de carga (foreground; precisa do Redis no ar)
sat-server:
	./gradlew -q bootJar -x test
ifeq ($(SAT_PIN),)
	@echo ">> AVISO: 'taskset' indisponível (macOS?) — SEM pinning real de core; JVM limitada a ActiveProcessorCount=2. O joelho é menos confiável (ver docs/SATURACAO.md §4)."
else
	@echo ">> klimiter pinado em CPUs $(SAT_CPUS), OTel off. Ctrl-C p/ parar; rode 'make saturation' noutro terminal."
endif
	KLIMITER_REDIS_URI=$(SAT_REDIS) KLIMITER_POLICIES_PATH=$(SAT_POLICIES) \
		$(SAT_PIN) java $(SAT_JVM) $(SAT_OTEL_OFF) -jar build/libs/klimiter-*.jar

## saturation: PERFORMANCE com ghz (rode `make sat-server` noutro terminal antes) — degraus até o joelho
saturation:
	@TARGET=$(SAT_TARGET) PRIORITY=$(PRIORITY) DURATION=$(SAT_DURATION) \
		KNEE_MS=$(KNEE_MS) STEPS="$(SAT_STEPS)" \
		ACCOUNT_DISTINCT_KEYS=$(SAT_ACCOUNT_KEYS) \
		SOURCE_DISTINCT_KEYS=$(SAT_SOURCE_KEYS) \
		FLOW_DISTINCT_KEYS=$(SAT_FLOW_KEYS) \
		bash $(SATURATION_SCRIPT)

## load-test: COMPORTAMENTO com k6 em background, exportando p/ o Grafana sob um test_run_id único
load-test:
	@RUNID=run-$$(date +%Y%m%d-%H%M%S); \
	LOG=/tmp/klimiter-loadtest-$$RUNID.log; \
	K6_OTEL_GRPC_EXPORTER_ENDPOINT=$(OTEL_ENDPOINT) \
	K6_OTEL_GRPC_EXPORTER_INSECURE=true \
	K6_OTEL_EXPORT_INTERVAL=$(K6_EXPORT_INTERVAL) \
	nohup k6 run --tag test_run_id=$$RUNID --out opentelemetry \
		-e DURATION=$(DURATION) \
		-e ONLINE_MIN_RPS=$(ONLINE_MIN_RPS) -e ONLINE_MAX_RPS=$(ONLINE_MAX_RPS) \
		-e ONLINE_WAVE_PERIOD=$(ONLINE_WAVE_PERIOD) \
		-e BAIXA_PRIORIDADE_RPS=$(BAIXA_PRIORIDADE_RPS) \
		-e DISTINCT_KEYS=$(DISTINCT_KEYS) \
		$(BEHAVIOR_SCRIPT) > $$LOG 2>&1 & \
	echo "k6 em background (PID $$!)"; \
	echo "  test_run_id : $$RUNID"; \
	echo "  log         : $$LOG   (tail -f para acompanhar)"; \
	echo "  Grafana     : http://localhost:3000/explore  (sem dashboard provisionado; consulte k6_klimiter_* / grpc_req_duration no Explore, filtrando test_run_id=$$RUNID)"

## sonar-local: sobe o SonarQube local, semeia a senha do admin e roda a análise (envia ao Sonar local)
sonar-local:
	@SONAR_URL=$(SONAR_URL) SONAR_PASSWORD=$(SONAR_PASSWORD) bash scripts/sonar-local.sh

## sonar-reset: derruba o SonarQube local e APAGA seus volumes (zera senha/dados; use após upgrade de major)
sonar-reset:
	docker compose rm -sf sonarqube
	docker volume rm -f klimiter_sonarqube-data klimiter_sonarqube-extensions klimiter_sonarqube-logs
