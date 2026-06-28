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

### 1.2 Do klimiter (a preencher)

Métricas de negócio do caminho quente (lease, pacing, short-circuit de lote, etc.).

| Nome | Tipo | Unidade | Tags / labels | Descrição |
|------|------|---------|---------------|-----------|
| _(a preencher)_ | counter \| gauge \| histogram \| timer | | | |

### 1.3 Convenções

- **Nomenclatura:** _(a preencher)_
- **Tags obrigatórias:** _(a preencher)_
- **Cardinalidade:** _(a preencher — quais labels são seguros; o que nunca vira tag)_

---

## 2. Traces

### 2.1 Spans providos por dependências (automáticos)

| Span | Origem | Escopo |
|------|--------|--------|
| `grpc.server` | Spring gRPC (auto, com o actuator) | uma chamada gRPC do servidor, ponta a ponta no handler |

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
| `ERROR` | _(a preencher)_ | |
| `WARN`  | _(a preencher)_ | |
| `INFO`  | _(a preencher)_ | |
| `DEBUG` | _(a preencher)_ | |
| `TRACE` | _(a preencher)_ | |

### 3.3 Eventos de log do klimiter (a preencher)

| Evento | Nível | Quando ocorre | Campos estruturados |
|--------|-------|---------------|---------------------|
| _(a preencher)_ | | | |

### 3.4 Convenções

- **Formato:** _(a preencher — estruturado/JSON, campos padrão)_
- **Dados sensíveis:** _(a preencher — o que nunca pode ser logado)_
