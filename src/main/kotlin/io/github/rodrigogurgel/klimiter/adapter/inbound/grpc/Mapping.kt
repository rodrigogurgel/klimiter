package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Hits
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Request
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitDecision
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitDescriptor
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitResponse
import kotlin.time.Duration
import com.google.protobuf.Duration as ProtoDuration
import io.github.rodrigogurgel.klimiter.grpc.v1.Priority as ProtoPriority
import io.github.rodrigogurgel.klimiter.grpc.v1.Status as ProtoStatus

/**
 * Tradução, proto ↔ domínio (R2 da ARQUITETURA.md: "o contrato externo fica na borda"). O core nunca
 * vê os tipos gerados; toda a conversão vive aqui, em `adapter.inbound.grpc`.
 */
object Mapping {
    fun toBatch(request: RateLimitRequest): Batch = Batch(request.descriptorsList.map(::toRequest))

    private fun toRequest(descriptor: RateLimitDescriptor): Request = Request(
        dimension = Dimension(descriptor.key),
        value = DimensionValue(descriptor.value),
        hits = Hits(descriptor.hits.toUInt().toLong()), // proto uint32 → Long sem sinal
        priority = toPriority(descriptor.priority),
    )

    /** HIGH é o caminho primário; `UNSPECIFIED`/`UNRECOGNIZED` caem nele. */
    private fun toPriority(priority: ProtoPriority): Priority =
        if (priority == ProtoPriority.PRIORITY_LOW) Priority.LOW else Priority.HIGH

    fun toResponse(result: BatchResult): RateLimitResponse = RateLimitResponse.newBuilder()
        .setOverallStatus(toProtoStatus(result.overall))
        .addAllDecisions(result.decisions.map(::toProtoDecision))
        .build()

    private fun toProtoDecision(decision: Decision): RateLimitDecision = RateLimitDecision.newBuilder()
        .setStatus(toProtoStatus(decision.status))
        .setRemaining(decision.remaining.value)
        .setResetAfter(decision.resetAfter.toProtoDuration())
        .setCapacity(decision.capacity.value)
        .build()

    private fun toProtoStatus(status: Status): ProtoStatus = when (status) {
        Status.ALLOWED -> ProtoStatus.STATUS_ALLOWED
        Status.DENIED -> ProtoStatus.STATUS_DENIED
        Status.UNKNOWN -> ProtoStatus.STATUS_UNKNOWN
    }

    private fun Duration.toProtoDuration(): ProtoDuration = toComponents { seconds, nanoseconds ->
        ProtoDuration.newBuilder().setSeconds(seconds).setNanos(nanoseconds).build()
    }
}
