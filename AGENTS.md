# AGENTS.md — guia para agentes e novos integrantes

Contexto operacional do **klimiter** para agentes de IA (Claude Code, etc.) e para quem está
entrando no time. Mantenha este arquivo curto, factual e atualizado: ele é a primeira coisa
que um agente lê antes de agir.

## O que é o klimiter

Um **rate limiter distribuído por janela fixa (fixed window)**, exposto via **gRPC**, escrito
em **Kotlin** sobre **Spring Boot 4 / Spring gRPC**. Vários nós compartilham um contador por
janela num armazenamento central (ex.: Redis) mas resolvem a maioria das requisições
localmente via *lease* (arrendamento de blocos) + *pacing* (linha de liberação para tráfego
de baixa prioridade).

> **Fonte da verdade da lógica:** [`docs/DESIGN-CONCEITUAL.md`](docs/DESIGN-CONCEITUAL.md).
> Leia-o antes de implementar ou alterar qualquer comportamento. Ele descreve *o que* fazer
> (invariantes, fluxos, premissas), não nomes de classes. Se o código divergir do design,
> trate como bug — e se o design mudar, atualize o documento no mesmo PR.

## Stack

| Item            | Valor                                              |
|-----------------|----------------------------------------------------|
| Linguagem       | Kotlin 2.3.21 (JVM, toolchain JDK 21)              |
| Framework       | Spring Boot 4.1 + Spring gRPC                       |
| Build           | Gradle 9.5.1 (Kotlin DSL, via `./gradlew`)         |
| RPC             | gRPC + Protobuf (plugin `com.google.protobuf`)     |
| Pacote raiz     | `io.github.rodrigogurgel.klimiter`                 |

## Comandos essenciais

```bash
./gradlew build            # compila + testa + verificações
./gradlew test             # testes (gera relatório JaCoCo como finalizer)
./gradlew detekt           # lint estático Kotlin (config/detekt/detekt.yml)
./gradlew check            # test + detekt + verificações agregadas
./gradlew jacocoTestReport # cobertura → build/reports/jacoco/test/jacocoTestReport.xml
./gradlew dokkaGenerate    # docs de API → build/dokka/
./gradlew bootRun          # sobe o serviço localmente
./gradlew sonar -Dsonar.token=$SONAR_TOKEN   # análise Sonar (após test+detekt+jacoco)
git cliff -o CHANGELOG.md  # regenera o CHANGELOG a partir dos commits
```

Sempre use o wrapper (`./gradlew`), nunca um Gradle global.

## Convenções (resumo — detalhes no CONTRIBUTING)

- **Branches:** git flow. `main` (releasable) e `develop` (integração). Trabalhe em
  `feature/<slug>`, `hotfix/vX.Y.Z`, `release/vX.Y.Z`. Nunca commite direto em `main`/`develop`.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org) obrigatório —
  o CHANGELOG é gerado pelo git-cliff (`cliff.toml`). Ex.: `feat(lease): ...`, `fix(pacing): ...`.
  Breaking change → `tipo!:` e/ou rodapé `BREAKING CHANGE:`.
- **Changelog:** gerado, **não** editar `CHANGELOG.md` à mão.
- **Estilo:** detekt + detekt-formatting (ktlint), `.editorconfig`, linha máx. 120 colunas.
- **Docs de API:** KDoc no código público (Dokka consome).

## Regras para o agente

1. **Leia o design antes de codar.** Mudanças de comportamento precisam casar com
   `docs/DESIGN-CONCEITUAL.md` e suas invariantes (contador só cresce, rollback local, piso
   inteiro ≥ piso flutuante, etc.).
2. **Não edite artefatos gerados:** `CHANGELOG.md` (git-cliff) e código gerado de protobuf
   em `build/generated/`.
3. **Valide localmente antes de concluir:** rode `./gradlew check` e garanta verde.
4. **Respeite o git flow:** crie a branch certa; não faça commit/push sem o usuário pedir.
5. **Commits no padrão Conventional Commits**, no imperativo, escopo quando fizer sentido.
6. **Não baixe a régua de qualidade** silenciosamente (não adicione `@Suppress`, não desative
   regras do detekt, nem abaixe a meta de cobertura sem justificativa explícita no PR).
7. **Antes de adicionar/atualizar uma dependência ou plugin:**
   - **Cheque a versão estável mais recente** na fonte oficial (Maven Central / Gradle Plugin
     Portal) — não chute nem copie versões de memória.
   - **Confirme a compatibilidade** com o stack atual: Kotlin 2.3.21, JDK 21, Gradle
     (wrapper), Spring Boot 4.1. Este projeto usa versões de ponta e conflitos de
     compatibilidade são comuns — ex.: detekt × versão do Kotlin embutido, Dokka × Jackson
     forçado pelo BOM do Spring. Rode `./gradlew help` e `./gradlew check` após a mudança.
   - **Onde declarar:** plugins e dependências **com versão própria** vão no
     `gradle/libs.versions.toml` (versão em `[versions]`, lib em `[libraries]` → `libs.*`,
     plugin em `[plugins]` → `libs.plugins.*`). A regra firme é: **nunca fixe um número de
     versão inline** no `build.gradle.kts`.
   - **Deps gerenciadas por um BOM** (ex.: os `spring-boot-starter-*`, cuja versão vem do BOM
     do Spring Boot) **não têm versão própria** — declare como coordenada direta **sem versão**
     no `build.gradle.kts` (ou como entrada sem `version` no catálogo, se quiser o acessor
     tipado `libs.*`). A versão é responsabilidade do BOM; não a duplique nem a fixe.
   - **Step de pré-commit (obrigatório ao mexer em dependências/plugins):** rode
     `./gradlew dependencyUpdates` e confirme que as entradas novas/alteradas estão na **última
     versão dentro de um major compatível**. Se houver um major mais novo, **não** salte cego —
     avalie a compatibilidade (regra acima) e, se não for adotar, registre o porquê no PR.
