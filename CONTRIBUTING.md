# Como contribuir com o klimiter

Bem-vindo! O **klimiter** é um *rate limiter* distribuído por janela fixa, exposto via
**gRPC** e escrito em **Kotlin**. Antes de mexer no código, leia o
[Design Conceitual](docs/DESIGN-CONCEITUAL-V2.md) — ele descreve a lógica (incremento condicional, pacing,
batch all-or-nothing, hot reload) de forma independente de implementação e é a fonte da
verdade sobre *o que* o serviço deve fazer. Para *como* o código é estruturado (camadas
hexagonais, layout de pacotes e regras de fronteira), veja a
[Arquitetura](docs/ARQUITETURA.md). O que o serviço expõe para observação (métricas,
traces e logs) está em [Observabilidade](docs/OBSERVABILIDADE.md). Toda a configuração via
ambiente está centralizada em [Variáveis de ambiente](docs/VARIAVEIS-DE-AMBIENTE.md).

Este guia explica **como interagir com o projeto**: ambiente, fluxo de branches, padrão de
commits, ferramentas de qualidade e o checklist antes de abrir um PR.

> Agentes de IA: leiam também o [AGENTS.md](AGENTS.md), que resume comandos e convenções
> em formato otimizado para automação.

---

## 1. Pré-requisitos

| Ferramenta   | Versão            | Observação                                            |
|--------------|-------------------|-------------------------------------------------------|
| JDK          | 21 (LTS)          | O Gradle usa toolchain 21; recomendado via SDKMAN.    |
| Gradle       | 9.6.0 (wrapper)   | Use sempre `./gradlew`, nunca um Gradle global.       |
| git          | 2.36+             | Necessário para git flow e git-cliff.                 |
| git-cliff    | 2.x               | Geração do CHANGELOG (`cargo install git-cliff`).     |
| git-flow     | AVH (opcional)    | Atalhos do fluxo; o modelo funciona com git puro.     |

Kotlin, protobuf e os plugins de qualidade são resolvidos pelo próprio Gradle — não
precisam ser instalados à parte.

---

## 2. Fluxo de trabalho — git flow

O projeto adota o **git flow**. Branches de longa duração:

- **`main`** — sempre estável e *releasable*. Cada commit aqui corresponde a uma release
  tagueada (`vX.Y.Z`).
- **`develop`** — integração contínua do trabalho em andamento; base das features.

Branches de suporte (sempre derivadas e mescladas conforme o modelo):

| Tipo        | Cria a partir de | Mescla em            | Convenção de nome      |
|-------------|------------------|----------------------|------------------------|
| Feature     | `develop`        | `develop`            | `feature/<slug>`       |
| Release     | `develop`        | `main` **e** `develop` | `release/vX.Y.Z`     |
| Hotfix      | `main`           | `main` **e** `develop` | `hotfix/vX.Y.Z`      |

### Com o utilitário git-flow (AVH)

```bash
git flow init -d                 # aceita os defaults (main/develop, prefixos acima)
git flow feature start minha-feature
# ... commits ...
git flow feature finish minha-feature
```

### Com git puro (sem o utilitário)

```bash
git switch develop
git switch -c feature/minha-feature
# ... commits ...
git switch develop && git merge --no-ff feature/minha-feature
```

> Não commite direto em `main` nem em `develop`: todo trabalho passa por uma branch de
> feature/hotfix e por Pull Request.

---

## 3. Padrão de commits — Conventional Commits

