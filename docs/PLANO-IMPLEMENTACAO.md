# Plano de Implementação — migração do klimiter para o DESIGN-CONCEITUAL-V2

> **Status: M1–M5 executados** na branch `feature/design-v2-migration`; resta o **M6**
> (hardening de desempenho + validação do SLO com ElastiCache). Desvios registrados na
> execução: (a) o índice de buckets ficou em `ConcurrentHashMap` + `EvictionRunner` (padrão
> já existente e vigiado pelo Konsist) em vez de Caffeine — o core não ganha dependência
> externa; (b) a porta manteve o nome `GlobalCounter`, agora `fun interface` com a única
> operação `tryAcquire`; (c) o estado local chama-se `LocalState` (substitui `LocalBudget`).
>
> **Alvo:** o repositório existente `~/IdeaProjects/klimiter` (Spring Boot 4.1.0,
> Kotlin 2.3.21, Java 21, hexagonal `core/` + `adapter/` com Konsist enforçando a regra de
> dependência). O projeto implementava o design **anterior** (leases locais + prefetch +
> refund); este plano o migra para o `DESIGN-CONCEITUAL-V2.md` (decisão 100% central,
> incremento condicional, sem refund, reserva ordenada por pressão).
>
> Metas não-funcionais, em ordem: **throughput máximo**, **p99 ≤ 15 ms** (servidor, lote
> de ~3 itens), **CPU mínima por request**. O contrato de wire (`ratelimit.proto`) **não
> muda** — clientes não são afetados; o que muda é o padrão de decisão sob saturação.

---

## 0. Viabilidade

O orçamento de latência fecha com folga. O caminho crítico de um lote de 3 itens é
dominado pelos round-trips ao Redis — todo o resto é trabalho local de microssegundos:

| Etapa | Custo estimado (p99) |
|---|---|
| gRPC decode + resolução de política + inspeção local (§5.2/§7.1 do design) | ~0,1–0,3 ms |
| 3 × `EVALSHA` sequenciais (incremento condicional, §4), cross-AZ | ~3–6 ms |
| Montagem da resposta + encode | ~0,1 ms |
| Jitter (GC, scheduling, rede) | ~2–3 ms |
| **Total p99** | **~6–9 ms** ✅ (budget 15 ms) |

Throughput: 20k rps × 3 itens = 60k `EVALSHA`/s no pior caso; 3 shards de ElastiCache
operam com folga 4–5× (§12 do design). Sob saturação, a inspeção local nega a maior parte
dos lotes sem round-trip. O gargalo é o Redis, não o serviço.

**A migração REDUZ código e estado:** sai a máquina de lease/prefetch/refund
(`LocalBudget`, `ReserveHigh`, `Pacing`, 2 scripts Lua) e entra um único script + um
snapshot `AtomicLong` por bucket. O risco é de regressão comportamental, não de
complexidade nova — e a suíte existente (unit + Testcontainers + Konsist) cobre a rede de
segurança.

---

## 1. Stack: o que já está certo e o que ajustar

O repositório já acerta quase tudo que o plano original pedia:

| Já está no projeto | Status para o v2 |
|---|---|
| Boot 4.1.0 + `spring-boot-starter-grpc-server` + grpc-kotlin (stubs coroutine) | mantém |
| Kotlin 2.3.21, coroutines | mantém |
| Lettuce (API coroutines via bridge Reactor) | mantém; ver nota de CPU abaixo |
| Hexagonal `core/`+`adapter/` com Konsist (`ArchitectureTest`) | mantém — o v2 respeita as mesmas regras |
| Hot reload (`FilePolicyRepository` + watcher), portas `Clock`/`RateLimitMetrics` | mantém intacto |
| OpenTelemetry starter + Micrometer, detekt/jacoco/sonar, Testcontainers (standalone + cluster), Makefile/Docker/CI | mantém |

Ajustes pontuais:

- **Toolchain Java 21 → 25 (LTS)**: recomendado pelo ZGC geracional maduro (protege o
  p99). Não é bloqueante — em 21, `-XX:+UseZGC -XX:+ZGenerational` já atende; tratar como
  tarefa isolada de infra (M6).
- **Nota Lettuce/CPU:** a API de coroutines do Lettuce passa pela bridge do Reactor
  (alocação extra por chamada). Aceitável para começar — legibilidade primeiro; se o
  profiling do M6 apontar custo relevante, trocar **só o hot path** do adapter para a API
  async (`RedisFuture.await()`), mantendo a porta suspend idêntica.
