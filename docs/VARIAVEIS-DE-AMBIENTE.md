# Variáveis de ambiente — klimiter

O conjunto **completo** de variáveis que cada dependência/ferramenta reconhece vive na
**documentação oficial da versão em uso** (linkada em cada seção). Este documento **não duplica**
esse catálogo — ele registra apenas:

- o que está **definido ou sobrescrito no projeto** (com o valor do projeto);
- a **referência oficial** de cada fonte, para o resto das chaves e seus valores.

> **Regra:** se uma variável já está na doc oficial linkada, basta a referência — não a copie
> aqui. Mantenha este arquivo enxuto e fiel (ver regra no [`AGENTS.md`](../AGENTS.md)).

> **Onde este documento se encaixa.**
>
> | Documento | Responde |
> |-----------|----------|
> | [`DESIGN-CONCEITUAL.md`](DESIGN-CONCEITUAL.md) | o quê — fluxos, invariantes |
> | [`ARQUITETURA.md`](ARQUITETURA.md) | como o código é estruturado |
> | [`POLITICAS.md`](POLITICAS.md) | formato do arquivo de políticas (limites por dimensão) |
> | [`OBSERVABILIDADE.md`](OBSERVABILIDADE.md) | o que o serviço expõe para observação |
> | **`VARIAVEIS-DE-AMBIENTE.md`** (este) | configuração via ambiente |

---

## Mecanismos

Uma variável de ambiente chega à configuração por um de três caminhos:

- **(R) Relaxed binding do Spring** — qualquer propriedade `spring.*`/`management.*` vira env:
  maiúsculas, `.`/`-` → `_`. Ex.: `spring.application.name` ⇄ `SPRING_APPLICATION_NAME`.
