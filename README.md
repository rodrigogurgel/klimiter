# klimiter

**Rate limiter distribuído por janela fixa (fixed window)**, exposto via **gRPC**, escrito em
**Kotlin** sobre **Spring Boot 4 / Spring gRPC**.

Vários nós (réplicas) compartilham um **contador por janela** num armazenamento central (Redis,
standalone ou cluster). Toda admissão é um **incremento condicional atômico** nesse contador —
nunca escreve acima do limiar (over-admission zero por construção) e nega **sem escrever**. O nó
local **só nega** (snapshot monotônico aprendido de cada resposta), nunca admite sozinho. Tráfego é
classificado em **duas prioridades**: a **alta** é limitada só pela capacidade da janela; a
**baixa** passa por *pacing* — uma linha de liberação que cresce no tempo e cede capacidade
para a alta.

> A lógica completa (invariantes, fluxos, premissas) está em
> **[`docs/DESIGN-CONCEITUAL-V2.md`](docs/DESIGN-CONCEITUAL-V2.md)** — a fonte da verdade do
> comportamento.

---

## Stack

| Item        | Valor                                          |
|-------------|------------------------------------------------|
| Linguagem   | Kotlin 2.3.21 (JVM, toolchain JDK 25)          |
| Framework   | Spring Boot 4.1 + Spring gRPC                  |
| Transporte  | gRPC + Protobuf                                |
| Build       | Gradle 9.6 (Kotlin DSL, via `./gradlew`)       |
| Central     | Redis (contador por janela, scripts Lua atômicos) |
| Pacote raiz | `io.github.rodrigogurgel.klimiter`             |

## Como funciona (em 30s)

- A **verdade global** é um contador por janela no Redis — só **cresce** dentro da janela (não há
  refund), nunca passa da capacidade (incremento **condicional**) e expira com ela.
- Cada nó guarda um **snapshot monotônico** (o maior contador que já viu). Como o contador só cresce,
  o snapshot é um **limite inferior garantido** — o nó **nega** de graça o que o central garantidamente
  negaria; **admitir** exige sempre o round-trip.
- **Alta prioridade:** limitada só pela capacidade da janela.
- **Baixa prioridade (pacing):** só admite abaixo da **linha de liberação** (`capacidade × decorrido /
  duração`), validada contra o contador verdadeiro — quando a alta consome forte, a baixa é estrangulada;
  quando a alta esvazia, a baixa acelera.
- Um pedido do cliente é um **lote** de dimensões (ex.: por `user_id`, por `ip`, por `tenant`), avaliado
  **all-or-nothing**: reserva **sequencial, ordenada pela pressão observada** de cada chave (o negador
  mais provável primeiro), abortando na primeira negação.

## Começando

Pré-requisitos: **JDK 25** e **Docker** (para o Redis).

### Stack local completa (Docker)

```bash
make up          # sobe klimiter + Redis + RedisInsight + Grafana/OTel-LGTM e builda a imagem
# gRPC:         localhost:9090
# Grafana:      http://localhost:3000
# RedisInsight: http://localhost:5540
make logs        # acompanha os logs do serviço
make down        # derruba a stack (make down ARGS=-v apaga volumes)
```

O Redis sobe em **dois modos mutuamente exclusivos** (profiles do compose, selecionados por
`COMPOSE_PROFILES`; o `.env` já default para `standalone`):

```bash
make up           # standalone: klimiter + 1 Redis (default)
make up-cluster   # cluster:    klimiter-cluster + 3 nós Redis Cluster (flag klimiter.redis.cluster)
make logs-cluster # logs do serviço no modo cluster
```

Escolher um modo **não** sobe o outro: `make up-cluster` não inicia o Redis standalone e
`make up` não inicia o cluster. Em produção, use `KLIMITER_REDIS_CLUSTER=true` ao apontar para um
Redis Cluster / ElastiCache (cluster mode enabled) — ver [docs/VARIAVEIS-DE-AMBIENTE.md](docs/VARIAVEIS-DE-AMBIENTE.md).

