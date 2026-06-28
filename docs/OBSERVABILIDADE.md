# Observabilidade — klimiter

Catálogo do que o klimiter **expõe** para observação: métricas, traces (com o escopo de cada
span) e logs (com o significado de cada nível).

Os sinais **automáticos** (providos por dependências) já estão catalogados na **documentação
oficial da versão** — aqui ficam apenas as **referências** e os destaques; este arquivo **não
duplica** essas listas. As seções **do klimiter** descrevem a instrumentação própria e serão
preenchidas conforme adicionada (`_(a preencher)_` = placeholder).

> **Regra:** sinal automático que já está na doc oficial linkada não é copiado aqui — basta a
> referência (ver regra no [`AGENTS.md`](../AGENTS.md)).

> **Onde este documento se encaixa.**
>
> | Documento | Responde |
> |-----------|----------|
> | [`DESIGN-CONCEITUAL.md`](DESIGN-CONCEITUAL.md) | o quê — fluxos, invariantes |
> | [`ARQUITETURA.md`](ARQUITETURA.md) | como o código é estruturado |
> | **`OBSERVABILIDADE.md`** (este) | o que o serviço expõe para observação |
> | [`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md) | endpoints/sampling via `management.*` |
>
> Referências `§N` apontam para seções do `DESIGN-CONCEITUAL.md`.

---

## 1. Métricas

### 1.1 Providas por dependências (automáticas)

Registradas pelo **Actuator + Micrometer** e exportadas via **OTLP** pelo
`spring-boot-starter-opentelemetry` quando o endpoint estiver configurado (ver
[`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md) §3). Grupos: JVM (`jvm.*`),
sistema/processo (`system.*`, `process.*`), disco (`disk.*`), startup
(`application.started.time`/`ready.time`), logs (`logback.events`), executores (`executor.*`) e
**gRPC do servidor** (`grpc.server`, via Spring gRPC quando a observação do servidor está habilitada).

