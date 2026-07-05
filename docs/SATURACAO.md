# Saturação — encontrando o joelho

Como medir o **teto de throughput** do klimiter: subir o RPS em degraus e achar o **joelho** — o
maior RPS sustentável com **p99 < 15 ms**. Usa `ghz` (overhead de cliente desprezível) contra o
servidor gRPC; cada requisição é o lote all-or-nothing de 3 dimensões (§7).

> Scripts em [`scripts/load-test/`](../scripts/load-test/README.md). Esta página é a **metodologia + como rodar**.

---

## 1. Metodologia (apples-to-apples)

- **2 CPU pinados** no serviço, **sem quota** (`taskset -c 0,1` + `-XX:ActiveProcessorCount=2`) — ver
  a pegadinha em §4. Redis fica **livre** (sem limite).
- **OTel/observação OFF** (ceiling limpo): exclui o interceptor de observação por-RPC, tracing e o
  SDK OTel — senão a telemetria por requisição infla a latência.
- **JVM**: `-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx768m`.
- **Políticas de carga** ([`scripts/load-test/policies.sample.yaml`](../scripts/load-test/policies.sample.yaml)):
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
KLIMITER_REDIS_URI=redis://localhost:6379 KLIMITER_POLICIES_PATH=scripts/load-test/policies.sample.yaml \
taskset -c 0,1 java \
  -XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=2 -Xms512m -Xmx768m \
  -Dspring.autoconfigure.exclude=org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration \
  -Dmanagement.tracing.enabled=false -Dmanagement.otlp.metrics.export.enabled=false -Dotel.sdk.disabled=true \
  -jar "$JAR" &
STEPS="2000 8000 12000 14000 16000 18000" DURATION=15s PRIORITY=PRIORITY_LOW \
  bash scripts/load-test/performance/saturation-ghz.sh
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

---

## 7. Premissas de transferência p/ Kubernetes (o que a régua NÃO captura)

Estes números são um **piso de sanidade e um mapa de *inclinações/ratios*** — **não** uma régua
absoluta de produção. São medidos num **host único** (servidor + `ghz` + Redis na VM do Docker
disputando a mesma máquina), com **carga sintética** (lote fixo de 3 dimensões, **puro HIGH ou puro
LOW**, `hits=1`), **Redis local** e **sem ingress/mesh**. Antes de usá-los como fonte da verdade do
cluster, conheça o que **não** está embutido. *(Referência de escala medida: HIGH ~linear ~+19k
RPS/core; LOW plateau ~18k/pod; instrumentação ON ~−20% no HIGH — ver
[OTIMIZACAO-THROUGHPUT.md](OTIMIZACAO-THROUGHPUT.md).)*

### 7.1 O que torna os números FICÇÃO se ignorado

- **CFS throttling (§4/§6).** `limits.cpu` = quota CFS → throttle a cada ~100 ms → a p99 desaba e o
  joelho craterа. A régua **só vale** com QoS **Guaranteed + `cpu-manager-policy=static`** (cores
  exclusivos, = `taskset`) **ou** `requests.cpu` **sem** `limits.cpu`. Com `limits.cpu` apertado os
  números são fantasia.
- **Heap acoplado à memória (ZGC stalla com heap apertado).** Sob rajada em poucos cores, `-Xmx` baixo
  (ex.: 768m) faz o ZGC **stallar** → colapsos transitórios de p99 que derrubam o joelho. Regra de
  bolso: **`-Xmx` ≥ ~1 GB/core** (via `-XX:MaxRAMPercentage` casado com `limits.memory`). **A régua é
  (cores ​**E**​ memória)** — dimensionar core sem memória reproduz o colapso.

### 7.2 O que torna os números OTIMISTAS (gap de ambiente)

- **Redis pela rede derruba o LOW.** O LOW é **latency-bound** (não é CPU do servidor nem do Redis —
  medido). Redis em outro pod/nó soma RTT de rede a **cada** round-trip (§6.1) → o plateau de ~18k/pod
  **cai** proporcionalmente. O LOW local é teto otimista de co-locação.
- **Teto agregado de Redis não medido.** "Cada pod soma ~18k de LOW" vale **até o Redis agregado
  saturar** — N pods batendo num Redis fazem dele o gargalo. A escala horizontal do LOW tem um teto de
  Redis que **este teste não exercita**.
- **Sem ingress/mesh.** O `ghz` bate no loopback. kube-proxy/iptables, Service/LB, sidecar, TLS e o RTT
  real do cliente consomem do orçamento de p99 e CPU por request — **em cima** do custo da observação
  per-RPC (§5.2).