- **Caffeine (novo, `core`):** entra para o índice de buckets de snapshot com `Expiry`
  por janela (§5.4 do design). O `EvictionRunner` atual (varredura própria) pode ser
  aposentado junto com o `LocalBudget` — uma responsabilidade a menos.
- **Property tests** para a aritmética da linha (equivalência nó ≥ central, overflow):
  jqwik ou Kotest-property como dep de teste — a suíte atual (`ReleaseLineTest` etc.) é
  exemplo-based; a garantia do §6.3 do design pede exploração de domínio.

---

## 2. Arquitetura: o hexágono existente absorve o v2

Nada muda nas regras de dependência (Konsist continua passando). O mapeamento de nomes do
design para o código existente:

- `core/domain/` + `core/policy/` — modelo e políticas (linguagem ubíqua: `Window`,
  `WindowKey`, `ReleaseLine` já existem e **ficam**).
- `core/application/` — orquestração do lote (reescrita no v2, ver §3).
- `core/port/outbound/` — portas no formato da operação. A porta central é **reescrita**
  (ver §3); `Clock`, `PolicyRepository`, `RateLimitMetrics` ficam.
- `adapter/` — gRPC, Redis, policy, metrics, clock: estrutura fica; só o adapter Redis
  muda de conteúdo.

As três regras que protegem desempenho e corretude (herdadas do plano original, o projeto
já as segue):

1. **Um modelo de domínio único, tradução só na borda** (o `Mapping.kt` do gRPC já faz
   isso; nenhuma camada interna ganha DTO próprio).
2. **Portas no formato da operação, não CRUD** — a nova porta expõe exatamente o contrato
   atômico do §4 do design.
3. **Estrutura de dados não é porta** — o índice de snapshots (Caffeine) vive dentro de
   `core/`, como o `LocalBudget` vivia; portas são só para fronteiras reais.

Legibilidade: manter o padrão já praticado no repo (KDoc com âncora de seção do design,
nomes do documento no código). Ao trocar o documento de referência para o V2, **atualizar
as âncoras `§` dos KDocs** dos arquivos tocados — âncora apontando para seção errada é
pior que ausente.

---

## 3. Mapa de migração por componente

### Fica intacto (não tocar)

| Componente | Motivo |
|---|---|
| `ratelimit.proto` + `Mapping` + `RateLimitService` (gRPC) | contrato de wire idêntico no v2 (só comentários do proto mudam) |
| `Window`, `WindowKey`, chave epoch-alinhada | §3 do design é o mesmo |
| `ReleaseLine` | fórmula idêntica (§6.1); passa a ser usada também na negação local |
| Stack de políticas (`Policy*`, `FilePolicyRepository`, watcher, loader) **menos `Prefetch`** | §8 é o mesmo |
| `Clock` (porta + `SystemClock`) | segue útil para janela/linha testáveis |
| `ObservabilityConfiguration`, adapter Micrometer | ganha métricas novas (§13), não muda de forma |

### Substituído

| Hoje (design anterior) | No v2 |
|---|---|
| Porta `GlobalCounter` (`lease` + `paceLease`) | porta única `tryAcquire(key, capacity, hits, priority, elapsed, duration, ttl) → (admitted, counter)` — o incremento condicional do §4. Retorna o contador **sempre** (admissão e negação) |
| `lease.lua` + `pace_lease.lua` | **um** script `conditional_increment.lua`: limiar = capacidade (ALTA) ou linha (BAIXA); nega **sem escrever**; mantém o truque de TTL-na-criação dos scripts atuais |
| `LettuceGlobalCounter` + `MeteredGlobalCounter` | mesma estrutura (adapter + decorator de métricas), corpo novo; ITs (`LettuceGlobalCounterIT`, `LettuceClusterGlobalCounterIT`) reescritos para o contrato novo |
| `LeaseResult`/`PaceResult` (`CounterResults.kt`) | um único `AcquireResult(admitted, counter)` |
| `BatchEvaluator` | reescrito: inspeção (regras novas do §5.2) → **ordenação por pressão** → **reserva sequencial** com abort na primeira negação; **sem refund** |

### Removido (deletar, com os testes correspondentes)

