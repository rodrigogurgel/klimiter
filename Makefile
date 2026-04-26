SHELL := /bin/bash

COMPOSE ?= docker compose
PROFILES := --profile redis_standalone --profile redis_cluster --profile app_standalone --profile app_cluster
ENV_FILE ?= .env
ENV_EXAMPLE ?= .env.example
GRPC_HOST ?= localhost
GRPC_PORT ?= 9090
HTTP_HOST ?= localhost
HTTP_PORT ?= 8080
SERVICE ?=


GRPC_REQUEST ?= '{"descriptors":[{"entries":[{"key":"user_id","value":"user_1"}]}],"domain":"default"}'
GRPC_METHOD ?= io.klimiter.RateLimitService.ShouldRateLimit

.PHONY: help env env-check ps config \
        redis-standalone redis-cluster \
        app-standalone app-cluster \
        build build-no-cache rebuild-standalone rebuild-cluster \
        down down-volumes restart-standalone restart-cluster \
        logs logs-app logs-standalone logs-cluster logs-nginx logs-redis-cluster-init \
        health grpcurl \
        redis-standalone-ping redis-cluster-info redis-cluster-nodes \
        clean

help: ## Show available commands
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  %-28s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

env: ## Create .env from .env.example if .env does not exist
	@if [ ! -f "$(ENV_FILE)" ]; then \
		cp "$(ENV_EXAMPLE)" "$(ENV_FILE)"; \
		echo "Created $(ENV_FILE) from $(ENV_EXAMPLE)"; \
	else \
		echo "$(ENV_FILE) already exists"; \
	fi

env-check: ## Check if .env exists
	@test -f "$(ENV_FILE)" || (echo "Missing $(ENV_FILE). Run: make env" && exit 1)

ps: ## List compose services
	$(COMPOSE) ps

config: env-check ## Render Docker Compose config
	$(COMPOSE) config

redis-standalone: env-check ## Start Redis standalone and Redis Insight
	$(COMPOSE) --profile redis_standalone up -d

redis-cluster: env-check ## Start Redis Cluster and Redis Insight
	$(COMPOSE) --profile redis_cluster up -d

app-standalone: env-check ## Build and start app with Redis standalone
	$(COMPOSE) --profile app_standalone up -d --build

app-cluster: env-check ## Build and start app cluster with Redis Cluster and Nginx
	$(COMPOSE) --profile app_cluster up -d --build

build: env-check ## Build compose images
	$(COMPOSE) build

build-no-cache: env-check ## Build compose images without cache
	$(COMPOSE) build --no-cache

rebuild-standalone: env-check ## Rebuild and restart standalone app profile
	$(COMPOSE) --profile app_standalone up -d --build --force-recreate

rebuild-cluster: env-check ## Rebuild and restart app cluster profile
	$(COMPOSE) --profile app_cluster up -d --build --force-recreate

restart-standalone: env-check ## Restart standalone app profile
	$(COMPOSE) --profile app_standalone restart

restart-cluster: env-check ## Restart app cluster profile
	$(COMPOSE) --profile app_cluster restart

down: ## Stop and remove containers from all profiles
	$(COMPOSE) $(PROFILES) down --remove-orphans

down-volumes: ## Stop and remove containers, networks and volumes from all profiles
	$(COMPOSE) $(PROFILES) down -v --remove-orphans

logs: ## Follow logs. Optionally pass SERVICE=name
	@if [ -n "$(SERVICE)" ]; then \
		$(COMPOSE) logs -f "$(SERVICE)"; \
	else \
		$(COMPOSE) logs -f; \
	fi

logs-app: ## Follow logs from all app instances
	$(COMPOSE) logs -f app-standalone app-1 app-2 app-3

logs-standalone: ## Follow logs from standalone app
	$(COMPOSE) logs -f app-standalone

logs-cluster: ## Follow logs from app cluster instances
	$(COMPOSE) logs -f app-1 app-2 app-3

logs-nginx: ## Follow Nginx logs
	$(COMPOSE) logs -f nginx

logs-redis-cluster-init: ## Follow Redis Cluster init logs
	$(COMPOSE) logs -f redis-cluster-init

health: ## Check application health endpoint
	curl -fsS "http://$(HTTP_HOST):$(HTTP_PORT)/actuator/health" | cat

grpcurl: ## Call the gRPC rate limit endpoint
	grpcurl \
		-plaintext \
		-emit-defaults \
		-d $(GRPC_REQUEST) \
		"$(GRPC_HOST):$(GRPC_PORT)" \
		$(GRPC_METHOD)

redis-standalone-ping: ## Ping Redis standalone
	$(COMPOSE) exec redis-standalone redis-cli ping

redis-cluster-info: ## Show Redis Cluster info
	$(COMPOSE) exec redis-cluster-1 redis-cli -p 7001 cluster info

redis-cluster-nodes: ## Show Redis Cluster nodes
	$(COMPOSE) exec redis-cluster-1 redis-cli -p 7001 cluster nodes

clean: down-volumes ## Alias for down-volumes
