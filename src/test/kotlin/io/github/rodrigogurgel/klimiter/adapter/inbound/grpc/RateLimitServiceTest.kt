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
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import io.github.rodrigogurgel.klimiter.grpc.v1.Priority as ProtoPriority
import io.github.rodrigogurgel.klimiter.grpc.v1.Status as ProtoStatus
import io.grpc.Status as GrpcStatus

class RateLimitServiceTest {
    private val useCaseNeverCalled = object : EvaluateUseCase {
        override suspend fun evaluate(batch: Batch): BatchResult = fail("o core não deve ser tocado")
    }

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

        val response = RateLimitService(useCase, maxBatchSize = 100).shouldRateLimit(request)

        assertEquals("user_id", seen?.requests?.single()?.dimension?.raw) // request foi mapeado p/ o domínio
        assertEquals(ProtoStatus.STATUS_ALLOWED, response.overallStatus)
        assertEquals(5L, response.decisionsList.single().remaining)
    }

    @Test
    fun `a descriptor with a blank key is rejected with INVALID_ARGUMENT before reaching the core`() = runBlocking {
        val request = RateLimitRequest.newBuilder()
            .addDescriptors(RateLimitDescriptor.newBuilder().setKey("").setValue("u1").setHits(1))
            .build()

        val failure = assertFailsWith<StatusException> {
            RateLimitService(useCaseNeverCalled, maxBatchSize = 100).shouldRateLimit(request)
        }
        assertEquals(GrpcStatus.Code.INVALID_ARGUMENT, failure.status.code)
    }

    @Test
    fun `a batch larger than the cap is rejected with INVALID_ARGUMENT before reaching the core`() = runBlocking {
        val builder = RateLimitRequest.newBuilder()
        repeat(3) { index ->
            builder.addDescriptors(
                RateLimitDescriptor.newBuilder().setKey("user_id").setValue("u$index").setHits(1),
            )
        }

        val failure = assertFailsWith<StatusException> {
            RateLimitService(useCaseNeverCalled, maxBatchSize = 2).shouldRateLimit(builder.build())
        }
        assertEquals(GrpcStatus.Code.INVALID_ARGUMENT, failure.status.code)
    }
}
