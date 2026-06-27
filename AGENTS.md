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

## Estrutura do repositório

```
build.gradle.kts          # build + plugins de qualidade (jacoco, detekt, sonar, dokka)
settings.gradle.kts
cliff.toml                # config do git-cliff (geração do CHANGELOG)
config/detekt/detekt.yml  # regras do detekt (sobre o default)
docs/DESIGN-CONCEITUAL.md # especificação da lógica (LER PRIMEIRO)
src/main/kotlin/...        # código de produção
src/test/kotlin/...        # testes
CONTRIBUTING.md           # fluxo de contribuição completo
AGENTS.md                 # este arquivo
```

## Documentos relacionados

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — fluxo completo, git flow, checklist de PR.
- [`docs/DESIGN-CONCEITUAL.md`](docs/DESIGN-CONCEITUAL.md) — a lógica do rate limiter.
