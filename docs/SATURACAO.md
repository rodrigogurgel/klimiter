# Saturação — encontrando o joelho

Como medir o **teto de throughput** do klimiter: subir o RPS em degraus e achar o **joelho** — o
maior RPS sustentável com **p99 < 15 ms**. Usa `ghz` (overhead de cliente desprezível) contra o
servidor gRPC; cada requisição é o lote all-or-nothing de 3 dimensões (§7).

> Scripts em [`test/load/`](../test/load/README.md). Esta página é a **metodologia + como rodar**.

---

## 1. Metodologia (apples-to-apples)

- **2 CPU pinados** no serviço, **sem quota** (`taskset -c 0,1` + `-XX:ActiveProcessorCount=2`) — ver
  a pegadinha em §4. Redis fica **livre** (sem limite).
- **OTel/observação OFF** (ceiling limpo): exclui o interceptor de observação por-RPC, tracing e o
  SDK OTel — senão a telemetria por requisição infla a latência.
- **JVM**: `-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx768m`.
- **Políticas de carga** ([`test/load/policies.sample.yaml`](../test/load/policies.sample.yaml)):
  `flow` é a **hot key** (`flow-0`, cap 6000/s, `FLOW_DISTINCT_KEYS=1`); `account`/`source` têm
  cardinalidade alta/média. Sem essas dimensões tudo vira pass-through (§8) e o teste não mede nada.
- **`ghz` nos cores restantes** (host tem 16; serviço em 2) — sem contenção com o servidor.
- Um servidor **por vez**.

O alvo `make sat-server` aplica as três primeiras (pinning, OTel off, políticas de carga) de uma vez.

---

## 2. Como rodar

> **Use jar + `taskset`** (pinning real). É o único harness confiável aqui — o Docker (`cpus` quota
> **ou** `cpuset` + export via container) distorce os números (§4). Pré-requisito: Redis no ar
> (`docker compose up -d redis`) e `ghz` instalado.

### Com `make` (dois terminais)

```bash
make sat-server                            # terminal 1: serviço pinado em 2 cores, OTel off (builda o bootJar)
make saturation PRIORITY=PRIORITY_LOW      # terminal 2: sweep (ver knobs na §3)
make saturation PRIORITY=PRIORITY_HIGH SAT_STEPS="2000 8000 12000 16000 20000 24000" SAT_DURATION=15s
```

### À mão (para ajustar os flags)

`./gradlew bootJar -x test` e, com o Redis no ar:

```bash
JAR=$(ls build/libs/klimiter-*.jar | grep -v plain)
KLIMITER_REDIS_URI=redis://localhost:6379 KLIMITER_POLICIES_PATH=test/load/policies.sample.yaml \
taskset -c 0,1 java \
  -XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=2 -Xms512m -Xmx768m \
  -Dspring.autoconfigure.exclude=org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration \
  -Dmanagement.tracing.enabled=false -Dmanagement.otlp.metrics.export.enabled=false -Dotel.sdk.disabled=true \
  -jar "$JAR" &
STEPS="2000 8000 12000 14000 16000 18000" DURATION=15s PRIORITY=PRIORITY_LOW \
  bash test/load/performance/saturation-ghz.sh
```

---

## 3. Knobs e leitura da saída

| Variável | Default | O que faz |
|---|---|---|
| `PRIORITY` (`SAT_…` no make) | `PRIORITY_HIGH` | `PRIORITY_LOW` (pacing, 1 round-trip/req) ou `PRIORITY_HIGH` (prefetch + short-circuit) |
| `STEPS` / `SAT_STEPS` | degraus de RPS | lista de degraus |
| `DURATION` / `SAT_DURATION` | `20s` | duração de cada degrau |
| `KNEE_MS` | `15` | limite de p99 que define o joelho |
| `FLOW_DISTINCT_KEYS` | `1` | `1` = hot key (contenção); alto = espalha |
| `ACCOUNT/SOURCE_DISTINCT_KEYS` | `50000`/`100` | cardinalidade das outras dimensões |

```
 rps_alvo   rps_real    p50ms    p95ms    p99ms    maxms   nonOK
     2000       2000     0.42     7.26   103.49   198.06       2   ← descarte (warmup/JIT frio)
    14000      13999     0.89     3.18     6.70    31.96      36   ← p99 < 15  → OK
    16000      15999     1.89     9.06    39.17   116.58      11   ← p99 ≥ 15  → passou do joelho
```

- **Joelho** = último RPS com `p99 < KNEE_MS` (acima, ~14k).
- O **primeiro degrau é warmup** (JIT frio) — sempre comece com um degrau baixo e ignore-o.
- `nonOK` = erros de transporte (deadline/refused): poucos é normal perto do teto; muitos = colapso.
- O script **para sozinho** um degrau após cruzar o joelho.

---

## 4. Por que não Docker (a pegadinha de CPU)

**Meça com jar + `taskset`, não com Docker.** O `cpus: N` impõe uma **quota CFS**: o cgroup é
*throttled* a cada ~100 ms ao estourar o orçamento → picos de latência que derrubam a p99/joelho. E
mesmo com `cpuset` (pinning), o container + o export OTLP via rede do container distorcem o caso
instrumentado. Os números confiáveis vieram do jar pinado.

| Harness (2 cores, `PRIORITY_LOW`, mesmo binário) | Joelho |
|---|---|
| Docker `cpus: 2` (quota CFS) | ~5k ⚠️ artefato |
| Docker `cpuset: "0,1"` | ~12–14k (instrumentado instável) |
| **jar + `taskset -c 0,1`** | **~14k** ✅ confiável |

