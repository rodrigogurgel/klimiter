#!/usr/bin/env bash
# Teste de PERFORMANCE (saturação) com ghz: sobe o RPS em degraus e acha o "joelho"
# — o maior RPS em que a p99 da requisição ainda fica abaixo de KNEE_MS. Mede o teto
# de throughput do servidor gRPC com overhead de cliente desprezível (preferível ao
# k6 para isso). Cada requisição é o lote all-or-nothing de 3 dimensões (§7).
#
# Pré-requisitos:
#   - serviço de pé (ex.: `docker compose up`), escutando gRPC em ${TARGET}
#   - as 3 dimensões existindo no policies.yaml (ver scripts/load-test/policies.sample.yaml e o README)
#   - `ghz` e `python3` instalados
#
# Uso (a partir da raiz do repositório):
#   bash scripts/load-test/performance/saturation-ghz.sh
#   PRIORITY=PRIORITY_LOW STEPS="6000 8000 10000" bash scripts/load-test/performance/saturation-ghz.sh
#
# Variáveis (env):
#   TARGET         endereço gRPC (default localhost:9090)
#   PRIORITY       PRIORITY_HIGH | PRIORITY_LOW (default PRIORITY_HIGH)
#   DURATION       duração de cada degrau (default 20s)
#   KNEE_MS        limite de p99 que define a saturação (default 15)
#   STEPS          lista de RPS dos degraus
#   Espaço de chaves POR DIMENSÃO (prefixo + sufixo iterador 0..N-1; alto = ~0%
#   negação = mede o caminho de Redis; 1 = chave fixa = caminho local lock-free):
#     ACCOUNT_DISTINCT_KEYS  rate_limit_account_id — a de maior variação (default 50000)
#     SOURCE_DISTINCT_KEYS   rate_limit_source     — média (default 100)
#     FLOW_DISTINCT_KEYS     rate_limit_flow       — fixa, nunca muda (default 1)
#   CONNECTIONS    conexões HTTP/2 do ghz (default 16)
#   PROTO_DIR/PROTO caminho do .proto (default src/main/proto e klimiter/v1/ratelimit.proto)
set -u

TARGET="${TARGET:-localhost:9090}"
PRIORITY="${PRIORITY:-PRIORITY_HIGH}"
DURATION="${DURATION:-20s}"
KNEE_MS="${KNEE_MS:-15}"
ACCOUNT_DISTINCT_KEYS="${ACCOUNT_DISTINCT_KEYS:-50000}"
SOURCE_DISTINCT_KEYS="${SOURCE_DISTINCT_KEYS:-100}"
FLOW_DISTINCT_KEYS="${FLOW_DISTINCT_KEYS:-1}"
CONNECTIONS="${CONNECTIONS:-16}"
STEPS="${STEPS:-1000 2000 4000 6000 8000 9000 10000 11000 12000}"
PROTO_DIR="${PROTO_DIR:-src/main/proto}"
PROTO="${PROTO:-klimiter/v1/ratelimit.proto}"

command -v ghz >/dev/null 2>&1 || { echo "ghz não encontrado — instale com 'brew install ghz' (ou veja https://ghz.sh)"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 não encontrado (usado para parse do JSON)"; exit 1; }

# Uma chave por dimensão: prefixo identifica a dimensão + sufixo iterador
# {{randomInt 0 N}} (= 0..N-1) define o espaço de chaves daquela dimensão.
# N=1 -> sufixo sempre 0 -> chave fixa (flow nunca muda).
account_key="acct-{{randomInt 0 ${ACCOUNT_DISTINCT_KEYS}}}"
source_key="src-{{randomInt 0 ${SOURCE_DISTINCT_KEYS}}}"
flow_key="flow-{{randomInt 0 ${FLOW_DISTINCT_KEYS}}}"
# Contrato klimiter: RateLimitRequest { repeated RateLimitDescriptor descriptors }, item = {key,value,hits,priority}.
DATA="{\"descriptors\":[\
{\"key\":\"rate_limit_account_id\",\"value\":\"${account_key}\",\"hits\":1,\"priority\":\"${PRIORITY}\"},\
{\"key\":\"rate_limit_source\",\"value\":\"${source_key}\",\"hits\":1,\"priority\":\"${PRIORITY}\"},\
{\"key\":\"rate_limit_flow\",\"value\":\"${flow_key}\",\"hits\":1,\"priority\":\"${PRIORITY}\"}]}"

echo "### Saturação ghz | ${PRIORITY} | joelho p99>=${KNEE_MS}ms | dur=${DURATION} | alvo ${TARGET}"
echo "### chaves: account=${ACCOUNT_DISTINCT_KEYS} source=${SOURCE_DISTINCT_KEYS} flow=${FLOW_DISTINCT_KEYS}"
printf "%9s %10s %8s %8s %8s %8s %7s\n" "rps_alvo" "rps_real" "p50ms" "p95ms" "p99ms" "maxms" "nonOK"

crossed=0
for RPS in $STEPS; do
  CONC=$(python3 -c "print(min(1000, max(60, int($RPS*0.08))))")
  OUT=$(ghz --insecure --proto "${PROTO_DIR}/${PROTO}" --import-paths "${PROTO_DIR}" \
        --call klimiter.v1.RateLimitService.ShouldRateLimit \
        --rps "$RPS" --duration "$DURATION" --concurrency "$CONC" --connections "$CONNECTIONS" \
        --data "$DATA" -O json "$TARGET" 2>/dev/null)
  if [ -z "$OUT" ]; then
    printf "%9s  (ghz falhou — servidor no ar em %s?)\n" "$RPS" "$TARGET"
    continue
  fi
  LINE=$(printf '%s' "$OUT" | RPS="$RPS" python3 -c '
import json, sys, os
d = json.load(sys.stdin)
pct = {x["percentage"]: x["latency"]/1e6 for x in d.get("latencyDistribution", [])}
non = sum(v for k, v in d.get("statusCodeDistribution", {}).items() if k != "OK")
p99 = pct.get(99, 0.0)
row = "%9s %10.0f %8.2f %8.2f %8.2f %8.2f %7d" % (
    os.environ["RPS"], d.get("rps", 0), pct.get(50, 0), pct.get(95, 0),
    p99, d.get("slowest", 0)/1e6, non)
print("%.4f\t%s" % (p99, row))
') || { printf "%9s  (parse falhou)\n" "$RPS"; continue; }
  P99="${LINE%%	*}"
  echo "${LINE#*	}"
  if awk "BEGIN{exit !($P99>=$KNEE_MS)}"; then
    [ "$crossed" = "1" ] && break
    crossed=1
  fi
done
