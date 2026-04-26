package io.klimiter.service.adapter.input.grpc

import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.generated.service.proto.RateLimitDescriptor
import io.klimiter.generated.service.proto.RateLimitRequest
import io.klimiter.generated.service.proto.RateLimitResponse
import io.klimiter.service.domain.port.input.CheckRateLimitUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.klimiter.core.api.rls.RateLimitRequest as CoreRateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse as CoreRateLimitResponse

class RateLimitGrpcAdapterTest {

    private fun validProtoRequest(): RateLimitRequest = RateLimitRequest.newBuilder()
        .setDomain("api")
        .addDescriptors(
            RateLimitDescriptor.newBuilder()
                .addEntries(RateLimitDescriptor.Entry.newBuilder().setKey("user").setValue("alice")),
        )
        .build()

    @Test
    fun `shouldRateLimit returns proto OK on successful use case call`() = runTest {
        val useCase = object : CheckRateLimitUseCase {
            override suspend fun check(request: CoreRateLimitRequest): CoreRateLimitResponse =
                CoreRateLimitResponse(RateLimitCode.OK)
        }
        val response = RateLimitGrpcAdapter(useCase).shouldRateLimit(validProtoRequest())
        assertEquals(RateLimitResponse.Code.OK, response.overallCode)
    }

    @Test
    fun `shouldRateLimit returns UNKNOWN on use case exception`() = runTest {
        data class UnknownRuntimeException(override val message: String) : RuntimeException(message)
        val useCase = object : CheckRateLimitUseCase {
            override suspend fun check(request: CoreRateLimitRequest): CoreRateLimitResponse =
                throw UnknownRuntimeException("simulated failure")
        }
        val response = RateLimitGrpcAdapter(useCase).shouldRateLimit(validProtoRequest())
        assertEquals(RateLimitResponse.Code.UNKNOWN, response.overallCode)
    }

    @Test
    fun `shouldRateLimit returns UNKNOWN when proto request maps to invalid core request`() = runTest {
        val useCase = object : CheckRateLimitUseCase {
            override suspend fun check(request: CoreRateLimitRequest): CoreRateLimitResponse =
                CoreRateLimitResponse(RateLimitCode.OK)
        }
        // Empty domain causes require(domain.isNotBlank()) to throw inside toCoreRequest()
        val invalidRequest = RateLimitRequest.newBuilder().setDomain("").build()
        val response = RateLimitGrpcAdapter(useCase).shouldRateLimit(invalidRequest)
        assertEquals(RateLimitResponse.Code.UNKNOWN, response.overallCode)
    }
}
