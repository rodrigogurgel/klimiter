import grpc from 'k6/net/grpc';
import { Counter } from 'k6/metrics';

// ============================================================
// KLimiter load test configuration
// ============================================================

export const testConfig = {
    grpcHost: 'localhost:9090',
    grpcMethod: 'io.klimiter.RateLimitService/ShouldRateLimit',
    protoDirs: ['../../klimiter-service/src/main/proto'],
    protoFile: 'klimiter.proto',
    payloadFile: '../requests/rate-limit-request.json',

    // Evita poluir demais o console em 4k req/s.
    debugErrors: true,
    maxDebugLogsPerVu: 5,
};

export const options = {
    scenarios: {
        constant_test: {
            executor: 'constant-arrival-rate',
            rate: 300,
            timeUnit: '1s',
            duration: '1m',
            preAllocatedVUs: 200,
            maxVUs: 2000,
            gracefulStop: '30s',
        },
    },
};

const client = new grpc.Client();
client.load(testConfig.protoDirs, testConfig.protoFile);

const allowedCounter = new Counter('grpc_allowed');
const overLimitCounter = new Counter('grpc_over_limit');
const errorCounter = new Counter('grpc_error');

// Contadores específicos para facilitar análise no resultado JSON.
const grpcStatusErrorCounter = new Counter('grpc_status_error');
const unknownOverallCodeCounter = new Counter('grpc_unknown_overall_code');
const invokeExceptionCounter = new Counter('grpc_invoke_exception');

const requestPayload = JSON.parse(open(testConfig.payloadFile));

let debugLogsPrinted = 0;

function debugError(reason, details) {
    if (!testConfig.debugErrors) {
        return;
    }

    if (debugLogsPrinted >= testConfig.maxDebugLogsPerVu) {
        return;
    }

    debugLogsPrinted += 1;

    console.error(JSON.stringify({
        reason,
        vu: __VU,
        iter: __ITER,
        details,
    }));
}

export default function () {
    if (__ITER === 0) {
        try {
            client.connect(testConfig.grpcHost, {
                plaintext: true,
            });
        } catch (error) {
            errorCounter.add(1, { reason: 'connect_exception' });
            invokeExceptionCounter.add(1, { reason: 'connect_exception' });

            debugError('connect_exception', {
                message: String(error),
                host: testConfig.grpcHost,
            });

            return;
        }
    }

    let response;

    try {
        response = client.invoke(testConfig.grpcMethod, requestPayload);
    } catch (error) {
        errorCounter.add(1, { reason: 'invoke_exception' });
        invokeExceptionCounter.add(1, { reason: 'invoke_exception' });

        debugError('invoke_exception', {
            message: String(error),
            method: testConfig.grpcMethod,
            host: testConfig.grpcHost,
        });

        return;
    }

    if (!response) {
        errorCounter.add(1, { reason: 'empty_response' });

        debugError('empty_response', {
            response,
        });

        return;
    }

    if (response.status !== grpc.StatusOK) {
        const statusName = grpcStatusName(response.status);

        errorCounter.add(1, { reason: 'grpc_status_not_ok', status: statusName });
        grpcStatusErrorCounter.add(1, { status: statusName });

        debugError('grpc_status_not_ok', {
            status: response.status,
            statusName,
            error: response.error,
            message: response.message,
            headers: response.headers,
            trailers: response.trailers,
        });

        return;
    }

    const message = response.message || {};
    const overallCode = message.overallCode;

    if (overallCode === 0 || overallCode === 'OK') {
        allowedCounter.add(1);
        return;
    }

    if (overallCode === 1 || overallCode === 'OVER_LIMIT') {
        overLimitCounter.add(1);
        return;
    }

    errorCounter.add(1, {
        reason: 'unknown_overall_code',
        overallCode: String(overallCode),
    });

    unknownOverallCodeCounter.add(1, {
        overallCode: String(overallCode),
    });

    debugError('unknown_overall_code', {
        overallCode,
        message,
        responseStatus: response.status,
    });
}

function grpcStatusName(status) {
    switch (status) {
        case grpc.StatusOK:
            return 'OK';
        case grpc.StatusCanceled:
            return 'CANCELED';
        case grpc.StatusUnknown:
            return 'UNKNOWN';
        case grpc.StatusInvalidArgument:
            return 'INVALID_ARGUMENT';
        case grpc.StatusDeadlineExceeded:
            return 'DEADLINE_EXCEEDED';
        case grpc.StatusNotFound:
            return 'NOT_FOUND';
        case grpc.StatusAlreadyExists:
            return 'ALREADY_EXISTS';
        case grpc.StatusPermissionDenied:
            return 'PERMISSION_DENIED';
        case grpc.StatusResourceExhausted:
            return 'RESOURCE_EXHAUSTED';
        case grpc.StatusFailedPrecondition:
            return 'FAILED_PRECONDITION';
        case grpc.StatusAborted:
            return 'ABORTED';
        case grpc.StatusOutOfRange:
            return 'OUT_OF_RANGE';
        case grpc.StatusUnimplemented:
            return 'UNIMPLEMENTED';
        case grpc.StatusInternal:
            return 'INTERNAL';
        case grpc.StatusUnavailable:
            return 'UNAVAILABLE';
        case grpc.StatusDataLoss:
            return 'DATA_LOSS';
        case grpc.StatusUnauthenticated:
            return 'UNAUTHENTICATED';
        default:
            return `UNKNOWN_STATUS_${status}`;
    }
}
