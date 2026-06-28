import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// --- Teste de COMPORTAMENTO sob carga variável -----------------------------
// Dois fluxos concorrentes contra o mesmo contador: um "online" (PRIORITY_HIGH)
// que oscila em onda entre min/max RPS, e um "baixa_prioridade" (PRIORITY_LOW)
// constante. Valida a interação de prioridade (§6.5): quando a ALTA sobe, a BAIXA
// é estrangulada pela linha de pacing; quando a ALTA cede, a BAIXA recupera. Os
// `thresholds` são os critérios de aceite.
//
// Pré-requisitos:
//   - serviço de pé (ex.: `docker compose up`), gRPC em ${TARGET}
//   - as 3 dimensões existindo no policies.yaml (ver test/load/policies.sample.yaml e o README)
//   - `k6` com suporte a gRPC
//
// Uso (a partir da raiz do repositório):
//   k6 run test/load/behavior/evaluate-online-variavel.js
//   k6 run -e DURATION=1m -e ONLINE_MAX_RPS=70 -e BAIXA_PRIORIDADE_RPS=1000 test/load/behavior/evaluate-online-variavel.js
// === OPENTELEMETRY (exporta métricas do k6 para o LGTM do compose) ===
//   K6_OTEL_GRPC_EXPORTER_ENDPOINT=localhost:4317 \
//   K6_OTEL_GRPC_EXPORTER_INSECURE=true \
//   k6 run --out opentelemetry test/load/behavior/evaluate-online-variavel.js
const TARGET = __ENV.TARGET || 'localhost:9090';
const DURATION = __ENV.DURATION || '30m';

const ONLINE_MIN_RPS = parseInt(__ENV.ONLINE_MIN_RPS || '0', 10);
const ONLINE_MAX_RPS = parseInt(__ENV.ONLINE_MAX_RPS || '70', 10);

// Tempo de um ciclo completo do online: sobe de min -> max em metade do período
// e desce de max -> min na outra metade.
const ONLINE_WAVE_PERIOD = __ENV.ONLINE_WAVE_PERIOD || '5m';

const BAIXA_PRIORIDADE_RPS = parseInt(__ENV.BAIXA_PRIORIDADE_RPS || '1000', 10);

const ONLINE_PRE_ALLOCATED_VUS = parseInt(__ENV.ONLINE_VUS || __ENV.VUS || '20', 10);
const ONLINE_MAX_VUS = parseInt(
    __ENV.ONLINE_MAX_VUS || __ENV.MAX_VUS || String(ONLINE_PRE_ALLOCATED_VUS * 4),
    10,
);

const BAIXA_PRIORIDADE_PRE_ALLOCATED_VUS = parseInt(
    __ENV.BAIXA_PRIORIDADE_VUS || __ENV.VUS || '50',
    10,
);
const BAIXA_PRIORIDADE_MAX_VUS = parseInt(
    __ENV.BAIXA_PRIORIDADE_MAX_VUS ||
    __ENV.MAX_VUS ||
    String(BAIXA_PRIORIDADE_PRE_ALLOCATED_VUS * 4),
    10,
);

const DISTINCT_KEYS = parseInt(__ENV.DISTINCT_KEYS || '1000', 10);
const HITS = parseInt(__ENV.HITS || '1', 10);

// --- gRPC clients ---------------------------------------------------------
// importPaths relativo a este arquivo; o caminho do .proto preserva o pacote
// klimiter.v1, então o serviço resolve como klimiter.v1.RateLimitService.
const PROTO_IMPORT_PATH = __ENV.PROTO_IMPORT_PATH || '../../../src/main/proto';
const PROTO_FILE = __ENV.PROTO_FILE || 'klimiter/v1/ratelimit.proto';

const onlineClient = new grpc.Client();
onlineClient.load([PROTO_IMPORT_PATH], PROTO_FILE);

const baixaPrioridadeClient = new grpc.Client();
baixaPrioridadeClient.load([PROTO_IMPORT_PATH], PROTO_FILE);

