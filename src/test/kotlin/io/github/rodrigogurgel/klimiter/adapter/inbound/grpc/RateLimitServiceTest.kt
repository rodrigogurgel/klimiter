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
import io.micrometer.core.instrument.kotlin.asContextElement
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    fun `attaches the response to the current observation as per-decision span attributes`() = runBlocking {
        val useCase = object : EvaluateUseCase {
            override suspend fun evaluate(batch: Batch): BatchResult = BatchResult(
                overall = Status.DENIED,
                decisions = listOf(
                    Decision(Status.ALLOWED, Remaining(5), 1.seconds, ReportedCapacity(10)),
                    Decision(Status.DENIED, Remaining.NONE, 30.seconds, ReportedCapacity(100)),
                    Decision(Status.DENIED, Remaining.NONE, 30.seconds, ReportedCapacity(100)),
                ),
            )
        }
        val request = RateLimitRequest.newBuilder()
            .addDescriptors(descriptor("ip", "1.2.3.4"))
            .addDescriptors(descriptor("user_id", "u1"))
            .addDescriptors(descriptor("user_id", "u2")) // dimensão repetida → sufixo de ocorrência
            .build()
        // Registry com um handler qualquer: sem handler toda Observation vira NOOP e nada é gravado.
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(ObservationHandler<Observation.Context> { true })
        val observation = Observation.start("grpc.server", registry)

        try {
            // Mesmo mecanismo do ObservationCoroutineContextServerInterceptor: escopo → CoroutineContext.
            observation.openScope().use {
                withContext(registry.asContextElement()) {
                    RateLimitService(useCase, maxBatchSize = 100).shouldRateLimit(request)
                }
            }
        } finally {
            observation.stop()
        }

        val attributes = observation.context.highCardinalityKeyValues.associate { it.key to it.value }
        assertEquals("denied", attributes["klimiter.response.overall_status"])
        assertEquals("allowed", attributes["klimiter.response.decisions.ip.status"])
        assertEquals("5", attributes["klimiter.response.decisions.ip.remaining"])
        assertEquals("1s", attributes["klimiter.response.decisions.ip.reset_after"])
        assertEquals("10", attributes["klimiter.response.decisions.ip.capacity"])
        assertEquals("denied", attributes["klimiter.response.decisions.user_id.status"])
        assertEquals("30s", attributes["klimiter.response.decisions.user_id.reset_after"])
        assertEquals("100", attributes["klimiter.response.decisions.user_id.capacity"])
        // A segunda ocorrência de user_id não sobrescreve a primeira.
        assertEquals("denied", attributes["klimiter.response.decisions.user_id.2.status"])
        assertEquals("0", attributes["klimiter.response.decisions.user_id.2.remaining"])
    }

    private fun descriptor(key: String, value: String): RateLimitDescriptor.Builder = RateLimitDescriptor.newBuilder()
        .setKey(key).setValue(value).setHits(1).setPriority(ProtoPriority.PRIORITY_HIGH)

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