- `LocalBudget` (+ single-flight, lease local, crédito) — o coração do design anterior.
- `Prefetch` (`core/policy`) e todo o caminho de bloco/amortização.
- `ReserveHigh` e `Pacing` (application) — os dois caminhos de reserva viram um só, fino,
  sobre a porta nova.
- Refund no `BatchEvaluator` e qualquer noção de rollback local.
- `EvictionRunner`/`EvictionProperties` — substituídos pelo `Expiry` do Caffeine.
- `pace_lease.lua`, `lease.lua`.
- No `config/policies/`: campo `prefetch` do `policies.yaml` e do schema (copiar os já
  limpos deste diretório de design).

### Novo

- `core/` **estado local do v2** (§5 do design): bucket = `AtomicLong snapshot`
  (monotônico via `updateAndGet(max)`) + chave pronta; índice Caffeine com `Expiry` no fim
  da janela + carência; **pressão** EWMA por `(dimensão, valor)`.
- **Regras de negação local** (§5.2): esgotado terminal, next-admit exato da linha —
  funções puras ao lado de `ReleaseLine`.
- **Ordenação por pressão** no lote (§7.2–7.3): pressão decrescente, empate → menor
  capacidade; insertion sort em array (lotes de ~3).
- **Métricas novas** (§13): `served` vs `reserved` (a queima de prefixo é o delta),
  veredito por chave negadora + posição do negador, origem da negação (local vs central).

### Documentação do repositório (não esquecer)

- `docs/DESIGN-CONCEITUAL.md` → substituído pelo `DESIGN-CONCEITUAL-V2.md` (autocontido,
  com o histórico no Apêndice A).
- `docs/ARQUITETURA.md`, `OBSERVABILIDADE.md`, `SATURACAO.md`, `OTIMIZACAO-THROUGHPUT.md`,
  `POLITICAS.md`, `VARIAVEIS-DE-AMBIENTE.md`: revisar — todos citam lease/prefetch/refund
  em algum grau. `POLITICAS.md` perde a seção de prefetch; `OBSERVABILIDADE.md` ganha
  `served`/`reserved`.
- Comentários do proto (lease → incremento condicional) — já prontos na cópia deste
  diretório.
- `CHANGELOG.md`/git-cliff: a migração é **breaking de comportamento** (não de wire) —
  versionar como `0.3.0` com nota explícita.

---

## 4. Regras de desempenho do caminho quente

(Inalteradas do plano original; o repo já pratica a maioria.)

- Nenhum proxy Spring no hot path; beans finais injetados por construtor.
- Nenhum bloqueio de thread; I/O só via Lettuce; continuations sem hop desnecessário
  (medir antes de trocar dispatcher).
- Alocação mínima: sem streams no hot path; arrays pré-dimensionados; chave como
  `ByteArray` cacheado no bucket (trocar o codec do adapter para `ByteArrayCodec` na
  reescrita); primitivos sem boxing.
- Sem logging por request no caminho feliz.
- JVM: ZGC geracional, heap 1–2 GiB, `-XX:+AlwaysPreTouch`; Netty com transporte nativo.
- Timeout de comando Redis agressivo (~10 ms) → `STATUS_UNKNOWN` + abort do lote (§7.5);
  deadline do gRPC propagado (cancelamento aborta reservas restantes).

---

## 5. Marcos da migração

Cada marco deixa `main` verde (build + detekt + Konsist + testes). Ordem pensada para
**coexistência curta**: o caminho novo entra ao lado do antigo e a troca é atômica no M3.

**M1 — Porta e operação novas (ao lado das antigas).** `conditional_increment.lua`,
`tryAcquire` na porta (ou porta nova `ConditionalCounter`), adapter Lettuce + decorator de
métricas, ITs standalone e cluster. *Aceite:* teste de concorrência — K coroutines × N
hits contra capacidade C ⇒ admitidos == C, contador nunca > C; para BAIXA, admitidos ≈
linha (±1) e **zero escritas em negação** (auditar contador após rajada negada).

**M2 — Estado local do v2.** Snapshot monotônico + Caffeine + regras de negação + pressão
EWMA + property tests da linha (nó ≥ central, overflow). *Aceite:* rajada de negações ⇒
1 round-trip (métrica de origem da negação); esgotado não vaza entre janelas; footprint
estável com 1M de valores distintos.