- **(N) Nativa** — lida diretamente pela biblioteca/runtime (JVM, ferramentas de build).
- **(O) Mapeamento OTel** — o Boot 4.1 mapeia as `OTEL_*` padrão do SDK OpenTelemetry para
  propriedades `management.*`, controlado por `management.opentelemetry.map-environment-variables`.
  Assim as `OTEL_*` da [spec OpenTelemetry](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/)
  são reconhecidas sem configuração extra. (Isso substitui a limitação histórica do
  [spring-boot#48799](https://github.com/spring-projects/spring-boot/issues/48799).)

> As URLs do Spring Boot abaixo são canônicas e hoje resolvem para a **4.1** (a versão do projeto);
> o caminho versionado `/4.1/` redireciona para elas.

---

## 1. Aplicação — Spring Core

Provido por: Spring Boot (núcleo). Definido no projeto:

| Variável | Caminho | Propriedade | Valor no projeto |
|----------|---------|-------------|------------------|
| `SPRING_APPLICATION_NAME` | R | `spring.application.name` | `klimiter` (`application.yaml`) |

```yaml
spring:
  application:
    name: klimiter
```

**Referência completa:** [Spring Boot — Common Application Properties (Core)](https://docs.spring.io/spring-boot/appendix/application-properties/index.html#appendix.application-properties.core)
— inclui `spring.profiles.active`, `spring.jackson.*` (serialização JSON) e demais `spring.*`.

### 1.1 Configuração própria do klimiter (`klimiter.*`)

Propriedades definidas pelo projeto (binding relaxado do Spring, caminho **R** — `@ConfigurationProperties`).

| Variável | Caminho | Propriedade | Valor no projeto |
|----------|---------|-------------|------------------|
| `KLIMITER_POLICIES_PATH` | R | `klimiter.policies.path` | `config/policies/policies.yaml` (`application.yaml`) |
| `KLIMITER_POLICIES_RELOAD_DEBOUNCE` | R | `klimiter.policies.reload-debounce` | `200ms` (`application.yaml`) |
| `KLIMITER_REDIS_URI` | R | `klimiter.redis.uri` | `redis://localhost:6379` (`application.yaml`) |
| `KLIMITER_REDIS_POOL_SIZE` | R | `klimiter.redis.pool-size` | `8` (`application.yaml`) |
| `KLIMITER_REDIS_KEY_PREFIX` | R | `klimiter.redis.key-prefix` | `klimiter` (`application.yaml`) |
| `KLIMITER_REDIS_CLUSTER` | R | `klimiter.redis.cluster` | `false` (`application.yaml`) |
| `KLIMITER_EVICTION_INTERVAL` | R | `klimiter.eviction.interval` | `60s` (`application.yaml`) |
| `KLIMITER_OBSERVABILITY_GRPC_SAMPLE_RATE` | R | `klimiter.observability.grpc-sample-rate` | `1.0` (`application.yaml`) |

`path`: caminho do arquivo de políticas (formato em [`POLITICAS.md`](POLITICAS.md)); relativo ao
diretório de trabalho ou absoluto. `reload-debounce`: janela de silêncio do hot reload — eventos do
filesystem em rajada são coalescidos num único reload (aceita formato de duração do Spring, ex.:
`200ms`, `1s`).

`redis`: conexão com o contador global por janela (§4). `uri` no formato `redis://host:porta` (ou
`rediss://` para TLS); `pool-size` é o nº de conexões multiplexadas sempre abertas (round-robin,
PA-2); `key-prefix` prefixa as chaves `prefixo:dimensão:valor:início` (§3.2). `cluster`: usa o client
de **Redis Cluster** (ElastiCache cluster mode enabled) com descoberta de topologia e roteamento por
slot — `uri` vira o endpoint-semente. Como os scripts Lua são single-key (`KEYS[1]`), não há
CROSSSLOT. `false` = standalone (inclui ElastiCache cluster mode disabled, via o primary endpoint).
Localmente: `make up-cluster` (profile `redis-cluster` do compose). `eviction.interval`: período da varredura do
índice local de buckets (§4.2; aceita formato de duração do Spring). `observability.grpc-sample-rate`:
fração das observações por-RPC do gRPC a registrar (1.0 = todas; 0.0 = desliga) — knob de custo, ver
[`OBSERVABILIDADE.md`](OBSERVABILIDADE.md) §2 e [`SATURACAO.md`](SATURACAO.md) §5.2.

```yaml
klimiter:
  policies:
    path: config/policies/policies.yaml
    reload-debounce: 200ms
  redis:
    uri: redis://localhost:6379
    pool-size: 8
    key-prefix: klimiter
    cluster: false
  eviction:
    interval: 60s
  observability:
    grpc-sample-rate: 1.0
```

---

## 2. Spring Boot Actuator — `spring-boot-starter-actuator`

Configuração via `management.*` (R): exposição de endpoints, health, porta de management, info.
Nada definido no projeto.

**Referência completa:** [Spring Boot — Application Properties (Actuator)](https://docs.spring.io/spring-boot/appendix/application-properties/index.html#appendix.application-properties.actuator).

---

## 3. Observabilidade — `spring-boot-starter-opentelemetry`

Starter oficial do Boot 4 (Micrometer + `management.*`, R); as `OTEL_*` são reconhecidas via
mapeamento (caminho **O**, ver Mecanismos). Cobre export OTLP de métricas, traces e logs e a
amostragem. Nada definido no projeto.

**Referências completas:**
[`management.otlp.metrics.export.*` e `management.opentelemetry.*`](https://docs.spring.io/spring-boot/appendix/application-properties/index.html#appendix.application-properties.actuator)
· [Observability/Tracing](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
· [Spec de variáveis `OTEL_*`](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/).

---

## 4. Spring gRPC Server — `spring-boot-starter-grpc-server` (Spring gRPC 1.1.0)

Configuração via `spring.grpc.server.*` (R): porta, host, tamanho de mensagem, keep-alive, SSL,
health, reflection, observação, exception-handling, etc. Nada definido no projeto.

**Referência completa:** [Spring gRPC — Configuration Properties (appendix)](https://docs.spring.io/spring-grpc/reference/appendix.html)
· [Server](https://docs.spring.io/spring-grpc/reference/server.html).

---

## 5. JVM

Opções de JVM e seleção de JDK por variáveis **nativas** (sem equivalente em `application.yaml`).
Nada definido no projeto; a toolchain de compilação é JDK 21 (no `build.gradle.kts`, não via
ambiente).

**Referência completa:** [The `java` Command — JDK 21](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)
(seção *Using the JDK_JAVA_OPTIONS / JAVA_TOOL_OPTIONS Environment Variables*).

---

## 6. Build / Ferramentas (não-runtime)

Não afetam a aplicação em execução nem têm equivalente em `application.yaml`.

### 6.1 Gradle — wrapper 9.5.1

Variáveis de ambiente do build (JVM do Gradle, home, propriedades de projeto). Nada definido no
projeto.

**Referência completa:** [Gradle 9.5.1 — Build Environment](https://docs.gradle.org/9.5.1/userguide/build_environment.html).

### 6.2 Sonar — plugin `org.sonarqube` 7.2.2

Usado no projeto:

| Variável | Valor no projeto | Descrição |
|----------|------------------|-----------|
| `SONAR_TOKEN` | sem default; usado em `-Dsonar.token=$SONAR_TOKEN` | Token de autenticação (obrigatório p/ `./gradlew sonar`). |
| `SONAR_HOST_URL` | fallback `https://sonarcloud.io` (`build.gradle.kts`) | URL do servidor Sonar. |

**Rodar contra um SonarQube local** (perfil `sonar` do `docker-compose`, opt-in — não sobe na stack padrão).

**Atalho (um comando):** sobe o Sonar, **semeia a senha do admin**, gera o token e roda a análise:

```bash
make sonar-local                                   # tudo automático; usa SONAR_PASSWORD (>=12, com
                                                   # maiúscula+minúscula+dígito+especial)
make sonar-reset                                   # zera o Sonar (volumes) — após upgrade de major
```

**Manual**, se preferir:

```bash
docker compose --profile sonar up -d sonarqube     # http://localhost:9000
# defina a senha do admin (>=12, maiúscula+minúscula+dígito+especial), crie um token e rode:
SONAR_HOST_URL=http://localhost:9000 ./gradlew test detekt jacocoTestReport sonar -Dsonar.token=<token>
```

O serviço usa SonarQube Community com **H2 embutido** (apenas avaliação/dev — o Sonar avisa que H2 não é
para produção) e `SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true` para subir sem ajustar `vm.max_map_count` no host.
Se o Elasticsearch interno não subir, aumente o `vm.max_map_count` (`sysctl -w vm.max_map_count=524288`).
A senha do admin **não nasce pronta** na Community (não há env de boot); o `make sonar-local` a semeia via
API no 1º boot (idempotente) — é o mesmo padrão do Helm chart oficial.

**Referência completa:** [SonarScanner for Gradle — Analysis parameters](https://docs.sonarsource.com/sonarqube-cloud/advanced-setup/analysis-parameters/).

### 6.3 Testcontainers — testes de integração (versão pelo BOM do Spring Boot)

Usado nos testes de integração do adapter Redis (`@Testcontainers(disabledWithoutDocker = true)`):
sem Docker os ITs são pulados. Definido no test runner (`build.gradle.kts`):

| Variável | Valor no projeto | Descrição |
|----------|------------------|-----------|
| `TESTCONTAINERS_RYUK_DISABLED` | `true` (override pelo ambiente) | Desliga o reaper Ryuk (dispensável em CI/dev). |
| `DOCKER_HOST` | repassado do host quando setado | Socket do Docker (ex.: Docker Desktop fora do padrão). |
| `api.version` (system property) | `1.43` (override por `-Dapi.version` ou `DOCKER_API_VERSION`) | Versão da API do docker-java (o default 1.32 é antigo demais para daemons modernos). |

**Referência completa:** [Testcontainers — Configuration](https://java.testcontainers.org/features/configuration/).

---

## 7. Dependências e plugins sem variáveis de ambiente próprias

Analisados e **sem** variáveis de ambiente próprias (são configurados via Gradle/código, ou sua
config cai em `spring.*`/`management.*` já coberto acima):

- **Codegen (build):** plugin `com.google.protobuf`, `grpc-kotlin-stub`, `protobuf-kotlin`.
- **Runtime Kotlin/Reactor:** `kotlinx-coroutines-core`/`-reactor`, `reactor-kotlin-extensions`,
  `kotlin-reflect`.
- **Redis (runtime):** `lettuce-core` — conexão configurada via URI/código (ver wiring do
  contador global), sem variáveis de ambiente próprias.
- **Serialização:** `jackson-module-kotlin` — configurado por `spring.jackson.*` (ver §1).
- **Qualidade/docs:** plugins `dev.detekt`, `org.jetbrains.dokka`, `jacoco`,
  `com.github.ben-manes.versions`, `io.spring.dependency-management`; libs de teste `konsist`,
  `mockk`. Configuração no `build.gradle.kts` / `config/`, não por ambiente.
