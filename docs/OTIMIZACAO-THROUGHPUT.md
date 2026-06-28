# Otimização de throughput — branch `feature/throughput-optimization`

> Relatório de uma rodada de otimização do teto de throughput (joelho) do klimiter, com foco em
> **subir o joelho de HIGH e LOW preservando o comportamento de prioridade** (§5/§6/§6.5 do
> [DESIGN-CONCEITUAL](DESIGN-CONCEITUAL.md)), feita na branch `feature/throughput-optimization`.
> A **régua de dimensionamento** (escala por cores) está em [§ Escalabilidade](#escalabilidade-por-cores--instrumentação-onoff-dimensionamento);
> as **premissas de transferência p/ Kubernetes** ficam no [SATURACAO.md §7](SATURACAO.md#7-premissas-de-transferência-p-kubernetes-o-que-a-régua-não-captura).

## TL;DR

- **HIGH (alta prioridade): joelho subiu de ~24k → ~26k RPS** (p99 a 26k caiu de ~19,6 ms para
  ~14,4 ms num A/B limpo) — ganho **medido e reproduzível**, já commitado.
- **LOW (baixa prioridade): ~16k RPS, inalterado.** Concluído que está **maximizado para o design**
  neste ambiente: o caminho LOW é *round-trip-bound* (cada admissão exige um round-trip ao central,
  §6.1 — requisito de corretude, não dá pra remover sem over-admission).
- **Comportamento preservado:** `./gradlew check` verde; testes de prioridade (HIGH/LOW), batch,
  pacing e reserve passando. Validação funcional via grpcurl confere os 4 caminhos (HIGH allow,
  LOW pacing, HIGH > capacidade → denied, sem política → pass-through).
- **3 commits**, um por camada, todos otimizações do *como* — **nenhuma muda o design** nem suas
  invariantes (§9).

## O que foi feito (3 otimizações, design intacto)

| # | Commit | Camada | Ideia | Por que é seguro |
|---|--------|--------|-------|------------------|
| 1 | `perf(redis): aplica o TTL só na criação da chave…` | Lua (`lease.lua`, `pace_lease.lua`) | Remove o `PTTL` que rodava em **toda** chamada só pra decidir o TTL. Detecta "chave recém-criada" pelo retorno do `INCRBY` (== incremento ⟺ key nova). **Um comando Redis a menos por round-trip.** | Usa a invariante §9 (contador só cresce de 0) + premissa §11 (scripts são os únicos escritores). Mantém "aplica o TTL só quando a chave ainda não tem" (§4.1). |
| 2 | `perf(redis): lê o resultado do script sem List<Long>…` | Adapter Lettuce (`LuaScripts`, `LettuceGlobalCounter`) | Lê as duas posições direto da lista crua do Lettuce (`longAt`), em vez de alocar uma `List<Long>` intermediária por round-trip. | Sem mudança de contrato da porta `GlobalCounter`. Só corta alocação. |
| 3 | `perf(batch): resolve o bucket uma vez por item + fan-out só do que vai ao central` | Core (`BatchEvaluator`, `LocalBudget`) | (a) A inspeção (§7.1) resolve o bucket uma vez (holder `Matched`) e a reserva reaproveita — antes reabria o índice e recomputava a janela por item. (b) O fan-out dispara `async` **só** para itens que podem suspender num round-trip; ALTA servida do lease local e pass-through rodam inline (sem custo de criar corrotina). | Veredito, ordem do lote e a interação de prioridade (§6.5) preservados. O design já diz "paralelismo é otimização, não corretude" (§7). |

**Por que o HIGH ganhou e o LOW não:** a ALTA é *CPU/local-path-bound* (prefetch + short-circuit
amortizam o central, §5), então cortar lookups duplicados, alocações e corrotinas desnecessárias
sobe o teto direto. A BAIXA é *round-trip-bound* (§6.1 obriga validar a linha no central a cada
admissão), e essas três mudanças mexem em CPU/uma-instrução-Redis — não no número de round-trips,
que é o gargalo do LOW.

## Metodologia de medição

- **2 CPUs pinados** no serviço (`taskset -c 0,1` + `-XX:ActiveProcessorCount=2`), ZGC geracional,
  heap 512–768m, **OTel/observação por-RPC off** — exatamente o harness de [SATURACAO.md](SATURACAO.md).
- **`ghz` pinado nos cores 2–15** (cliente nunca disputa com o servidor).
- **A/B com dois jars** (baseline pré-mudanças × otimizado) no mesmo harness, alternando para
  cancelar drift, e **mediana de p99 sobre N repetições** perto do joelho (descarta colapsos
  transitórios).

### Pegadinhas do ambiente (importantes para reproduzir)

Este host é **compartilhado e ruidoso**, o que mascarou metade das medições iniciais:

1. **Servidores órfãos.** Execuções que estouraram timeout deixaram JVMs presas nos cores 0,1
   competindo com cada teste novo (load subiu pra ~4,5). **Sempre matar com `pkill -9 -f klimiter…`
   e conferir a porta 9090 livre antes de medir.** Um zombie segurando 9090 faz todo servidor novo
   falhar no bind e morrer — parecia "crash loop", era conflito de porta.
2. **VM do Docker (qemu).** O Redis roda **dentro da VM do Docker Desktop** (`desktop-linux`).
   Não dá pra matá-la (derruba o Redis). Mitigado **pinando a VM nos cores 2–15** (`taskset -acp 2-15 <pid-qemu>`),
   liberando 0,1 limpos pro servidor. Mesmo assim, o Redis-na-VM compartilha cores com o `ghz`, então
   a **latência do round-trip do LOW fica ruidosa** — é o teto de precisão da medição do LOW aqui.

## Resultados (A/B limpo, máquina quieta)

| Fluxo | Baseline (pré) | Otimizado | Δ |
|-------|----------------|-----------|---|
| **HIGH** | ~24k (p99 a 26k ≈ 19,6 ms) | **~26k** (p99 a 26k ≈ 14,4 ms) | **+~8% / joelho sobe um degrau** |
| **LOW**  | ~16k | ~16k | sem mudança mensurável |

> Os números absolutos dependem da máquina; o que vale é o **delta** no mesmo harness. Os valores de
> baseline limpos (HIGH ~24k, LOW ~16k) já ficaram **acima** dos documentados em SATURACAO.md
> (HIGH ≥16k, LOW ~14k) porque a máquina aqui tem mais folga — o ganho relatado é o **incremento
> sobre o próprio baseline limpo**.

## Tentativa que NÃO entrou (revertida)

- **Trim de alocação no lookup do bucket** (`WindowKey.windowStart` — calcular só o início da janela
  no caminho quente, sem alocar o `Window`). Correto, `check` verde, **não pode regredir** (estritamente
  menos alocação), mas **sem ganho de joelho mensurável** (a mediana de p99 do LOW/HIGH ficou dentro
  do ruído). Como o critério é "commitar só quando o joelho melhora", foi **revertida** — a branch
  contém apenas melhorias medidas. Fica como candidata se o LOW virar CPU-bound noutro perfil de carga.

## Por que o LOW está maximizado (para este design)

- O caminho LOW **precisa** de um round-trip ao central por admissão (§6.1: "o round-trip de validação
  não é dispensável" — o snapshot local é lower-bound, só **nega**, nunca admite sozinho; admitir do
  local sem confirmar a linha furaria o pacing).
- A dimensão de alta cardinalidade (`account`, cap 60, 50k chaves) **quase sempre admite** → ~1
  round-trip por request, sem amortização (pacing não faz prefetch, §6.1 — prefetch infla o contador
  e estrangula a própria baixa).
- Juntar as 3 dimensões num único script multi-key **violaria** o princípio "uma chave por operação"
  (§3.2, compatibilidade com Redis Cluster) — descartado.
- Logo, no teto de 2 cores, o LOW é limitado pela vazão de round-trips ao central + a CPU de
  suspender/retomar uma corrotina por round-trip. Isso é **inerente ao design** de pacing.

## Como validar / reproduzir

```bash
# 1) Redis no ar
docker compose up -d redis

# 2) Servidor pinado (harness de saturação)
make sat-server                       # builda o bootJar e sobe pinado em 2 cores, OTel off

# 3) Sweeps (noutro terminal) — ghz idealmente pinado nos cores restantes
make saturation PRIORITY=PRIORITY_HIGH SAT_STEPS="18000 20000 22000 24000 26000 28000"
make saturation PRIORITY=PRIORITY_LOW  SAT_STEPS="12000 14000 15000 16000 17000 18000"
```

Validação funcional rápida (servidor normal, qualquer um):

```bash
grpcurl -plaintext -import-path src/main/proto -proto klimiter/v1/ratelimit.proto \
  -d '{"descriptors":[{"key":"rate_limit_flow","value":"flow-0","hits":1,"priority":"PRIORITY_HIGH"}]}' \
  localhost:9090 klimiter.v1.RateLimitService.ShouldRateLimit
```

## Escalabilidade por cores + instrumentação ON/OFF (dimensionamento)

Medição do joelho (p99 < 15 ms) variando **nº de cores** do pod e **instrumentação ON/OFF**, por
fluxo. Servidor pinado em `0..N-1` (`ActiveProcessorCount=N`), `ghz` nos cores restantes, Redis
isolado na VM. **Heap generoso** (≥ N×1 GB) — descoberta-chave: com `-Xmx768m` o ZGC **stalla** sob
rajada em poucos cores e derruba a p99 (colapsos transitórios); com heap folgado a curva fica limpa.

### Joelho medido (RPS sustentável, p99 < 15 ms)

| Cores | HIGH OFF | HIGH ON | LOW OFF | LOW ON |
|------:|---------:|--------:|--------:|-------:|
| **1** | ~19k | ~16k | ~16k | ~11k |
| **2** | ~33k | ~26k | ~18k | ~15k |
| **4** | ~78k | ~64k | ~19k | ~19k |
| **8** | *(cliente-limitado¹)* | *(cliente-limitado¹)* | ~18k | ~16k |

> ¹ Em 8 cores no servidor, o `ghz` fica só com 6 cores para gerar carga e **satura antes do servidor**
> (~59k RPS de teto do cliente) — o joelho de HIGH a 8 cores **não é mensurável nesta máquina**;
> precisa de um host de carga separado. O HIGH a 1/2/4 cores já fixa a tendência.

### As duas leis de escala (o que importa para sizing)

- **HIGH (prioridade alta) escala ~LINEAR com cores.** ~**+19–20k RPS por core** adicionado
  (19k → 33k → 78k em 1→2→4 cores). É CPU/local-bound (prefetch + short-circuit amortizam o central),
  então core a mais = throughput a mais.
- **LOW (pacing) NÃO escala com cores — plateau ~18k por pod.** Subir de 1→8 cores quase não move o
  joelho (16k→18k). Confirmado que **não é o Redis**: dar 8 cores à VM do Redis manteve o mesmo ~16k.
  É **round-trip-bound** (latência do round-trip ao central + a corrotina por round-trip, §6.1). Para
  escalar LOW: **mais pods** (cada pod soma ~18k, pois o Redis não é o gargalo) ou **reduzir o custo
  do round-trip** (Redis co-locado/mais rápido, menos dimensões por lote) — **não** mais cores por pod.
- **Instrumentação ON custa ~−20% no HIGH** (per-RPC observation, igual a SATURACAO §5.2). No **LOW só
  pesa quando o pod está sem folga de CPU** (1 core: −31%); com folga (≥4 cores) é **~grátis** (a CPU
  da observação cabe na ociosidade que o LOW deixa esperando o round-trip).

### A conta para dimensionar

```
cores  ≈  RPS_HIGH_alvo / 19000        (÷ 0,8  se instrumentação ON)      → escala VERTICAL
pods   ≈  RPS_LOW_alvo  / 18000        (cada pod ≈ 18k de LOW, qualquer nº de cores) → escala HORIZONTAL
```

Exemplos:
- **100k RPS de HIGH, OTel off:** `100000/19000 ≈ 6 cores` (1 pod de 6 cores, ou 2 de 3).
- **100k RPS de HIGH, OTel on:** `100000/19000/0,8 ≈ 7 cores`.
- **60k RPS de LOW:** `60000/18000 ≈ 4 pods` (dar mais cores a 1 pod **não** ajuda o LOW).
- **Mix:** dimensione o HIGH por cores e garanta **≥ ⌈RPS_LOW/18000⌉ pods**; o que for maior manda.

> **Ressalvas.** Números **desta máquina** (16 cores, Redis na VM do Docker); o que transfere é a
> **inclinação** (~+19k HIGH/core) e os **ratios** (LOW plateau, −20% instrumentação), não os valores
> absolutos — confirme no hardware de produção. Joelho de HIGH ≥ 4 cores exige host de carga dedicado.
> Heap: use `-Xmx` folgado (regra de bolso ≥ 1 GB/core) para o ZGC não stallar sob rajada.

### Sensibilidade do LOW ao RTT do Redis (cenário Fargate / Redis remoto)

No co-locado deste doc o Redis tem RTT ~0,1 ms. No Fargate (ou qualquer Redis pela rede) o RTT sobe, e só
o **LOW** sente (o HIGH é caminho local). Para medir **sem um Redis dedicado**, injeta-se latência na
interface do container do Redis com **`tc netem`** (kernel-level, não limita vazão — ao contrário de um
proxy TCP como o toxiproxy, que satura e vira ele mesmo o gargalo):

```bash
# +Xms na eth0 do Redis (sidecar privilegiado no netns do container); klimiter fala DIRETO no Redis
docker run --rm --net container:<redis> --cap-add NET_ADMIN alpine \
  sh -c 'apk add -q iproute2 && tc qdisc replace dev eth0 root netem delay 1ms'
# medir o RTT real: redis-cli -h <redis> --latency  (de outro container na mesma rede)
# remover: tc qdisc del dev eth0 root
```

Joelho do LOW vs. RTT do Redis (2 cores, OFF, **SLO p99 < 15 ms**):

| RTT do Redis | Joelho LOW | p50 |
|---:|---:|---:|
| ~0,1 ms (direto) | **18k** | 0,5 ms |
| ~1,1 ms | **18k** | 1,6 ms |
| ~2–5 ms | ~14–16k | 2–5 ms |
| **~11 ms** | **~3k** ⚠️ | 11 ms |

> **HIGH:** ~inalterado (~36k a 3 ms de RTT) — o RTT do Redis quase não afeta o caminho local.

**Modelo:** `joelho_LOW ≈ min(teto ~18k, vazão onde a p99 bate o SLO dado o RTT base)`. Com SLO de 15 ms há
**folga** sobre poucos ms de RTT, então o LOW **não despenca** por estar na rede — ele cai conforme o RTT
**se aproxima do SLO** (o termo que manda é `headroom = SLO − RTT`). **Implicação:** ElastiCache **mesma
AZ** (~0,5–1 ms) deixa o LOW perto do co-locado; o risco é **cross-region** (RTT alto) ou **SLO apertado**
(p99 < 5 ms torna 2–3 ms de RTT caro). Ver [SATURACAO.md §8.3](SATURACAO.md#83-eks-fargate-especificidades).

## Recomendações / próximos passos

- **Mergear** a branch: o ganho de HIGH é real e o LOW não regrediu; o `check` está verde.
- **HIGH** escala vertical (~+19k RPS/core); **LOW** só escala horizontal (~18k/pod) — ver a seção de
  dimensionamento. Para subir o LOW por pod seria preciso **cortar round-trips/latência ao central**
  (Redis co-locado, menos dimensões), não cores nem código dentro deste design.
- **Heap:** use `-Xmx` folgado (≥ ~1 GB/core). Com `768m` o ZGC stalla sob rajada em poucos cores e
  derruba a p99 — foi a maior fonte de ruído nas medições.
- Medições finas de joelho de HIGH exigem um **host de carga dedicado** (servidor com ≥ 4 cores satura
  o `ghz` local nesta máquina de 16 cores).