### 7.3 O que limita a GENERALIZAÇÃO

- **Linearidade validada só até ~4 cores.** Acima disso o `ghz` local satura antes do servidor (joelho
  de HIGH ≥ 4 cores **não é mensurável** numa só máquina). Em pods grandes podem surgir NUMA, limite de
  event-loops do Netty e **contenção de lock** (`renewMutex` por bucket, `ConcurrentHashMap`) — **não
  assuma `+19k/core` linear indefinidamente**; prefira escalar **horizontal**.
- **Workload-bound.** O joelho do LOW depende de quão often a chave quente faz short-circuit vs vai ao
  central — função da **sua** carga (cardinalidades, tamanho do lote, nº de dimensões). E o teste usa
  **prioridade pura**; a dinâmica de **prioridade mista** no mesmo lote (§6.5) **não foi medida sob
  carga**.
- **SLO-específico.** O joelho é definido por `KNEE_MS` (p99 < 15 ms aqui). Outro SLA → outro joelho.
- **Zero efeitos distribuídos.** Foi **1 processo**. Skew de relógio, durabilidade do Redis no failover,
  thundering-herd do latch e prefetch encalhado entre pods são **premissas de corretude** (§11), não de
  perf, e **não** são exercitadas aqui.
- **Cold start.** Medido com JIT quente; pod recém-escalado pela HPA entrega bem menos por ~30–60 s —
  deixe **headroom**/pré-aquecimento.

### 7.4 Para virar régua de verdade

Calibre **in-cluster**: QoS Guaranteed (ou sem `limits.cpu`) + memória ≥ ~1 GB/core, Redis na topologia
real, **carga representativa com prioridade mista**, e meça o **agregado de N pods contra o Redis real**
para achar o teto horizontal. Dimensione por **(cores ​**E**​ memória)** com o **SLO explícito**.

---

## 8. Recomendações de deployment (Kubernetes e Fargate)

Consolida as decisões de runtime. O **dimensionamento por carga** (cores p/ HIGH, pods p/ LOW) está em
[OTIMIZACAO-THROUGHPUT.md](OTIMIZACAO-THROUGHPUT.md); aqui ficam **CPU/QoS, heap/memória e Fargate**.

> Os **manifestos prontos** (Namespace, Secret, ConfigMap, Deployment, Service, HPA/PDB) e a ordem de
> `kubectl apply` estão em [`deployments/README.md`](../deployments/README.md).

### 8.1 Heap e memória — o ZGC precisa de folga

**Heap apertado derruba o joelho** (não por falta de throughput, mas porque o ZGC **stalla** sob rajada
em poucos cores → picos de p99). Sweep a **2 cores, OFF** (joelho p99 < 15 ms):

| `-Xmx` (= por core) | Joelho HIGH | Joelho LOW |
|---|---|---|
| 768m (384m/core) | ~24k | ~14k |
| 1024m (512m/core) | ~28k | ~16k |
| **2048m (1 GB/core)** | ~28–33k | **~18k** |
| 4096m (2 GB/core) | ~40k | ~18k |

- **Regra: `-Xmx` ≥ ~1 GB/core.** Abaixo disso o joelho cai ~20–40 % (stalls do ZGC). O **LOW plateia
  em ~18k já com 1 GB/core** — mais heap não sobe o LOW (volta a ser round-trip-bound). O HIGH (mais
  alocador) ainda ganha um pouco até ~2 GB/core, mas com retorno decrescente — o teto vira CPU.
- **`-XX:MaxRAMPercentage`** casado com `resources.limits.memory` (ex.: `limits.memory: 4Gi` +
  `MaxRAMPercentage=60` → ~2,4 GB de heap num pod de 2 cores = 1,2 GB/core). Deixe ~30–40 % do container
  para fora-do-heap (metaspace, threads, Netty/direct buffers, ZGC).
- **`-Xms=-Xmx`** (ou `InitialRAMPercentage=MaxRAMPercentage`): **commita o heap no boot**, evitando
  jitter de resize/page-fault sob a primeira rajada. *(Não isolei o efeito em teste — é estabilidade de
  latência, não teto de throughput.)*
- **`-XX:ActiveProcessorCount=N`** = nº de cores do pod (a auto-detecção erra sob cgroup/fração de vCPU
  e desalinha pools do Netty/ForkJoin/ZGC).

### 8.2 CPU e QoS (resumo do §6) + dimensionamento

- **Cores exclusivos, sem quota CFS:** QoS **Guaranteed + `cpu-manager-policy=static`** (cores inteiros)
  **ou** só `requests.cpu` sem `limits.cpu`. **Nunca** `limits.cpu` apertado sem CPU manager static (§4/§6).
