# Políticas — klimiter

Define o **formato do arquivo de políticas** (`config/policies/policies.yaml`), sua **validação**
por JSON Schema e como ele é resolvido em runtime. É a fonte da verdade do **contrato de
configuração** dos limites — não da lógica que os aplica (essa é o `DESIGN-CONCEITUAL.md`).

> **Onde este documento se encaixa.**
>
> | Documento | Responde | Fonte da verdade de |
> |-----------|----------|---------------------|
> | [`DESIGN-CONCEITUAL.md`](DESIGN-CONCEITUAL.md) | **o quê** — fluxos, invariantes, premissas | comportamento |
> | [`ARQUITETURA.md`](ARQUITETURA.md) | **como o código é estruturado** — camadas, fronteiras | estrutura |
> | **`POLITICAS.md`** (este) | **como configurar limites** — formato, schema, resolução | configuração de políticas |
> | [`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md) | configuração via ambiente | configuração de runtime |
>
> Referências `§N` apontam para seções do [`DESIGN-CONCEITUAL.md`](DESIGN-CONCEITUAL.md). Se o
> formato mudar, atualize **este documento e o schema** no mesmo PR.

---

## Índice

1. [Arquivos e localização](#1-arquivos-e-localização)
2. [Formato](#2-formato)
3. [Campos de uma regra](#3-campos-de-uma-regra)
4. [Resolução de política](#4-resolução-de-política)
5. [Validação](#5-validação)
6. [Hot reload](#6-hot-reload)

---

## 1. Arquivos e localização

```
config/policies/
├─ policies.yaml          # o catálogo de limites (editável; recarregado a quente, §6)
└─ policies.schema.json   # JSON Schema (draft 2020-12) que valida o policies.yaml
```

O adapter `adapter.outbound.policy` carrega e observa esse arquivo, implementando a porta
`PolicyRepository` (ver [`ARQUITETURA.md`](ARQUITETURA.md) §3). O caminho é **configurável** pela
propriedade `klimiter.policies.path` (env `KLIMITER_POLICIES_PATH`); o default é
`config/policies/policies.yaml`, relativo ao diretório de trabalho — ver
[`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md). O arquivo **pode estar ausente no boot** —
nesse caso o serviço começa com políticas default (tudo pass-through) até ele aparecer (§8).

---

## 2. Formato

O arquivo é um **mapa de dimensão → política**. Uma *dimensão* é o eixo do limite (ex.:
`user_id`, `ip`, `tenant`); cada dimensão tem um `default` obrigatório e `overrides` opcionais
por valor exato.

```yaml
# yaml-language-server: $schema=./policies.schema.json
version: 1

policies:
  user_id:                       # dimensão (eixo do limite)
    default:                     # regra para qualquer valor sem override
      requests_per_unit: 1000    # capacidade (§3.1)
      unit: MINUTE               # SECOND | MINUTE | HOUR | DAY
      prefetch:                  # opcional (§5) — 'percent' OU 'count', exclusivos
        percent: 10              #   bloco = 10% da capacidade
    overrides:                   # exceções por valor exato (§8.1)
      service-account:
        requests_per_unit: 50000
        unit: MINUTE
        prefetch:
          count: 500             #   bloco = 500 unidades absolutas
  ip:
    default:
      requests_per_unit: 100
      unit: SECOND               # sem prefetch
```

| Chave | Obrigatório | Descrição |
|-------|-------------|-----------|
| `version` | sim | Versão do formato. Atual: `1`. |
| `policies` | sim | Mapa dimensão → política. Ao menos uma dimensão. |
| `policies.<dimensão>.default` | sim | Regra aplicada a valores sem override. |
| `policies.<dimensão>.overrides` | não | Mapa valor exato → regra. |

---

## 3. Campos de uma regra

Uma **regra** (`default` ou cada `override`) segue o modelo **Envoy RLS**: capacidade por uma
unidade de tempo, não uma duração arbitrária (§3.1).

| Campo | Obrigatório | Tipo | Descrição |
|-------|-------------|------|-----------|
| `requests_per_unit` | sim | inteiro ≥ 1 | Capacidade: requisições permitidas por **uma** `unit`. |
| `unit` | sim | enum | Unidade da janela: `SECOND`, `MINUTE`, `HOUR`, `DAY`. |
| `prefetch` | não | objeto | Amortização do lease de alta prioridade (§5). |

A **janela tem sempre o tamanho de exatamente uma `unit`** (§3.1) e é alinhada ao epoch/UTC
(§3.2). Não existe "a cada 10 segundos" nem "a cada 90 minutos".