// --- Custom metrics: segregated by flow suffix ----------------------------
// Duas granularidades, lado a lado:
//   requests_* -> nível da REQUISIÇÃO (lote all-or-nothing), via veredito `overallStatus`.
//   items_*    -> nível da DIMENSÃO, via cada item em `decisions` (3 por request).
const metrics = {
    online: {
        requestsSent: new Counter('k6_klimiter_requests_sent_online'),
        transportOk: new Counter('k6_klimiter_transport_ok_online'),
        transportError: new Counter('k6_klimiter_transport_error_online'),
        requestsAllowed: new Counter('k6_klimiter_requests_allowed_online'),
        requestsDenied: new Counter('k6_klimiter_requests_denied_online'),
        requestsUnknown: new Counter('k6_klimiter_requests_unknown_online'),
        itemsAllowed: new Counter('k6_klimiter_items_allowed_online'),
        itemsDenied: new Counter('k6_klimiter_items_denied_online'),
        itemsUnknown: new Counter('k6_klimiter_items_unknown_online'),
    },
    baixa_prioridade: {
        requestsSent: new Counter('k6_klimiter_requests_sent_baixa_prioridade'),
        transportOk: new Counter('k6_klimiter_transport_ok_baixa_prioridade'),
        transportError: new Counter('k6_klimiter_transport_error_baixa_prioridade'),
        requestsAllowed: new Counter('k6_klimiter_requests_allowed_baixa_prioridade'),
        requestsDenied: new Counter('k6_klimiter_requests_denied_baixa_prioridade'),
        requestsUnknown: new Counter('k6_klimiter_requests_unknown_baixa_prioridade'),
        itemsAllowed: new Counter('k6_klimiter_items_allowed_baixa_prioridade'),
        itemsDenied: new Counter('k6_klimiter_items_denied_baixa_prioridade'),
        itemsUnknown: new Counter('k6_klimiter_items_unknown_baixa_prioridade'),
    },
};

export const options = {
    scenarios: {
        online: {
            // Varia continuamente entre ONLINE_MIN_RPS e ONLINE_MAX_RPS.
            executor: 'ramping-arrival-rate',
            exec: 'acquireOnline',
            startRate: ONLINE_MIN_RPS,
            timeUnit: '1s',
            stages: buildRepeatingRampStages({
                totalDuration: DURATION,
                wavePeriod: ONLINE_WAVE_PERIOD,
                minRate: ONLINE_MIN_RPS,
                maxRate: ONLINE_MAX_RPS,
            }),
            preAllocatedVUs: ONLINE_PRE_ALLOCATED_VUS,
            maxVUs: ONLINE_MAX_VUS,
            tags: { fluxo: 'online' },
        },
        baixa_prioridade: {
            // Mantém o TPS constante em paralelo com o online.
            executor: 'constant-arrival-rate',
            exec: 'acquireBaixaPrioridade',
            rate: BAIXA_PRIORIDADE_RPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: BAIXA_PRIORIDADE_PRE_ALLOCATED_VUS,
            maxVUs: BAIXA_PRIORIDADE_MAX_VUS,
            tags: { fluxo: 'baixa_prioridade' },
        },
    },
    thresholds: {
        'grpc_req_duration{scenario:online}': ['p(95)<50', 'p(99)<100'],
        'grpc_req_duration{scenario:baixa_prioridade}': ['p(95)<50', 'p(99)<100'],
        'checks{scenario:online}': ['rate>0.99'],
        'checks{scenario:baixa_prioridade}': ['rate>0.99'],
    },
};

export function acquireOnline() {
    evaluate({
        client: onlineClient,
        flow: 'online',
        valuePrefix: 'user',
        priority: 'PRIORITY_HIGH',
    });
}

export function acquireBaixaPrioridade() {
    evaluate({
        client: baixaPrioridadeClient,
        flow: 'baixa_prioridade',
        valuePrefix: 'user',
        priority: 'PRIORITY_LOW',
    });
}

