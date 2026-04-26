# Docker - KLimiter

Este documento descreve como executar o projeto localmente com Docker Compose, usando Redis standalone, Redis Cluster, múltiplas instâncias da aplicação e Nginx como balanceador gRPC.

## Arquivos envolvidos

Estrutura esperada:

```text
.
├── .env
├── .env.example
├── docker-compose.yaml
├── Dockerfile
├── docker/
│   ├── nginx/
│   │   └── nginx.conf
│   └── redis-cluster/
│       └── cluster-init.sh
└── klimiter-service/
```

## `.env`

O arquivo `.env` contém as variáveis reais usadas localmente pelo Docker Compose e pela aplicação.

Crie o `.env` a partir do `.env.example`:

```bash
cp .env.example .env
```

Exemplo:

```env
# Application
SPRING_APPLICATION_NAME=klimiter-service

# HTTP server
SERVER_PORT=8080

# gRPC server
GRPC_PORT=9090

# Actuator / Management
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,env,configprops
MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true

# Tracing
TRACING_SAMPLING_PROBABILITY=0

# OTLP Metrics
OTLP_METRICS_EXPORT_ENABLED=false

# OTLP Tracing
OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces

# OTLP Logging
OTLP_LOGGING_ENDPOINT=http://localhost:4318/v1/logs

# Logging
LOGGING_LEVEL_ROOT=INFO

# KLimiter backend
# Options: IN_MEMORY, REDIS_STANDALONE, REDIS_CLUSTER
KLIMITER_BACKEND_MODE=IN_MEMORY

# Redis standalone
KLIMITER_BACKEND_REDIS_URI=redis://localhost:6379

# Redis cluster
KLIMITER_BACKEND_REDIS_URIS=redis://localhost:7001,redis://localhost:7002,redis://localhost:7003

# Redis lease configuration
KLIMITER_BACKEND_REDIS_LEASE_PERCENTAGE=10
KLIMITER_BACKEND_REDIS_KEY_PREFIX=klimiter
```

## Diferença entre `.env` e `.env.example`

O `.env.example` deve ser versionado no Git como modelo de configuração.

O `.env` deve conter os valores reais do ambiente local e não deve ser versionado.

Adicione ao `.gitignore`:

```gitignore
.env
```

## Uso do `env_file`

O `docker-compose.yaml` usa:

```yaml
env_file:
  - .env
```

Isso injeta as variáveis do `.env` dentro dos containers da aplicação.

Algumas variáveis são sobrescritas diretamente no `environment` de cada service, porque dentro do Docker não devemos usar `localhost` para acessar outro container.

Exemplo:

```yaml
environment:
  KLIMITER_BACKEND_MODE: REDIS_STANDALONE
  KLIMITER_BACKEND_REDIS_URI: redis://redis-standalone:6379
```

Dentro de um container, `localhost` aponta para o próprio container. Por isso, a aplicação deve acessar Redis usando o nome do service Docker, como:

```text
redis-standalone
redis-cluster-1
redis-cluster-2
redis-cluster-3
```

## Profiles disponíveis

O Docker Compose está organizado com os seguintes profiles:

| Profile | Descrição |
|---|---|
| `redis_standalone` | Sobe Redis standalone e Redis Insight |
| `redis_cluster` | Sobe Redis Cluster e Redis Insight |
| `app_standalone` | Sobe Redis standalone, Redis Insight e uma instância da aplicação |
| `app_cluster` | Sobe Redis Cluster, Redis Insight, três instâncias da aplicação e Nginx |

## Subir Redis standalone

```bash
docker compose --profile redis_standalone up -d
```

Esse profile sobe:

```text
redis-standalone
redis-insight
```

Acesso:

```text
Redis: localhost:6379
Redis Insight: http://localhost:5540
```

## Subir Redis Cluster

```bash
docker compose --profile redis_cluster up -d
```

Esse profile sobe:

```text
redis-cluster-1
redis-cluster-2
redis-cluster-3
redis-cluster-init
redis-insight
```

Acesso externo aos nós:

```text
redis-cluster-1: localhost:7001
redis-cluster-2: localhost:7002
redis-cluster-3: localhost:7003
```

Dentro da rede Docker, a aplicação usa:

```text
redis://redis-cluster-1:7001
redis://redis-cluster-2:7002
redis://redis-cluster-3:7003
```

## Subir aplicação com Redis standalone

```bash
docker compose --profile app_standalone up -d --build
```

Esse profile sobe:

```text
redis-standalone
redis-insight
app-standalone
```

Portas expostas:

```text
HTTP: localhost:8080
gRPC: localhost:9090
Redis Insight: http://localhost:5540
```

Nesse modo, a aplicação usa:

```env
KLIMITER_BACKEND_MODE=REDIS_STANDALONE
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

## Subir aplicação em cluster com Nginx

```bash
docker compose --profile app_cluster up -d --build
```

Esse profile sobe:

```text
redis-cluster-1
redis-cluster-2
redis-cluster-3
redis-cluster-init
redis-insight
app-1
app-2
app-3
nginx
```

Portas expostas:

```text
gRPC via Nginx: localhost:9090
Redis Insight: http://localhost:5540
```

Nesse modo, a aplicação usa:

```env
KLIMITER_BACKEND_MODE=REDIS_CLUSTER
KLIMITER_BACKEND_REDIS_URIS=redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