**Referências (lista completa + nomes/tags):**
[Spring Boot — Supported Metrics and Meters](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
· [Micrometer — gRPC Instrumentation](https://docs.micrometer.io/micrometer/reference/reference/grpc.html)
· [Spring gRPC — Observability](https://docs.spring.io/spring-grpc/reference/server.html).

### 1.2 Do klimiter

Instrumentação própria, registrada no `MeterRegistry` (Micrometer) e exportada via OTLP.

| Nome | Tipo | Tags | Descrição |
|------|------|------|-----------|
| `klimiter.reserve` | counter | `priority` = `high`\|`low`, `status` = `allowed`\|`denied`\|`unknown` | Reservas individuais (§5/§6). Registrado pelo `LocalBudget` via a **porta `RateLimitMetrics`** (núcleo desacoplado do framework). |
| `klimiter.reserve.high` | counter | `path` = `l1`\|`l2`\|`l3`\|`l4` | Nível do caminho de reserva ALTA (§5): L1 crédito local, L2 esgotado, L3 impossível, L4 renovação (round-trip). Distribuição-chave para tunar o prefetch. |
| `klimiter.batch.shortcircuit` | counter | — | Lotes natimortos pelo short-circuit (§7.2): a grande economia no regime de chave quente. |
| `klimiter.batch.refund` | counter | — | Lotes refundados (§7.4): veredito coletivo não-PERMITIDO desfez as reservas. |
| `klimiter.central.roundtrip` | timer | `op` = `lease`\|`pace_lease` | Latência de cada round-trip ao contador global em Redis (§4). Medido no boundary (`MeteredGlobalCounter`). |
| `klimiter.bucket.index.size` | gauge | — | Tamanho do índice local de buckets por nó (§4.2); cresce com a cardinalidade e cai na evicção. |

**Derivações** (sem métrica própria, de propósito): o **shed do pré-portão** da BAIXA =
`klimiter.reserve{priority=low}` − `klimiter.central.roundtrip{op=pace_*}` (a distribuição da ALTA já
é explícita em `klimiter.reserve.high`). A recarga NOSCRIPT e a degradação de backend são observáveis
por **log** (§3.3).

### 1.3 Convenções

- **Nomenclatura:** prefixo `klimiter.`, em `lower.dot.case`, no padrão `klimiter.<área>.<medida>`
  (ex.: `klimiter.central.roundtrip`, `klimiter.bucket.index.size`).
- **Tags obrigatórias:** nenhuma além das automáticas; tags próprias só de **enums limitados**
  (`op`, e futuramente `status`/`priority`/`path`).
- **Cardinalidade:** **nunca** usar `dimension`/`value`/chave como tag (alta cardinalidade e PII).
  Só rótulos de domínio fechado.

---

## 2. Traces

### 2.1 Spans providos por dependências (automáticos)

| Span | Origem | Escopo |
|------|--------|--------|
| `grpc.server` | Spring gRPC (auto, com o actuator) | uma chamada gRPC do servidor, ponta a ponta no handler |

**Amostragem por-RPC (knob de custo):** a observação `grpc.server` (span **e** timer) é criada por
chamada — o maior overhead da telemetria no hot path. Pode ser amostrada por
`klimiter.observability.grpc-sample-rate` (1.0 = todas; 0.0 = desliga); as não-amostradas viram NOOP.
Ver [`SATURACAO.md`](SATURACAO.md) §5.2. Não afeta as métricas `klimiter.*` (§1.2) nem o export.

**Propagação/amostragem:** contexto W3C Trace Context; amostragem configurável por
`management.opentelemetry.tracing.sampler` (ver [`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md) §3
e a referência de tracing abaixo). Exportação OTLP quando o endpoint de traces estiver configurado.

**Referências:** [Spring Boot — Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
· [Micrometer — gRPC Instrumentation](https://docs.micrometer.io/micrometer/reference/reference/grpc.html).

### 2.2 Spans do klimiter (a preencher)

| Span | Escopo (o que engloba) | Span pai | Atributos |
|------|------------------------|----------|-----------|
| _(a preencher)_ | | | |

---

## 3. Logs

### 3.1 Providos por dependências (automáticos)

- **Framework:** **Logback** — default do Spring Boot (via `spring-boot-starter-logging`,
  transitivo). Formato e padrão de console do Spring Boot.
- **Correlação com traces:** o Micrometer Tracing injeta `traceId`/`spanId` no MDC; o padrão de
  log inclui `[<app>,<traceId>,<spanId>]` quando o tracing está ativo.
- **Exportação OTLP de logs:** suportada pelo starter (Boot 4.1) via
  `management.opentelemetry.logging.export.otlp.*` — desligada até o endpoint ser configurado
  (ver [`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md) §3).

**Referências:** [Spring Boot — Logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
· [Tracing (correlação de logs)](https://docs.spring.io/spring-boot/reference/actuator/tracing.html).

### 3.2 Níveis (a preencher)

O que cada nível representa **neste serviço** e quando deve ser usado.

| Nível | Representa | Quando emitir |
|-------|------------|---------------|
| `ERROR` | Falha que impede a operação correta | config/políticas inválidas no carregamento |
| `WARN`  | Degradação ou condição recuperável | arquivo de políticas ausente; falha de backend → item `UNKNOWN` (§7.4) |
| `INFO`  | Marcos operacionais | boot, políticas carregadas/recarregadas (§8) |
| `DEBUG` | Detalhe de diagnóstico do hot path | evicção de buckets; recarga NOSCRIPT |
| `TRACE` | Detalhe fino (não usar em produção) | — |

### 3.3 Eventos de log do klimiter

| Evento | Nível | Quando ocorre | Campos estruturados |
|--------|-------|---------------|---------------------|
| degradação por falha de backend | `WARN` | o contador global falha ao reservar (§7.4) → item vira `UNKNOWN` | `priority`, causa (exceção) |
| recarga NOSCRIPT | `DEBUG` | script Lua ausente no cache do Redis → recarrega via `EVAL` | `sha1`, causa |
| evicção de buckets | `DEBUG` | varredura remove buckets de janelas vencidas (§4.2) | `removed`, `remaining` |

### 3.4 Convenções

- **Formato:** **API fluente do SLF4J** — `log.atInfo()/atDebug()/atWarn()/atError()` com **mensagem
  estática** (`setMessage(...)`) e os dados variáveis em pares chave-valor (`addKeyValue("chave",
  valor)`). **Não** interpolar dados na mensagem. Mensagens estáveis agrupam/filtram melhor e os
  pares viram **atributos estruturados** na exportação OTLP. Ex.:
  `log.atDebug().addKeyValue("removed", n).addKeyValue("remaining", size).setMessage("evicção de buckets").log()`.
- **Dados sensíveis:** nunca logar `dimension`/`value`/chave (PII e alta cardinalidade) em nível
  ≥ INFO; atributos/tags só de enums limitados (`status`, `priority`, `op`). Ver §1.3.