**M3 — Troca do orquestrador (o corte).** `BatchEvaluator` reescrito: inspeção →
ordenação → reserva sequencial → abort; UNKNOWN/cancelamento preservados
(`ThrowingGlobalCounter`/fakes de teste atualizados para a porta nova). Os fluxos
`ReserveHigh`/`Pacing`/`LocalBudget` deixam de ser referenciados. *Aceite:* suíte
completa verde usando **só** o caminho novo; cenário de saturação em miniatura
(account 60/s × N + flow + source, tudo BAIXA) ⇒ `served/ofertado ≥ 90%` e
`reserved − served` de poucos %.

**M4 — Remoção do design anterior.** Deletar `LocalBudget`, `Prefetch`, `ReserveHigh`,
`Pacing`, refund, `EvictionRunner`, scripts Lua antigos, `LeaseResult`/`PaceResult` e
testes órfãos; limpar `policies.yaml`/schema (sem `prefetch`); Konsist/detekt verdes.
*Aceite:* `grep -ri "lease\|prefetch\|refund" src/` retorna só menções históricas
deliberadas (se houver); cobertura não cai abaixo do gate atual.

**M5 — Docs e observabilidade.** `docs/` atualizado (V2 no lugar do design antigo,
POLITICAS/OBSERVABILIDADE/SATURACAO revisados), métricas `served`/`reserved`/posição do
negador expostas, dashboards/alertas anotados em OBSERVABILIDADE.md. *Aceite:* nenhuma
referência pendurada ao design antigo; dashboard responde "qual dimensão mata lotes e
quanto queimo de prefixo".

**M6 — Hardening de desempenho + validação do SLO.** Profiling (async-profiler CPU +
alloc), decisão medida sobre bridge Reactor vs API async, cauda paralela (§7.2) se
necessário, toolchain 25, teste de carga completo. *Aceite (gates):*
- 20k rps sustentados, lotes de 3: **p99 ≤ 15 ms**, p50 ≤ 5 ms;
- CPU ≤ ~2 vCPUs por 10k rps (baseline a refinar no primeiro profiling);
- saturação conjunta: throughput ≥ 90% do teórico; **over-admission = 0** auditada
  (`max(contador) ≤ capacidade` por chave em toda a rodada);
- cenário original do colapso (6k rps, 3 dimensões, 11 réplicas): `served ≥ ~5,5k`
  (contra ~2k do design anterior).

---

## 6. Testes — o que reaproveitar e o que muda

| Suíte existente | Destino |
|---|---|
| `WindowTest`, `WindowKeyTest`, `ReleaseLineTest`, policy/*, grpc/* | ficam (comportamento idêntico) — somar property tests à linha |
| `LuaScriptsTest`, `Lettuce*IT` | reescritos para o script/porta novos |
| `LocalBudgetTest`, `ReserveHighTest`, `PacingTest` | deletados com o código |
| `BatchEvaluatorTest` | reescrito: all-or-nothing, short-circuit, ordenação, abort, UNKNOWN, cancelamento, queima de prefixo |
| `InMemoryGlobalCounter`, `ThrowingGlobalCounter`, `RecordingRateLimitMetrics` | adaptados ao contrato novo (o fake em memória fica trivial: um `AtomicLong` condicional) |
| `ArchitectureTest` (Konsist) | fica — vigia a migração |
| Carga (ghz/k6 + ElastiCache) | gates do M6, incluindo replay do cenário do colapso |

---

## 7. Riscos e mitigações

- **Regressão comportamental para clientes** (mesma wire, decisões diferentes sob
  saturação: mais NEGADO honesto no lugar de sub-admissão silenciosa) → changelog 0.3.0
  explícito + replay do cenário real antes do deploy.
- **Coexistência longa dos dois caminhos** (M1–M3) vira dívida → M4 é obrigatório no
  mesmo ciclo; o gate de grep garante a limpeza.
- **Bridge Reactor no hot path** → medir no M6 antes de otimizar; a porta suspend isola a
  troca.
- **Hot key além do shard** (limite estrutural, §10 do design) → alarme por ops/s por
  chave; resposta é modelagem da dimensão, não o serviço.
- **Docs desatualizados enganando o próximo leitor** (o repo tem 7 docs que citam lease) →
  M5 é marco de primeira classe, não "se sobrar tempo".

---

## Artefatos prontos neste diretório de design

Para copiar ao repositório na migração: `DESIGN-CONCEITUAL-V2.md` (→ `docs/`),
`policies.yaml` + `policies.schema.json` (sem prefetch, → `config/policies/`), comentários
atualizados do `ratelimit.proto`.