| `unit` | comprimento da janela | fronteira de alinhamento (epoch/UTC) |
|--------|-----------------------|--------------------------------------|
| `SECOND` | 1 s | a cada segundo |
| `MINUTE` | 60 s | topo do minuto |
| `HOUR` | 3600 s | topo da hora (UTC) |
| `DAY` | 86400 s | meia-noite (UTC) |

### Prefetch

Na alta prioridade, em vez de arrendar exatamente N, arrenda-se um **bloco**; o excedente vira
crédito local que serve as próximas requisições sem round-trip (§5). O tamanho do bloco vem da
política, em **um** de dois modos mutuamente exclusivos:

- `prefetch.percent` — percentual de `requests_per_unit` (1–100);
- `prefetch.count` — número absoluto de unidades (≥ 0).

Omitir `prefetch` significa **sem prefetch**: arrenda-se apenas o necessário.

---

## 4. Resolução de política

Para `(dimensão, valor)`, a precedência é (§8.1):

```mermaid
flowchart TD
    Q["resolver (dimensão, valor)"] --> D{"dimensão em 'policies'?"}
    D -->|não| PT["sem política → PASS-THROUGH<br/>(admitido, não cobra)"]
    D -->|sim| O{"existe override<br/>para o valor exato?"}
    O -->|sim| Ov["usar a regra do override"]
    O -->|não| Def["usar o 'default' da dimensão"]
```

O **pass-through** é decidido aqui, na resolução — não dentro do reserve (§2.2). Uma dimensão
ausente do mapa nunca cobra nada.

---

## 5. Validação

A integridade do `policies.yaml` é garantida em três níveis. O `policies.schema.json` (JSON
Schema draft 2020-12) é o **contrato legível** do formato; o runtime aplica as **mesmas regras**
por parse tipado:

1. **Editor** — o comentário `# yaml-language-server: $schema=./policies.schema.json` no topo do
   arquivo dá autocomplete e erros inline contra o schema (VS Code com a extensão YAML, IntelliJ,
   etc.).
2. **Runtime** — o loader (`adapter.outbound.policy`) faz **parse tipado estrito**: campos
   desconhecidos são rejeitados (`FAIL_ON_UNKNOWN_PROPERTIES`) e cada valor passa pelas
   **invariantes dos Value Objects** do `core.policy` (capacidade ≥ 1, `unit` no enum, `prefetch`
   com exatamente um entre `percent`/`count`, `default` presente, `version` = 1). É equivalente ao
   schema, sem acoplar um motor de JSON Schema em runtime. Arquivo inválido → **mantém a última
   config boa** e loga o erro (§6).
3. **Build/test** — testes carregam o `policies.yaml` versionado pelo próprio loader, garantindo
   que o exemplo permanece válido e que exemplo e código não divergem.

As regras estruturais cobertas (em ambos os níveis): `version` correta, ao menos uma dimensão,
`default` presente, `requests_per_unit` inteiro ≥ 1, `unit` num enum fechado
(`SECOND`/`MINUTE`/`HOUR`/`DAY`), `prefetch` com exatamente um entre `percent`/`count`, e nenhum
campo desconhecido — para pegar erros de digitação cedo.

> **Knobs globais não vivem aqui.** Modo de pacing (fundido × probe, §6.4), carência de TTL
> (§3.2) e a conexão com o armazenamento central são configuração de runtime — ficam em
> `application.yaml`/ambiente (ver [`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md)), não no
> `policies.yaml`, que é só o catálogo de limites por dimensão.

---

## 6. Hot reload

As políticas são recarregadas **sem reiniciar** o serviço (§8). Um observador (`WatchService` do
JDK, gerenciado como `SmartLifecycle`) acompanha o **diretório** do arquivo (não o arquivo),
porque editores salvam via rename/replace:

- **criação/modificação** → recarrega, **revalida o arquivo inteiro** (§5) e, se válido, faz a
  **troca atômica** do índice de políticas; leituras nunca veem estado meio-atualizado;
- **deleção** → ignorada de propósito (um save transitório não derruba a config);
- **arquivo ausente no boot** → começa com políticas default e passa a valer assim que ele aparece;
- **falha de parsing/validação** → mantém a última config boa e loga o erro; o observador segue vivo.

Eventos em rajada (um único save costuma gerar vários eventos) são **coalescidos** por um debounce
configurável — `klimiter.policies.reload-debounce` (default 200ms, ver
[`VARIAVEIS-DE-AMBIENTE.md`](VARIAVEIS-DE-AMBIENTE.md)): após o último evento, espera-se esse
intervalo de silêncio e recarrega-se **uma só vez**.
