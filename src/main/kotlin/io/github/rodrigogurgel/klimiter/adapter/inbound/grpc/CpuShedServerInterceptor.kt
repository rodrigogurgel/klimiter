package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/**
 * Portão de *load shedding* por carga de CPU (§ short-circuit): fecha a chamada com [shedStatus]
 * antes de iniciar o handler quando a carga amostrada cruza [threshold], protegendo o hot path de
 * saturar a CPU. Não faz backpressure — rejeita, deixando o cliente gRPC aplicar retry/backoff.
 *
 * A carga é lida de [load] (`[0.0, 1.0]`, cacheada fora do hot path — ver amostrador no wiring), de
 * modo que o interceptor não chama o MXBean por request. Leituras inválidas (`< 0`) não cortam
 * (*fail open*). Cada corte dispara [onShed] (telemetria).
 */
class CpuShedServerInterceptor(
    private val threshold: Double,
    private val load: () -> Double,
    private val onShed: () -> Unit = {},
    private val shedStatus: Status = Status.UNAVAILABLE.withDescription("cpu shed"),
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (shouldShed(load(), threshold)) {
            onShed()
            call.close(shedStatus, Metadata())
            // Chamada já encerrada: listener inerte que ignora as mensagens de entrada.
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }
}

/**
 * Decide o corte por CPU: corta quando a [load] é válida (`>= 0`) e atinge o [threshold]. Leituras
 * inválidas (`< 0`, ex.: JVM sem suporte a `getProcessCpuLoad`) nunca cortam. Extraído para teste
 * sem instanciar o interceptor.
 */
internal fun shouldShed(load: Double, threshold: Double): Boolean = load >= 0.0 && load >= threshold
