import grpc from 'k6/net/grpc';
import {Counter} from 'k6/metrics';

// ============================================================
// KLimiter load test configuration
// Edite este arquivo para mudar cenário, host, método, proto e request.
// O Python apenas executa este script, agrega os resultados e gera HTML.
// ============================================================

export const testConfig = {
    grpcHost: 'localhost:9090',
    grpcMethod: 'io.klimiter.RateLimitService/ShouldRateLimit',
    protoDirs: ['../../klimiter-service/src/main/proto'],
    protoFile: 'klimiter.proto',
    payloadFile: '../requests/rate-limit-request.json',
};

export const options = {
    scenarios: {
        constant_test: {
            executor: 'constant-arrival-rate',
            rate: 4000,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 100,
            maxVUs: 1000,
            gracefulStop: '30s',
        },

        // Para usar rampup/spike/mixed, comente o cenário acima
        // e descomente/ajuste os blocos desejados.

        // rampup_test: {
        //   executor: 'ramping-arrival-rate',
        //   startRate: 1,
        //   timeUnit: '1s',
        //   preAllocatedVUs: 100,
        //   maxVUs: 1000,
        //   gracefulStop: '30s',
        //   stages: [
        //     { target: 75, duration: '15s' },
        //     { target: 150, duration: '15s' },
        //     { target: 300, duration: '30s' },
        //   ],
        // },

        // spike_test: {
        //   executor: 'ramping-arrival-rate',
        //   startRate: 100,
        //   timeUnit: '1s',
        //   preAllocatedVUs: 100,
        //   maxVUs: 1000,
        //   gracefulStop: '30s',
        //   stages: [
        //     { target: 300, duration: '10s' },
        //     { target: 900, duration: '5s' },
        //     { target: 300, duration: '10s' },
        //     { target: 100, duration: '5s' },
        //   ],
        // },
    },
};

const client = new grpc.Client();
client.load(testConfig.protoDirs, testConfig.protoFile);

const allowedCounter = new Counter('grpc_allowed');
const overLimitCounter = new Counter('grpc_over_limit');
const errorCounter = new Counter('grpc_error');

const requestPayload = JSON.parse(open(testConfig.payloadFile));

export default function () {
    if (__ITER === 0) {
        client.connect(testConfig.grpcHost, {plaintext: true});
    }

    const response = client.invoke(testConfig.grpcMethod, requestPayload);

    if (response.status !== grpc.StatusOK) {
        errorCounter.add(1);
        return;
    }

    const message = response.message || {};
    const overallCode = message.overallCode;

    if (overallCode === 0 || overallCode === 'OK') {
        allowedCounter.add(1);
    } else if (overallCode === 1 || overallCode === 'OVER_LIMIT') {
        overLimitCounter.add(1);
    } else {
        errorCounter.add(1);
    }
}
