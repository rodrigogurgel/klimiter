package io.github.rodrigogurgel.klimiter.adapter.inbound.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuShedServerInterceptorTest {
    @Test
    fun `shouldShed cuts at or above threshold`() {
        assertTrue(shouldShed(load = 0.90, threshold = 0.90)) // igual ao limiar já corta
        assertTrue(shouldShed(load = 0.95, threshold = 0.90))
    }

    @Test
    fun `shouldShed passes below threshold`() {
        assertFalse(shouldShed(load = 0.89, threshold = 0.90))
    }

    @Test
    fun `shouldShed fails open on invalid load`() {
        assertFalse(shouldShed(load = -1.0, threshold = 0.0)) // leitura inválida nunca corta, mesmo com limiar 0
    }

    @Test
    fun `sheds above threshold closing UNAVAILABLE and counting`() {
        val call = mockk<ServerCall<Any, Any>>(relaxed = true)
        val next = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
        var shed = 0
        val interceptor = CpuShedServerInterceptor(threshold = 0.85, load = { 0.95 }, onShed = { shed++ })

        interceptor.interceptCall(call, Metadata(), next)

        val status = slot<Status>()
        verify { call.close(capture(status), any()) }
        assertEquals(Status.Code.UNAVAILABLE, status.captured.code)
        verify(exactly = 0) { next.startCall(any(), any()) } // curto-circuito: handler não é iniciado
        assertEquals(1, shed)
    }

    @Test
    fun `delegates below threshold without counting`() {
        val call = mockk<ServerCall<Any, Any>>(relaxed = true)
        val next = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
        var shed = 0
        val interceptor = CpuShedServerInterceptor(threshold = 0.85, load = { 0.10 }, onShed = { shed++ })

        interceptor.interceptCall(call, Metadata(), next)

        verify { next.startCall(call, any()) }
        verify(exactly = 0) { call.close(any(), any()) }
        assertEquals(0, shed)
    }
}
