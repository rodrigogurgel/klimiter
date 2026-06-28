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

Caminho do arquivo de políticas (formato em [`POLITICAS.md`](POLITICAS.md)); relativo ao diretório
de trabalho ou absoluto.

```yaml
klimiter:
  policies:
    path: config/policies/policies.yaml
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

**Referência completa:** [SonarScanner for Gradle — Analysis parameters](https://docs.sonarsource.com/sonarqube-cloud/advanced-setup/analysis-parameters/).

---

## 7. Dependências e plugins sem variáveis de ambiente próprias

Analisados e **sem** variáveis de ambiente próprias (são configurados via Gradle/código, ou sua
config cai em `spring.*`/`management.*` já coberto acima):

- **Codegen (build):** plugin `com.google.protobuf`, `grpc-kotlin-stub`, `protobuf-kotlin`.
- **Runtime Kotlin/Reactor:** `kotlinx-coroutines-core`/`-reactor`, `reactor-kotlin-extensions`,
  `kotlin-reflect`.
- **Serialização:** `jackson-module-kotlin` — configurado por `spring.jackson.*` (ver §1).
- **Qualidade/docs:** plugins `dev.detekt`, `org.jetbrains.dokka`, `jacoco`,
  `com.github.ben-manes.versions`, `io.spring.dependency-management`; libs de teste `konsist`,
  `mockk`. Configuração no `build.gradle.kts` / `config/`, não por ambiente.