8. **Mantenha o catálogo de variáveis de ambiente fiel — referência-primeiro.** Atualize
   [`docs/VARIAVEIS-DE-AMBIENTE.md`](docs/VARIAVEIS-DE-AMBIENTE.md), no mesmo PR, sempre que:
   - **criar/alterar uma variável ou propriedade** de configuração (app, JVM ou build) — registre
     a entrada com caminho, propriedade equivalente, default e se está definida no projeto;
   - **adicionar uma dependência/plugin** — adicione uma seção curta com a **referência oficial
     da versão** das variáveis que ela reconhece; **não liste as variáveis upstream uma a uma**
     (nem seus defaults). Se a dependência não tiver variáveis próprias, registre-a na §7;
   - **remover uma dependência/plugin** — remova a seção e a referência correspondentes.
   **Liste individualmente apenas o que o projeto define/sobrescreve** (com o valor do projeto);
   todo o resto fica na referência. Confirme nomes e versões na doc oficial, não de memória.
9. **Mantenha o catálogo de observabilidade fiel — referência-primeiro.** Atualize
   [`docs/OBSERVABILIDADE.md`](docs/OBSERVABILIDADE.md), no mesmo PR, sempre que:
   - **adicionar/alterar/remover instrumentação própria** (métrica, span ou evento de log do
     klimiter) — registre nome, tipo/escopo, tags/atributos e significado na subseção "do klimiter";
   - **adicionar/remover uma dependência** — ajuste os **destaques** dos sinais automáticos e a
     **referência oficial** da subseção "providas por dependências" (não copie a lista de
     métricas/spans/logs que já está na doc oficial; confirme na doc da versão, não de memória).
   Mantenha separado o que é **provido por dependências** (referenciado) do que é **do klimiter**.

## Estrutura do repositório

```
build.gradle.kts          # build + plugins de qualidade (jacoco, detekt, sonar, dokka)
settings.gradle.kts
gradle/libs.versions.toml # version catalog: plugins + deps com versão própria (BOM fica sem versão)
cliff.toml                # config do git-cliff (geração do CHANGELOG)
config/detekt/detekt.yml  # regras do detekt (sobre o default)
docs/DESIGN-CONCEITUAL.md # especificação da lógica (LER PRIMEIRO)
docs/ARQUITETURA.md       # estrutura do código + regras de fronteira (hexagonal)
docs/POLITICAS.md         # formato do config/policies/policies.yaml + JSON Schema
docs/OBSERVABILIDADE.md   # contrato de telemetria: métricas, traces/spans, logs
docs/VARIAVEIS-DE-AMBIENTE.md # variáveis de ambiente: aplicação, deps, JVM, build
config/policies/          # policies.yaml (catálogo de limites) + policies.schema.json
src/main/kotlin/...        # código de produção
src/test/kotlin/...        # testes unitários/integração (./gradlew test)
scripts/load-test/         # testes de carga (ghz/k6) — fora do gradle; ver scripts/load-test/README.md
CONTRIBUTING.md           # fluxo de contribuição completo
AGENTS.md                 # este arquivo
```

## Documentos relacionados

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — fluxo completo, git flow, checklist de PR.
- [`docs/DESIGN-CONCEITUAL.md`](docs/DESIGN-CONCEITUAL.md) — a lógica do rate limiter (*o quê*).
- [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) — estrutura do código e regras de fronteira (*o como*).
- [`docs/POLITICAS.md`](docs/POLITICAS.md) — formato do arquivo de políticas, JSON Schema e resolução.
- [`docs/OBSERVABILIDADE.md`](docs/OBSERVABILIDADE.md) — métricas, traces/spans e logs expostos.
- [`docs/VARIAVEIS-DE-AMBIENTE.md`](docs/VARIAVEIS-DE-AMBIENTE.md) — variáveis de ambiente (app, deps, JVM, build).
- [`docs/SATURACAO.md`](docs/SATURACAO.md) — como medir o joelho (saturação): metodologia, `make sat-*`, leitura da saída.
- [`docs/OTIMIZACAO-THROUGHPUT.md`](docs/OTIMIZACAO-THROUGHPUT.md) — rodada de otimização do joelho: ganhos, escala por cores e a conta de dimensionamento (CPU/pod).
