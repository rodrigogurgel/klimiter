package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult
import io.github.rodrigogurgel.klimiter.core.domain.Decision
import io.github.rodrigogurgel.klimiter.core.domain.Remaining
import io.github.rodrigogurgel.klimiter.core.domain.ReportedCapacity
import io.github.rodrigogurgel.klimiter.core.domain.Status
import io.github.rodrigogurgel.klimiter.core.port.inbound.EvaluateUseCase
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitDescriptor
import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import io.github.rodrigogurgel.klimiter.grpc.v1.Priority as ProtoPriority
import io.github.rodrigogurgel.klimiter.grpc.v1.Status as ProtoStatus

class RateLimitServiceTest {
    @Test
    fun `delegates to the use case and maps request and response`() = runBlocking {
        val expected = BatchResult(
            overall = Status.ALLOWED,
            decisions = listOf(Decision(Status.ALLOWED, Remaining(5), 1.seconds, ReportedCapacity(10))),
        )
        var seen: Batch? = null
        val useCase = object : EvaluateUseCase {
            override suspend fun evaluate(batch: Batch): BatchResult {
                seen = batch
                return expected
            }
        }
        val request = RateLimitRequest.newBuilder()
            .addDescriptors(
                RateLimitDescriptor.newBuilder()
                    .setKey("user_id").setValue("u1").setHits(1).setPriority(ProtoPriority.PRIORITY_HIGH),
            )
            .build()

        val response = RateLimitService(useCase).shouldRateLimit(request)

        assertEquals("user_id", seen?.requests?.single()?.dimension?.raw) // request foi mapeado p/ o domínio
        assertEquals(ProtoStatus.STATUS_ALLOWED, response.overallStatus)
        assertEquals(5L, response.decisionsList.single().remaining)
    }
}