O Nginx fica responsável por distribuir chamadas gRPC entre:

```text
app-1:9090
app-2:9090
app-3:9090
```

## Testar health check

Para a aplicação standalone:

```bash
curl http://localhost:8080/actuator/health
```

Resposta esperada:

```json
{
  "status": "UP"
}
```

No modo `app_cluster`, a porta HTTP das aplicações não é exposta diretamente. As instâncias ficam acessíveis apenas dentro da rede Docker.

## Testar gRPC com `grpcurl`

Exemplo para testar a porta gRPC:

```bash
grpcurl \
  -plaintext \
  -emit-defaults \
  -d '{"descriptors":[{"entries":[{"key":"user_id","value":"user_1"}]}],"domain":"default"}' \
  localhost:9090 \
  io.klimiter.RateLimitService.ShouldRateLimit
```

No profile `app_standalone`, a chamada vai direto para a aplicação.

No profile `app_cluster`, a chamada vai para o Nginx, que balanceia entre as instâncias.

## Rebuild das aplicações

Quando alterar código da aplicação ou Dockerfile:

```bash
docker compose --profile app_standalone up -d --build
```

ou:

```bash
docker compose --profile app_cluster up -d --build
```

Para forçar rebuild sem cache:

```bash
docker compose build --no-cache
```

Depois suba novamente:

```bash
docker compose --profile app_cluster up -d
```

## Parar containers

Parar os containers do projeto:

```bash
docker compose down
```

Parar e remover volumes:

```bash
docker compose down -v
```

Use `-v` quando quiser limpar completamente dados locais do Redis.

## Ver logs

Todos os services:

```bash
docker compose logs -f
```

Aplicação standalone:

```bash
docker compose logs -f app-standalone
```

Aplicações em cluster:

```bash
docker compose logs -f app-1 app-2 app-3
```

Nginx:

```bash
docker compose logs -f nginx
```

Redis Cluster init:

```bash
docker compose logs -f redis-cluster-init
```

## Ver containers ativos

```bash
docker compose ps
```

## Executar comandos no Redis standalone

```bash
docker compose exec redis-standalone redis-cli ping
```

Resultado esperado:

```text
PONG
```

## Executar comandos no Redis Cluster

Entrar em um nó:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001
```

Ver informações do cluster:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster info
```

Ver nós do cluster:

```bash
docker compose exec redis-cluster-1 redis-cli -p 7001 cluster nodes
```

## Redis Insight

Redis Insight sobe nos profiles:

```text
redis_standalone
redis_cluster
app_standalone
app_cluster
```

Acesse:

```text
http://localhost:5540
```

Conexões sugeridas:

### Standalone

```text
Host: redis-standalone
Port: 6379
Alias: standalone
```

### Cluster

```text
Host: redis-cluster-1
Port: 7001
Alias: cluster-node-1
```

## Observabilidade OTLP

Por padrão, no `.env` local:

```env
OTLP_METRICS_EXPORT_ENABLED=false
OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces
OTLP_LOGGING_ENDPOINT=http://localhost:4318/v1/logs
```

Se um collector ou Grafana LGTM estiver rodando dentro do Docker Compose, não use `localhost` dentro da aplicação. Use o nome do service Docker.

Exemplo:

```env
OTLP_TRACING_ENDPOINT=http://otel-lgtm:4318/v1/traces
OTLP_LOGGING_ENDPOINT=http://otel-lgtm:4318/v1/logs
```

## Atenção sobre `localhost`

Fora do Docker:

```env
KLIMITER_BACKEND_REDIS_URI=redis://localhost:6379
```

Dentro do Docker:

```env
KLIMITER_BACKEND_REDIS_URI=redis://redis-standalone:6379
```

Fora do Docker, `localhost` aponta para sua máquina.

Dentro de um container, `localhost` aponta para o próprio container.

Por isso o `docker-compose.yaml` sobrescreve as URIs de Redis nos services da aplicação.

## Estratégia recomendada

Use `.env` para configurações operacionais:

```text
portas
logs
observabilidade
modo do backend
Redis URI
prefixo das chaves
lease percentage
```

Mantenha regras complexas de domínio em YAML, por exemplo:

```yaml
klimiter:
  domains:
    - id: default
      descriptors:
        - key: user_id
          rule:
            unit: SECOND
            requests-per-unit: 100
```

Listas e objetos aninhados ficam difíceis de manter em variável de ambiente. Para regras de rate limit, YAML é mais legível e menos frágil.

## Comandos principais

Redis standalone:

```bash
docker compose --profile redis_standalone up -d
```

Redis Cluster:

```bash
docker compose --profile redis_cluster up -d
```

App standalone:

```bash
docker compose --profile app_standalone up -d --build
```

App cluster com Nginx:

```bash
docker compose --profile app_cluster up -d --build
```

Parar tudo:

```bash
docker compose down
```

Limpar tudo, incluindo volumes:

```bash
docker compose down -v
```

## Sugestão de commit

```bash
git add DOCKER.md docker-compose.yaml .env.example
git commit -m "docs: add Docker usage guide"
```