- **HIGH escala vertical** (~+19k RPS/core); **LOW não** (plateau ~18k/pod, round-trip-bound). Logo:
  `cores ≈ RPS_HIGH/19000` (÷ 0,8 se OTel on) e **`réplicas ≥ ⌈RPS_LOW/18000⌉`** — o que for maior manda.
  LOW se escala **adicionando pods**, não cores.

```yaml
# Nó comum — QoS Guaranteed (requests == limits), CPU inteiro, kubelet com --cpu-manager-policy=static
resources: { requests: { cpu: "2", memory: 4Gi }, limits: { cpu: "2", memory: 4Gi } }
env:
  - { name: JAVA_TOOL_OPTIONS, value: "-XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=2 -XX:InitialRAMPercentage=60 -XX:MaxRAMPercentage=60" }
```

### 8.3 EKS Fargate (especificidades)

No Fargate **cada pod roda na sua própria micro-VM (Firecracker)** dimensionada pelos *requests* do pod,
arredondados para um combo válido de vCPU/memória (+ ~256 MB de overhead da VM).

**A favor (é bom para um rate limiter sensível a latência):**
- **vCPU dedicada, sem *noisy neighbor* nem throttling de CFS entre tenants** → comporta-se como
  **Guaranteed** por construção; os números pinados (§5) transferem melhor que num nó compartilhado.

**Cuidados (mudam o dimensionamento):**
- **Sem co-locar o Redis.** Fargate **não roda DaemonSet** nem sidecar com afinidade de host — o Redis é
  sempre **outra VM** (ElastiCache ou um Redis em nó normal). O round-trip pela rede a cada admissão (§6.1)
  **mexe no LOW, não no HIGH** (caminho local). Mas — medido injetando RTT no Redis (curva completa em
  [OTIMIZACAO-THROUGHPUT.md](OTIMIZACAO-THROUGHPUT.md)) — **o LOW é tolerante a RTT enquanto sobrar
  *headroom* sobre o SLO**: com SLO p99 < 15 ms, RTT ≤ ~1 ms ⇒ sem perda (~18k); 2–5 ms ⇒ perda pequena
  (~14–16k); o joelho só **despenca quando o RTT se aproxima do SLO** (RTT ~11 ms ⇒ ~3k). O fator é
  `headroom = SLO − RTT`. **Recomendação:** Redis (ElastiCache) na **mesma AZ** (RTT ~0,5–1 ms) ⇒ LOW
  fica perto do co-locado. Cuidado com **cross-region** ou **SLO apertado** (p99 < 5 ms torna 2–3 ms de
  RTT caro).
- **Combo vCPU×memória fixo.** vCPU ∈ {0,25; 0,5; 1; 2; 4; 8; 16}; a memória tem faixa por vCPU (ex.:
  2 vCPU → 4–16 GB). **Escolha o combo que dê ≥ 1 GB/core de heap** (§8.1) — fácil: 2 vCPU + 4–8 GB.
  Fixe `ActiveProcessorCount` = vCPU do combo (em fração de vCPU, force =1).
- **Cold start pior** (provisão da micro-VM **+** warmup do JVM, dezenas de segundos). A HPA não te dá
  capacidade instantânea → **mantenha headroom** (escale por utilização baixa, ou pré-aqueça). Não conte
  com escalar *durante* a rajada.
- **Sem `privileged`/`hostNetwork`/DaemonSet** e startup mais lento — planeje probes (`grpc:` nativa) com
  `initialDelaySeconds` folgado.

```yaml
# Fargate — micro-VM dedicada (= Guaranteed). Redis SEMPRE pela rede (ElastiCache/Service) → use mesma AZ.
resources: { requests: { cpu: "2", memory: 4Gi } }   # Fargate arredonda p/ um combo válido
env:
  - { name: JAVA_TOOL_OPTIONS, value: "-XX:+UseZGC -XX:+ZGenerational -XX:ActiveProcessorCount=2 -XX:InitialRAMPercentage=60 -XX:MaxRAMPercentage=60" }
  - { name: KLIMITER_REDIS_URI, value: "redis://meu-elasticache.xxxx.cache.amazonaws.com:6379" }
```

> Como sempre (§7): números absolutos **desta máquina**; o que transfere são as **inclinações/ratios**.
> No Fargate, valide o **LOW** in-place medindo o **RTT real ao Redis** e conferindo o *headroom* sobre o
> seu SLO — é o que governa o joelho do LOW (curva em [OTIMIZACAO-THROUGHPUT.md](OTIMIZACAO-THROUGHPUT.md)).
