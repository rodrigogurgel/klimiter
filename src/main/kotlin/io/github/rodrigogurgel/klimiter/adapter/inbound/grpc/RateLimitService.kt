package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitResponse
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitServiceGrpcKt
import io.grpc.StatusException
import io.micrometer.core.instrument.kotlin.currentObservation
import io.micrometer.observation.Observation
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import com.google.protobuf.Duration as ProtoDuration
import io.grpc.Status as GrpcStatus

/**
 * Adapter de entrada gRPC (§7): handler `suspend` (modelo corrotina-por-request) que delega ao
 * [EvaluateUseCase]. O cancelamento do RPC propaga para as reservas pela estrutura concurrency
 * (§7.5). A tradução proto↔domínio fica no [Mapping] (R2). É registrado como bean no wiring (Fase 7);
 * o Spring gRPC associa cada bean `BindableService` ao servidor.
 *
 * Entrada inválida — lote maior que [maxBatchSize] ou descriptor que viola os invariantes dos Value
 * Objects (ex.: `key` vazia) — é rejeitada aqui com `INVALID_ARGUMENT`, antes de tocar o core.
 *
 * A resposta é anexada como atributos ao span `grpc.server` da chamada (OBSERVABILIDADE.md §2.1): a
 * `Observation` chega ao handler pelo `CoroutineContext` (o Spring gRPC registra o
 * `ObservationCoroutineContextServerInterceptor` quando há stubs corrotina).
 */
class RateLimitService(private val useCase: EvaluateUseCase, private val maxBatchSize: Int) :
    RateLimitServiceGrpcKt.RateLimitServiceCoroutineImplBase() {
    override suspend fun shouldRateLimit(request: RateLimitRequest): RateLimitResponse {
        if (request.descriptorsCount > maxBatchSize) {
            throw invalidArgument("lote com ${request.descriptorsCount} descriptors excede o máximo de $maxBatchSize")
        }
        val batch = try {
            Mapping.toBatch(request)
        } catch (invalid: IllegalArgumentException) {
            throw invalidArgument(invalid.message ?: "descriptor inválido", cause = invalid)
        }
        val response = Mapping.toResponse(useCase.evaluate(batch))
        currentCoroutineContext().currentObservation()?.tagResponse(request, response)
        return response
    }

    /**
     * Anexa a resposta ao span da chamada, uma família de atributos por decisão —
     * `klimiter.response.decisions.<dimensão>.{status,remaining,reset_after,capacity}` — mais o
     * veredito coletivo. A dimensão vem do descriptor correspondente do request (as decisões
     * respondem na mesma ordem, §2.1); dimensão repetida no lote ganha sufixo de ocorrência
     * (`user_id`, `user_id.2`, …) para nenhuma decisão sobrescrever outra. Key-values de **alta**
     * cardinalidade viram atributos do span, nunca tags do timer `grpc.server` (a cardinalidade
     * das métricas fica intacta). Sem `value` de tráfego (sem PII): só a dimensão nomeia a decisão.
     */
    private fun Observation.tagResponse(request: RateLimitRequest, response: RateLimitResponse) {
        highCardinalityKeyValue("klimiter.response.overall_status", tagValue(response.overallStatus))
        val occurrences = HashMap<String, Int>()
        response.decisionsList.forEachIndexed { index, decision ->
            val dimension = request.getDescriptors(index).key
            val occurrence = occurrences.merge(dimension, 1, Int::plus)
            val prefix = "klimiter.response.decisions.$dimension" +
                if (occurrence == 1) "" else ".$occurrence"
            highCardinalityKeyValue("$prefix.status", tagValue(decision.status))
            highCardinalityKeyValue("$prefix.remaining", decision.remaining.toString())
            highCardinalityKeyValue("$prefix.reset_after", decision.resetAfter.toReadable())
            highCardinalityKeyValue("$prefix.capacity", decision.capacity.toString())
        }
    }

    /** `STATUS_ALLOWED` → `allowed`: alinhado aos valores da tag `status` das métricas (§1.3). */
    private fun tagValue(status: io.github.rodrigogurgel.klimiter.grpc.v1.Status): String =
        status.name.removePrefix("STATUS_").lowercase()

    /** Proto `Duration` → texto legível (`1s`, `30.5s`) — o mesmo formato de duração do Kotlin. */
    private fun ProtoDuration.toReadable(): String = (seconds.seconds + nanos.nanoseconds).toString()

    private fun invalidArgument(description: String, cause: Throwable? = null): StatusException =
        StatusException(GrpcStatus.INVALID_ARGUMENT.withDescription(description).withCause(cause))
}