### Só o serviço (Gradle), com Redis no Docker

```bash
docker compose up -d redis
KLIMITER_POLICIES_PATH=config/policies/policies.yaml ./gradlew bootRun
```

### Uma chamada de exemplo (grpcurl)

```bash
grpcurl -plaintext -import-path src/main/proto -proto klimiter/v1/ratelimit.proto \
  -d '{"descriptors":[{"key":"rate_limit_flow","value":"flow-0","hits":1,"priority":"PRIORITY_HIGH"}]}' \
  localhost:9090 klimiter.v1.RateLimitService.ShouldRateLimit
```

A resposta traz o **status coletivo** do lote e a **decisão por item** (`ALLOWED` / `DENIED` /
`UNKNOWN`), mais `remaining`, `reset_after` e `capacity`. Contrato em
[`src/main/proto/klimiter/v1/ratelimit.proto`](src/main/proto/klimiter/v1/ratelimit.proto).

## Políticas

Os limites vivem em [`config/policies/policies.yaml`](config/policies/policies.yaml) (modelo Envoy RLS:
`requests_per_unit` + `unit`), com **hot reload** — editar o arquivo aplica ao vivo, sem reiniciar.
Dimensão sem política → **pass-through** (admitida, não cobra nada). Formato e resolução em
[`docs/POLITICAS.md`](docs/POLITICAS.md).

## Build, testes e qualidade

```bash
./gradlew build      # compila + testa + verificações
./gradlew test       # testes (gera cobertura JaCoCo)
./gradlew detekt     # lint estático (config/detekt/detekt.yml)
./gradlew check      # test + detekt + verificações agregadas
./gradlew bootRun    # sobe o serviço localmente
```

## Performance e dimensionamento

Como medir o **joelho** (saturação) e dimensionar por cores/pods está em
[`docs/SATURACAO.md`](docs/SATURACAO.md) e
[`docs/OTIMIZACAO-THROUGHPUT.md`](docs/OTIMIZACAO-THROUGHPUT.md), incluindo recomendações para
**Kubernetes e Fargate**. Atenção: os números publicados lá são **pré-V2** (medidos sobre a
arquitetura de leases); re-medição é o marco M6 do [plano](docs/PLANO-IMPLEMENTACAO.md).

```bash
make sat-server                            # serviço pinado em 2 cores, OTel off (harness de saturação)
make saturation PRIORITY=PRIORITY_HIGH     # sweep de RPS até o joelho (noutro terminal)
```

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [`docs/DESIGN-CONCEITUAL-V2.md`](docs/DESIGN-CONCEITUAL-V2.md) | A lógica do rate limiter (*o quê*): invariantes, fluxos, premissas. |
| [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) | Estrutura do código e regras de fronteira (hexagonal). |
| [`docs/POLITICAS.md`](docs/POLITICAS.md) | Formato do `policies.yaml`, JSON Schema e resolução. |
| [`docs/OBSERVABILIDADE.md`](docs/OBSERVABILIDADE.md) | Métricas, traces/spans e logs expostos. |
| [`docs/VARIAVEIS-DE-AMBIENTE.md`](docs/VARIAVEIS-DE-AMBIENTE.md) | Variáveis de ambiente (app, deps, JVM, build). |
| [`docs/SATURACAO.md`](docs/SATURACAO.md) | Medição do joelho + deployment (K8s/Fargate). |
| [`docs/OTIMIZACAO-THROUGHPUT.md`](docs/OTIMIZACAO-THROUGHPUT.md) | Otimização do joelho, escala por cores e dimensionamento. |
| [`deployments/README.md`](deployments/README.md) | Manifestos de Kubernetes/EKS: pré-requisitos e ordem de aplicação. |
| [`AGENTS.md`](AGENTS.md) | Guia rápido para agentes de IA e novos integrantes. |

## Contribuição

Git flow (`main`/`develop` + `feature/*`), Conventional Commits (CHANGELOG gerado por git-cliff) e o
checklist de PR estão em [`CONTRIBUTING.md`](CONTRIBUTING.md).