function evaluate({ client, flow, valuePrefix, priority }) {
    // One persistent connection per VU; opened on its first iteration.
    if (__ITER === 0) {
        client.connect(TARGET, { plaintext: true });
    }

    const flowMetrics = metrics[flow];
    const value = `${valuePrefix}-${Math.floor(Math.random() * DISTINCT_KEYS)}`;

    // Contrato klimiter: RateLimitRequest { repeated RateLimitDescriptor descriptors },
    // item = { key, value, hits, priority }.
    const request = {
        descriptors: [
            {
                key: 'rate_limit_account_id',
                value: value,
                hits: HITS,
                priority: priority,
            }, {
                key: 'rate_limit_source',
                value: value,
                hits: HITS,
                priority: priority,
            }, {
                key: 'rate_limit_flow',
                value: value,
                hits: HITS,
                priority: priority,
            },
        ],
    };

    flowMetrics.requestsSent.add(1);

    const response = client.invoke('klimiter.v1.RateLimitService/ShouldRateLimit', request);
    const transportOk = response && response.status === grpc.StatusOK;

    check(response, {
        [`transport_status_ok_${flow}`]: () => transportOk,
    });

    if (!transportOk) {
        flowMetrics.transportError.add(1);
        return;
    }

    flowMetrics.transportOk.add(1);

    // Nível da requisição: veredito coletivo do lote (all-or-nothing). ALLOWED só
    // se todas as dimensões passam; DENIED se qualquer uma nega. Uma vez por request.
    const overall = response.message && response.message.overallStatus;

    switch (overall) {
        case 'STATUS_ALLOWED':
            flowMetrics.requestsAllowed.add(1);
            break;
        case 'STATUS_DENIED':
            flowMetrics.requestsDenied.add(1);
            break;
        default:
            flowMetrics.requestsUnknown.add(1);
    }

    // Nível da dimensão: uma contagem por item em decisions (3 por request).
    const decisions = (response.message && response.message.decisions) || [];

    decisions.forEach((decision) => {
        switch (decision.status) {
            case 'STATUS_ALLOWED':
                flowMetrics.itemsAllowed.add(1);
                break;
            case 'STATUS_DENIED':
                flowMetrics.itemsDenied.add(1);
                break;
            default:
                flowMetrics.itemsUnknown.add(1);
        }
    });
}

function buildRepeatingRampStages({ totalDuration, wavePeriod, minRate, maxRate }) {
    const totalSeconds = parseDurationToSeconds(totalDuration);
    const periodSeconds = Math.max(2, parseDurationToSeconds(wavePeriod));
    const halfPeriodSeconds = Math.max(1, Math.floor(periodSeconds / 2));

    const stages = [];
    let elapsedSeconds = 0;
    let nextTarget = maxRate;

    while (elapsedSeconds < totalSeconds) {
        const remainingSeconds = totalSeconds - elapsedSeconds;
        const stageSeconds = Math.min(halfPeriodSeconds, remainingSeconds);

        stages.push({
            target: nextTarget,
            duration: `${stageSeconds}s`,
        });

        elapsedSeconds += stageSeconds;
        nextTarget = nextTarget === maxRate ? minRate : maxRate;
    }

    return stages;
}

function parseDurationToSeconds(duration) {
    const durationAsString = String(duration).trim();
    const regex = /(\d+)(ms|s|m|h)/g;

    let totalSeconds = 0;
    let matched = false;
    let match;

    while ((match = regex.exec(durationAsString)) !== null) {
        matched = true;

        const value = parseInt(match[1], 10);
        const unit = match[2];

        switch (unit) {
            case 'ms':
                totalSeconds += Math.ceil(value / 1000);
                break;
            case 's':
                totalSeconds += value;
                break;
            case 'm':
                totalSeconds += value * 60;
                break;
            case 'h':
                totalSeconds += value * 60 * 60;
                break;
            default:
                break;
        }
    }

    if (!matched || totalSeconds <= 0) {
        throw new Error(`Invalid duration: ${duration}`);
    }

    return totalSeconds;
}
