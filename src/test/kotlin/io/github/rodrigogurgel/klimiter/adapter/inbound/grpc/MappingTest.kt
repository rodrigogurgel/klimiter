package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.Priority
import io.github.rodrigogurgel.klimiter.core.domain.Remaining
import io.github.rodrigogurgel.klimiter.core.domain.ReportedCapacity
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitDescriptor
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.github.rodrigogurgel.klimiter.grpc.v1.Priority as ProtoPriority
import io.github.rodrigogurgel.klimiter.grpc.v1.Status as ProtoStatus

class MappingTest {
    @Test
    fun `toBatch maps descriptors to domain requests`() {
        val request = RateLimitRequest.newBuilder()
            .addDescriptors(
                RateLimitDescriptor.newBuilder()
                    .setKey("user_id").setValue("u1").setHits(2).setPriority(ProtoPriority.PRIORITY_LOW),
            )
            .build()
        val req = Mapping.toBatch(request).requests.single()
        assertEquals("user_id", req.dimension.raw)
        assertEquals("u1", req.value.raw)
        assertEquals(2L, req.hits.value)
        assertEquals(Priority.LOW, req.priority)
    }

    @Test
    fun `unspecified priority maps to HIGH`() {
        val request = RateLimitRequest.newBuilder()
            .addDescriptors(RateLimitDescriptor.newBuilder().setKey("k").setValue("v"))
            .build()
        assertEquals(Priority.HIGH, Mapping.toBatch(request).requests.single().priority)
    }

    @Test
    fun `toResponse maps overall status and per-item decisions including reset_after`() {
        val decision = Decision(Status.DENIED, Remaining(7), 3.seconds + 500.milliseconds, ReportedCapacity(100))
        val response = Mapping.toResponse(BatchResult(Status.DENIED, listOf(decision)))
        assertEquals(ProtoStatus.STATUS_DENIED, response.overallStatus)
        val mapped = response.decisionsList.single()
        assertEquals(ProtoStatus.STATUS_DENIED, mapped.status)
        assertEquals(7L, mapped.remaining)
        assertEquals(100L, mapped.capacity)
        assertEquals(3L, mapped.resetAfter.seconds)
        assertEquals(500_000_000, mapped.resetAfter.nanos)
    }
}
