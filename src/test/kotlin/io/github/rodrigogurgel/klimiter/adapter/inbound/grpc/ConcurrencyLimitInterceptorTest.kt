package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.github.rodrigogurgel.klimiter.grpc.v1.RateLimitServiceGrpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConcurrencyLimitInterceptorTest {
    private val registry = SimpleMeterRegistry()
    private val shed: Counter = registry.counter("klimiter.admission.shed")

    /** [ConcurrencyLimit] de teto fixo que grava as latências amostradas (verifica o sinal ao controlador). */
    private class RecordingLimit(override val current: Int) : ConcurrencyLimit {
        val samples = mutableListOf<Long>()
        override fun observe(rttMillis: Long, inFlight: Int, nowMillis: Long) {
            samples += rttMillis
        }
    }

    private fun call(service: String = RateLimitServiceGrpc.SERVICE_NAME): ServerCall<Any, Any> {
        val descriptor = mockk<MethodDescriptor<Any, Any>>()
        every { descriptor.serviceName } returns service
        return mockk<ServerCall<Any, Any>>(relaxed = true).also { every { it.methodDescriptor } returns descriptor }
    }

    private fun handler(downstream: ServerCall.Listener<Any> = mockk(relaxed = true)): ServerCallHandler<Any, Any> =
        mockk<ServerCallHandler<Any, Any>>().also { every { it.startCall(any(), any()) } returns downstream }

    @Test
    fun `admits and forwards when under the limit`() {
        val interceptor = ConcurrencyLimitInterceptor(FixedConcurrencyLimit(1), shed)
        val handler = handler()

        interceptor.interceptCall(call(), Metadata(), handler)

        verify(exactly = 1) { handler.startCall(any(), any()) }
        assertEquals(0.0, shed.count())
        assertEquals(1, interceptor.inFlight())
    }

    @Test
    fun `sheds with RESOURCE_EXHAUSTED when the limit is reached`() {
        val interceptor = ConcurrencyLimitInterceptor(FixedConcurrencyLimit(1), shed)
        interceptor.interceptCall(call(), Metadata(), handler()) // toma o único slot (não completa)

        val rejected = call()
        val handler = handler()
        interceptor.interceptCall(rejected, Metadata(), handler)

        val status = slot<Status>()
        verify { rejected.close(capture(status), any()) }
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, status.captured.code)
        verify(exactly = 0) { handler.startCall(any(), any()) } // handler NÃO roda → sem Redis/corrotina
        assertEquals(1.0, shed.count())
        assertEquals(1, interceptor.inFlight()) // o reject devolveu o slot; só o admitido segue em voo
    }

    @Test
    fun `onComplete samples the latency and releases the slot`() {
        val limit = RecordingLimit(1)
        val interceptor = ConcurrencyLimitInterceptor(limit, shed)
        val listener = interceptor.interceptCall(call(), Metadata(), handler())

        listener.onComplete()

        assertEquals(1, limit.samples.size) // sucesso → amostrou
        assertEquals(0, interceptor.inFlight())
    }

    @Test
    fun `onCancel releases the slot without sampling`() {
        val limit = RecordingLimit(1)
        val interceptor = ConcurrencyLimitInterceptor(limit, shed)
        val listener = interceptor.interceptCall(call(), Metadata(), handler())

        listener.onCancel()

        assertEquals(0, limit.samples.size) // cancelamento → não amostra
        assertEquals(0, interceptor.inFlight())
    }

    @Test
    fun `terminal release is idempotent across complete and cancel`() {
        val limit = RecordingLimit(1)
        val interceptor = ConcurrencyLimitInterceptor(limit, shed)
        val listener = interceptor.interceptCall(call(), Metadata(), handler())

        listener.onComplete()
        listener.onCancel() // segundo terminal não deve liberar um slot extra nem reamostrar

        assertEquals(1, limit.samples.size)
        assertEquals(0, interceptor.inFlight())
    }

    @Test
    fun `health and other services pass without consuming a slot`() {
        val interceptor = ConcurrencyLimitInterceptor(FixedConcurrencyLimit(1), shed)
        interceptor.interceptCall(call(), Metadata(), handler()) // esgota o único slot do rate-limit

        val health = call(service = "grpc.health.v1.Health")
        val handler = handler()
        interceptor.interceptCall(health, Metadata(), handler)

        verify(exactly = 1) { handler.startCall(any(), any()) } // passou
        verify(exactly = 0) { health.close(any(), any()) } // não foi shedada
        assertEquals(0.0, shed.count())
    }
}
