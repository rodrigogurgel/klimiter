# Testes de carga — klimiter

Testes **fora do `./gradlew test`** (precisam do serviço de pé e de ferramentas externas).
Portados do baseline e ajustados para o contrato do klimiter (`klimiter.v1.RateLimitService`,
porta gRPC `9090`). Separados por objetivo:

```
test/load/
├─ performance/
│  └─ saturation-ghz.sh           # PERFORMANCE: teto de throughput / joelho da p99 (ghz)
├─ behavior/
│  └─ evaluate-online-variavel.js # COMPORTAMENTO: interação de prioridade sob carga variável (k6)
└─ policies.sample.yaml           # dimensões usadas pelos testes (rate_limit_account_id/source/flow)
```

## Pré-requisitos

1. **Serviço no ar** em `localhost:9090` (gRPC). O mais simples: `docker compose up`.
2. **Dimensões no `policies.yaml`.** Os testes usam `rate_limit_account_id`, `rate_limit_source`,
   `rate_limit_flow`. Sem elas, tudo é pass-through (§8) e nada é limitado. Use
   [`policies.sample.yaml`](policies.sample.yaml): copie as dimensões para
   `config/policies/policies.yaml` (o **hot reload** aplica ao vivo, §8) ou suba o serviço com
   `KLIMITER_POLICIES_PATH=test/load/policies.sample.yaml`.
3. **Ferramenta** do teste: [`ghz`](https://ghz.sh) (performance) ou
   [`k6`](https://k6.io) com suporte a gRPC (comportamento).

## Performance — `performance/saturation-ghz.sh`

Sobe o RPS em degraus e acha o **joelho**: o maior RPS em que a p99 ainda fica abaixo de `KNEE_MS`.
Cada requisição é o lote all-or-nothing de 3 dimensões (§7).

> **Metodologia completa** (2 CPU pinados via `taskset`, OTel off, leitura da saída, por que não medir
> em Docker/K8s com quota CFS, e os resultados): [`docs/SATURACAO.md`](../../docs/SATURACAO.md).
> Atalho: `make sat-server` (terminal 1) + `make saturation PRIORITY=PRIORITY_LOW` (terminal 2).

```bash
# a partir da raiz do repositório
bash test/load/performance/saturation-ghz.sh
PRIORITY=PRIORITY_LOW STEPS="6000 8000 10000" bash test/load/performance/saturation-ghz.sh
```

Principais variáveis: `TARGET` (default `localhost:9090`), `PRIORITY`, `DURATION`, `KNEE_MS`,
`STEPS`, e os espaços de chave por dimensão `ACCOUNT_DISTINCT_KEYS` / `SOURCE_DISTINCT_KEYS` /
`FLOW_DISTINCT_KEYS` (`1` = chave fixa/quente; alto = ~0% negação, mede o caminho ao Redis).
Para medir **throughput puro** (sem negar), suba os caps no `policies.yaml` ou aumente
`FLOW_DISTINCT_KEYS`.

## Comportamento — `behavior/evaluate-online-variavel.js`

Dois fluxos concorrentes no mesmo contador: `online` (`PRIORITY_HIGH`, oscila em onda) e
`baixa_prioridade` (`PRIORITY_LOW`, constante). Valida a **interação de prioridade** (§6.5): quando
a ALTA sobe, a BAIXA é estrangulada pela linha de pacing; quando a ALTA cede, a BAIXA recupera. Os
`thresholds` do k6 são os **critérios de aceite**.

```bash
k6 run test/load/behavior/evaluate-online-variavel.js
k6 run -e DURATION=1m -e ONLINE_MAX_RPS=70 -e BAIXA_PRIORIDADE_RPS=1000 \
  test/load/behavior/evaluate-online-variavel.js

# exportando as métricas do k6 para o LGTM do compose (Grafana em http://localhost:3000):
K6_OTEL_GRPC_EXPORTER_ENDPOINT=localhost:4317 K6_OTEL_GRPC_EXPORTER_INSECURE=true \
  k6 run -e DISTINCT_KEYS=1 --out opentelemetry test/load/behavior/evaluate-online-variavel.js
```

Para **ver a prioridade agir**, crie contenção: `DISTINCT_KEYS=1` (chave única quente) e mantenha o
cap de `rate_limit_flow` baixo (como no sample) — assim a linha binda e a BAIXA é paceada.

## Contrato gRPC usado pelos testes

- **serviço/método:** `klimiter.v1.RateLimitService/ShouldRateLimit`
- **request:** `RateLimitRequest { descriptors: [{ key, value, hits, priority }] }`
- **response:** `overallStatus` + `decisions[].status` ∈ `STATUS_ALLOWED|DENIED|UNKNOWN`
- **porta** `9090`, **proto** `klimiter/v1/ratelimit.proto`