O CHANGELOG é gerado automaticamente pelo **git-cliff** a partir das mensagens de commit,
então o padrão [Conventional Commits](https://www.conventionalcommits.org/pt-br/) é
**obrigatório**:

```
<tipo>(<escopo opcional>): <descrição no imperativo>

[corpo opcional]

[rodapé opcional]
```

Tipos usados (mapeados para seções do CHANGELOG em `cliff.toml`):

| Tipo       | Vai para a seção  | Quando usar                                   |
|------------|-------------------|-----------------------------------------------|
| `feat`     | Adicionado        | Nova funcionalidade.                          |
| `fix`      | Corrigido         | Correção de bug.                              |
| `perf`     | Performance       | Melhoria de desempenho.                       |
| `refactor` | Refatorado        | Mudança interna sem alterar comportamento.    |
| `docs`     | Documentação      | Só documentação.                              |
| `test`     | Testes            | Adição/ajuste de testes.                      |
| `build`/`ci` | Build & CI      | Build, dependências, pipelines.               |
| `chore`/`style` | Manutenção   | Tarefas diversas, formatação.                 |

**Breaking changes:** use `!` após o tipo/escopo (`feat!:` ou `feat(api)!:`) **e/ou** um
rodapé `BREAKING CHANGE: ...`. Isso reflete no CHANGELOG e no bump de versão (SemVer).

Exemplos:

```
feat(lease): arrenda blocos com prefetch amortizado
fix(pacing): usa piso inteiro para nunca admitir acima da linha
docs: explica a invariante "contador só cresce"
feat(api)!: renomeia campo `priority` para `tier` no proto
```

---

## 4. Qualidade — antes de abrir o PR

Rode tudo localmente e garanta verde:

```bash
./gradlew detekt              # lint estático (Kotlin) + formatação
./gradlew test               # testes (gera relatório JaCoCo automaticamente)
./gradlew jacocoTestReport    # cobertura → build/reports/jacoco/test/
./gradlew check               # agrega test + detekt + verificações
./gradlew dokkaGenerate       # documentação de API → build/dokka/
```

### Detekt
Configurado em [`config/detekt/detekt.yml`](config/detekt/detekt.yml) (em cima do default).
Quebra o build em qualquer violação não *baselined*. Para registrar a dívida existente em
vez de corrigir tudo de uma vez:

```bash
./gradlew detektBaseline      # gera config/detekt/baseline.xml
```

### JaCoCo (cobertura)
O relatório XML alimenta o Sonar. Código gerado (protobuf/gRPC) e o bootstrap
(`KlimiterApplication`) ficam fora da medição. A meta mínima começa em 0% — suba-a em
`jacocoTestCoverageVerification` conforme o projeto amadurece.

### SonarQube / SonarCloud
Análise estática + cobertura. Configurado no `build.gradle.kts` (bloco `sonar`). Rode
depois de testes e detekt para enviar os relatórios juntos:

```bash
./gradlew test detekt jacocoTestReport sonar \
  -Dsonar.token=$SONAR_TOKEN
# host: defina SONAR_HOST_URL (default: https://sonarcloud.io)
```
Ajuste `sonar.projectKey`, `sonar.organization` e `sonar.host.url` para o seu servidor.

### Dokka
Gera a documentação de API a partir dos KDocs. Documente o código público com KDoc.

### Dependências e versões
A regra firme: **nunca fixe um número de versão inline no `build.gradle.kts`** — versão só no
**version catalog** [`gradle/libs.versions.toml`](gradle/libs.versions.toml) ou herdada de um
BOM. Antes de adicionar ou atualizar uma dependência:

- **Cheque a versão estável mais recente** na fonte oficial (Maven Central / Gradle Plugin
  Portal); não copie versões de memória.
- **Confirme a compatibilidade** com o stack atual (Kotlin 2.3.21, JDK 21, Gradle do wrapper,
  Spring Boot 4.1). O projeto usa versões de ponta e conflitos são comuns — ex.: detekt × a
  versão do Kotlin que ele embute, Dokka × Jackson forçado pelo BOM do Spring. Rode
  `./gradlew help` e `./gradlew check` após a mudança.

**Onde declarar cada dependência:**

| Caso | Onde | Como |
|------|------|------|
| Plugin | catálogo `[plugins]` | `id`, usado via `libs.plugins.*` |
| Dep com **versão própria** | catálogo `[versions]` + `[libraries]` | `module` + `version.ref`, usado via `libs.*` |
| Dep **gerenciada por BOM** (ex.: `spring-boot-starter-*`) | direto no `build.gradle.kts`, **sem versão** | `implementation("grupo:artefato")` — o BOM define a versão |

- Trocar as tabelas do catálogo é o erro clássico: um plugin em `[libraries]` **não** resolve
  via `libs.plugins.*`.
- Para deps de BOM, declarar a coordenada **sem versão** é o esperado — não duplique a versão
  no catálogo nem a fixe inline. (Opcionalmente cabe uma entrada sem `version` no catálogo só
  para ganhar o acessor tipado `libs.*`.)

**Step de pré-commit — verificar atualizações.** Sempre que adicionar ou alterar uma
dependência/plugin, rode antes de commitar:

```bash
./gradlew dependencyUpdates    # relatório de versões mais novas (libs + plugins)
```

Confirme que as entradas novas/alteradas estão na **última versão dentro de um major
compatível**. A task só sugere releases estáveis (exceto quando a versão atual já é
pré-lançamento, como o detekt `2.0.0-alpha.x`). Se existir um major mais novo, **não salte
cego**: avalie a compatibilidade com o stack e, se optar por não subir, registre o motivo no PR.

---

## 5. Changelog

Não edite o `CHANGELOG.md` à mão — ele é gerado pelo git-cliff:

```bash
git cliff -o CHANGELOG.md                          # regenera o arquivo inteiro
git cliff --unreleased --tag v0.1.0 --prepend CHANGELOG.md   # prepara uma release
```

Ao cortar uma release (branch `release/vX.Y.Z`): atualize a versão, gere o changelog,
faça o commit `chore(release): vX.Y.Z`, mescle em `main`, e crie a tag `vX.Y.Z`.

---

## 6. Checklist do Pull Request

- [ ] Branch criada a partir de `develop` (ou `main`, se hotfix).
- [ ] Commits no padrão Conventional Commits.
- [ ] `./gradlew check` passa (testes + detekt).
- [ ] Se mexeu em dependências/plugins: `./gradlew dependencyUpdates` rodado e versões novas
      conferidas (última dentro de um major compatível).
- [ ] Cobertura não regrediu de forma relevante.
- [ ] Comportamento condizente com o [Design Conceitual](docs/DESIGN-CONCEITUAL-V2.md);
      se o design mudou, o documento foi atualizado no mesmo PR.
- [ ] Estrutura condizente com a [Arquitetura](docs/ARQUITETURA.md) (regras de fronteira
      hexagonais); se a estrutura mudou, o documento foi atualizado no mesmo PR.
- [ ] KDoc adicionado/atualizado para API pública.
- [ ] PR aberto contra `develop` (ou `main`, se hotfix).