> ⚠️ **Isto vale para Kubernetes:** `resources.limits.cpu` usa a **mesma quota CFS**. Ver §6.

---

## 5. Resultados (referência desta máquina)

2 cores pinados (cpuset), ZGC, Redis livre, `flow-0` cap 6000/s, `DURATION=15s`, sweeps únicos
(ruidosos perto do joelho — **faixas**, não pontos exatos). Números absolutos dependem da máquina; o
que vale é a ordem de grandeza e o **delta** entre cenários.

### 5.1 Joelho (OTel off)

| Caminho | Joelho (p99 < 15 ms) |
|---|---|
| `PRIORITY_LOW`  | **~14k RPS** |
| `PRIORITY_HIGH` | **≥16k RPS** (p99 < 4 ms até 16k; não satura na faixa medida) |

### 5.2 Custo da observabilidade — e a causa

Decomposição no harness **jar + `taskset`** (mais limpo que o Docker), variando um componente por vez
no caminho `PRIORITY_LOW`:

| Cenário | Joelho | vs OFF |
|---|---|---|
| OFF (sem OTel/observação) | ~14k | — |
| ON completo (tracing + métricas + export + observação por-RPC) | ~11k | **−~20%** |
| ON **sem a observação por-RPC** (tracing + export mantidos) | **~14k** | **~0%** |

**A causa é a observação por-RPC do Spring gRPC** — o `ObservationGrpcServerInterceptor` registrado
pelo `GrpcServerObservationAutoConfiguration`. Ele embrulha **toda** chamada numa `Observation`
(start/stop, timer, criação + sampling de span, propagação de contexto) → CPU por requisição.
Removendo **só ela**, o joelho volta a ~14k: ou seja, **o export OTLP, o tracing e nossos contadores
`klimiter.*` custam ~nada** — o peso é praticamente todo o interceptor por-RPC.

Por que o `LOW` sente e o `HIGH` quase não: o LOW é Redis-bound sob 2 cores (cada request já espera o
round-trip), então a CPU gasta na observação por-RPC empurra a p99 sobre 15 ms mais cedo; o HIGH
amortiza local (prefetch + short-circuit) e absorve.

> **Nota de harness:** no Docker (`cpuset` + export container→lgtm) o custo aparente chega a ~−40%;
> no jar+`taskset` o número real é ~−20%, **todo** atribuível à observação por-RPC. Sempre meça o
> delta no mesmo harness.

**Ponto negociável (recupera o throughput):** amostrar a observação por-RPC via
`klimiter.observability.grpc-sample-rate` (`1.0` = todas, default; `0.0` = desliga; ex.: `0.1` = 10%)
— as não-amostradas viram NOOP (custo ~zero). Mantém os contadores `klimiter.*`, o gauge do índice e
as métricas de JVM/sistema + export OTLP (todos baratos); só a telemetria `grpc.server` (span + timer)
fica amostrada. Em produção com mais cores a folga absorve o custo; o knob é para quando o teto do LOW
importa. (Implementado por um `ObservationPredicate`; ver `config/ObservabilityConfiguration`.)

---

## 6. Em Kubernetes — os números transferem?

**Só se o pod tiver 2 cores DEDICADOS, sem throttling de CFS.** A pegadinha da §4 vale igual no K8s:
`resources.limits.cpu` é implementado como **quota CFS** — um `limits.cpu: "2"` throttla o pod a cada
~100 ms exatamente como o `cpus: 2` do Docker (que deu ~5k, não ~14k).

| Config do pod | O que acontece | Joelho esperado |
|---|---|---|
| `limits.cpu: 2` (sem CPU manager static) | quota CFS → throttling → picos de p99 | **pior** (~5k) ⚠️ |
| **Guaranteed + `cpuManagerPolicy: static`** (requests==limits, CPU **inteiro**) | cores **exclusivos** (= `taskset`) | **~14k** ✅ representativo |
| `requests.cpu: 2`, **sem** `limits.cpu` (Burstable) | pode estourar 2 cores | **≥ 14k** (o teste vira piso) |

**Recomendação** (rate limiter sensível a latência):
- **Fiel ao teste / melhor isolamento:** QoS **Guaranteed** (requests == limits, CPU **inteiro**) com
  o kubelet em `--cpu-manager-policy=static` → cores exclusivos, sem quota. Aí ~14k é representativo.
- **Pragmático:** definir só `requests.cpu` (sem `limits.cpu`) → evita o throttling; o número de 2 CPU
  vira um **piso conservador** (o pod pode usar mais).
- **Evite:** `limits.cpu` em orçamento apertado **sem** CPU manager static — é a armadilha do throttling.

Config extra do pod (independente do acima):
- **JVM:** `-XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=N` (fixe N = nº de cores do pod) +
  heap via `-XX:MaxRAMPercentage` casado com `resources.limits.memory`.
- **Políticas:** ConfigMap montado como **diretório** (o hot-reload observa o diretório; update de
  ConfigMap é swap atômico de symlink — por isso montamos o dir, não o arquivo, §8).
- **Health:** probe **gRPC nativa** do K8s (`grpc:`, ≥ 1.24) — dispensa o `grpc_health_probe` da imagem.
- **OTel:** `OTEL_EXPORTER_OTLP_ENDPOINT` → collector do cluster. Sob CPU apertada, lembre do custo da
  observação por-RPC (§5.2) — considere a amostragem.
- **Redis:** durável o bastante para sobreviver à janela (§11) e, de preferência, fora do nó do limiter.
