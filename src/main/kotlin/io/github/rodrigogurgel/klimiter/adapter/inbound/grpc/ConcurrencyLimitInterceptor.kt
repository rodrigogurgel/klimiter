package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitServiceGrpc
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.micrometer.core.instrument.Counter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controle de admissão (load shedding) do hot path gRPC: limita as chamadas `ShouldRateLimit`
 * **simultâneas** ao teto de [limit] (fixo ou adaptativo). Acima do teto, recusa na hora com
 * `RESOURCE_EXHAUSTED` — sem enfileirar, sem tocar o Redis nem criar corrotina — mantendo o conjunto
 * in-flight pequeno o bastante para os admitidos baterem o SLO (degradação graciosa, não colapso).
 *
 * Só protege o serviço de rate-limit ([RateLimitServiceGrpc.SERVICE_NAME]); **health e reflection
 * passam SEMPRE** — senão a probe do Kubernetes seria recusada sob carga e o pod morreria.
 *
 * No término de um admitido alimenta a [limit] com a sojourn latency (sucesso amostra; cancelamento
 * só libera o slot — cliente que desiste não é sinal de latência). Per-pod, sem estado entre réplicas
 * → scale-invariant.
 */
class ConcurrencyLimitInterceptor(private val limit: ConcurrencyLimit, private val shed: Counter) : ServerInterceptor {
    private val inFlightCount = AtomicInteger(0)

    /** Chamadas em voo agora. Alimenta o gauge `klimiter.admission.inflight`. */
    fun inFlight(): Int = inFlightCount.get()

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        // Só o rate-limit é gateado; o resto (health/reflection) nunca é shedado.
        if (call.methodDescriptor.serviceName != GATED_SERVICE) {
            return next.startCall(call, headers)
        }
        return if (inFlightCount.incrementAndGet() <= limit.current) admit(call, headers, next) else reject(call)
    }

    /** Acima do teto: devolve o slot, recusa na hora, sem rodar o handler (nem Redis nem corrotina). */
    private fun <ReqT : Any, RespT : Any> reject(call: ServerCall<ReqT, RespT>): ServerCall.Listener<ReqT> {
        inFlightCount.decrementAndGet()
        shed.increment()
        call.close(OVERLOADED, Metadata())
        return object : ServerCall.Listener<ReqT>() {} // listener inerte: a chamada já foi fechada
    }

    /** Admitido: roda o handler e, no estado TERMINAL (uma só vez), libera o slot e amostra a latência. */
    @Suppress("TooGenericExceptionCaught") // qualquer falha SÍNCRONA do startCall precisa devolver o slot
    private fun <ReqT : Any, RespT : Any> admit(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val startNanos = System.nanoTime()
        val settled = AtomicBoolean(false)
        val finish = fun(sample: Boolean) {
            if (!settled.compareAndSet(false, true)) return
            val concurrency = inFlightCount.getAndDecrement() // valor ANTES do decremento = in-flight com esta
            if (sample) {
                val rttMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                limit.observe(rttMillis, concurrency, System.currentTimeMillis())
            }
        }
        return try {
            object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                next.startCall(call, headers),
            ) {
                override fun onComplete() {
                    try {
                        super.onComplete()
                    } finally {
                        finish(true) // sucesso → amostra a latência
                    }
                }

                override fun onCancel() {
                    try {
                        super.onCancel()
                    } finally {
                        finish(false) // cancelamento → só libera o slot
                    }
                }
            }
        } catch (e: Throwable) {
            finish(false) // startCall lançou síncrono → não vaza o slot
            throw e
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val GATED_SERVICE: String = RateLimitServiceGrpc.SERVICE_NAME
        val OVERLOADED: Status =
            Status.RESOURCE_EXHAUSTED.withDescription("klimiter overloaded: admission limit reached")
    }
}
