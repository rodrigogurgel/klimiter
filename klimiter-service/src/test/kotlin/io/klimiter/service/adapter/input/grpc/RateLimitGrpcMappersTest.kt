package io.klimiter.service.adapter.input.grpc

import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitResponse as CoreRateLimitResponse
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.generated.service.proto.RateLimitDescriptor
import io.klimiter.generated.service.proto.RateLimitOverride
import io.klimiter.generated.service.proto.RateLimitRequest
import io.klimiter.generated.service.proto.RateLimitResponse
import io.klimiter.generated.service.proto.RateLimitUnit
import io.klimiter.generated.service.proto.UInt64Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class RateLimitGrpcMappersTest {

    // ──────────────── toCoreRequest ────────────────

    @Test
    fun `toCoreRequest uses 1 when hitsAddend is zero`() {
        val req = RateLimitRequest.newBuilder()
            .setDomain("api")
            .addDescriptors(descriptor("k", "v"))
            .setHitsAddend(0)
            .build()
        assertEquals(1, req.toCoreRequest().hitsAddend)
    }

    @Test
    fun `toCoreRequest uses explicit hitsAddend when positive`() {
        val req = RateLimitRequest.newBuilder()
            .setDomain("api")
            .addDescriptors(descriptor("k", "v"))
            .setHitsAddend(3)
            .build()
        assertEquals(3, req.toCoreRequest().hitsAddend)
    }

    // ──────────────── toCoreDescriptor ────────────────

    @Test
    fun `toCoreDescriptor maps entries`() {
        val desc = descriptor("user", "alice").build()
        val core = desc.toCoreDescriptor()
        assertEquals(1, core.entries.size)
        assertEquals("user", core.entries[0].key)
        assertEquals("alice", core.entries[0].value)
    }

    @Test
    fun `toCoreDescriptor limit is null when not set`() {
        val desc = descriptor("k", "v").build()
        assertNull(desc.toCoreDescriptor().limit)
    }

    @Test
    fun `toCoreDescriptor limit is mapped when set`() {
        val desc = descriptor("k", "v")
            .setLimit(RateLimitOverride.newBuilder().setRequestsPerUnit(10).setUnit(RateLimitUnit.MINUTE))
            .build()
        val core = desc.toCoreDescriptor()
        assertEquals(10, core.limit!!.requestsPerUnit)
        assertEquals(RateLimitTimeUnit.MINUTE, core.limit!!.unit)
    }

    @Test
    fun `toCoreDescriptor hitsAddend is null when not set`() {
        val desc = descriptor("k", "v").build()
        assertNull(desc.toCoreDescriptor().hitsAddend)
    }

    @Test
    fun `toCoreDescriptor hitsAddend is mapped when set`() {
        val desc = descriptor("k", "v")
            .setHitsAddend(UInt64Value.newBuilder().setValue(7L))
            .build()
        assertEquals(7L, desc.toCoreDescriptor().hitsAddend)
    }

    // ──────────────── RateLimitUnit.toCoreTimeUnit ────────────────

    @Test
    fun `toCoreTimeUnit maps all RateLimitUnit values`() {
        val mapping = mapOf(
            RateLimitUnit.SECOND to RateLimitTimeUnit.SECOND,
            RateLimitUnit.MINUTE to RateLimitTimeUnit.MINUTE,
            RateLimitUnit.HOUR to RateLimitTimeUnit.HOUR,
            RateLimitUnit.DAY to RateLimitTimeUnit.DAY,
            RateLimitUnit.MONTH to RateLimitTimeUnit.MONTH,
            RateLimitUnit.YEAR to RateLimitTimeUnit.YEAR,
            RateLimitUnit.WEEK to RateLimitTimeUnit.WEEK,
            RateLimitUnit.RATE_LIMIT_UNIT_UNKNOWN to RateLimitTimeUnit.UNKNOWN,
            RateLimitUnit.UNRECOGNIZED to RateLimitTimeUnit.UNKNOWN,
        )
        for ((proto, core) in mapping) {
            assertEquals(core, proto.toCoreTimeUnit(), "Expected $core for $proto")
        }
    }

    // ──────────────── RateLimitCode.toProtoCode ────────────────

    @Test
    fun `toProtoCode maps all RateLimitCode values`() {
        assertEquals(RateLimitResponse.Code.OK, RateLimitCode.OK.toProtoCode())
        assertEquals(RateLimitResponse.Code.OVER_LIMIT, RateLimitCode.OVER_LIMIT.toProtoCode())
        assertEquals(RateLimitResponse.Code.UNKNOWN, RateLimitCode.UNKNOWN.toProtoCode())
    }

    // ──────────────── RateLimitTimeUnit.toProtoRateLimitUnit ────────────────

    @Test
    fun `toProtoRateLimitUnit maps all RateLimitTimeUnit values`() {
        val mapping = mapOf(
            RateLimitTimeUnit.SECOND to RateLimitResponse.RateLimit.Unit.SECOND,
            RateLimitTimeUnit.MINUTE to RateLimitResponse.RateLimit.Unit.MINUTE,
            RateLimitTimeUnit.HOUR to RateLimitResponse.RateLimit.Unit.HOUR,
            RateLimitTimeUnit.DAY to RateLimitResponse.RateLimit.Unit.DAY,
            RateLimitTimeUnit.MONTH to RateLimitResponse.RateLimit.Unit.MONTH,
            RateLimitTimeUnit.YEAR to RateLimitResponse.RateLimit.Unit.YEAR,
            RateLimitTimeUnit.WEEK to RateLimitResponse.RateLimit.Unit.WEEK,
            RateLimitTimeUnit.UNKNOWN to RateLimitResponse.RateLimit.Unit.UNKNOWN,
        )
        for ((core, proto) in mapping) {
            assertEquals(proto, core.toProtoRateLimitUnit(), "Expected $proto for $core")
        }
    }

    // ──────────────── toProtoDescriptorStatus ────────────────

    @Test
    fun `toProtoDescriptorStatus maps code and limitRemaining`() {
        val status = RateLimitStatus(code = RateLimitCode.OK, limitRemaining = 42)
        val proto = status.toProtoDescriptorStatus()
        assertEquals(RateLimitResponse.Code.OK, proto.code)
        assertEquals(42, proto.limitRemaining)
    }

    @Test
    fun `toProtoDescriptorStatus sets currentLimit when present without name`() {
        val status = RateLimitStatus(
            code = RateLimitCode.OK,
            currentLimit = RateLimit(requestsPerUnit = 100, unit = RateLimitTimeUnit.SECOND),
        )
        val proto = status.toProtoDescriptorStatus()
        assertEquals(100, proto.currentLimit.requestsPerUnit)
        assertEquals(RateLimitResponse.RateLimit.Unit.SECOND, proto.currentLimit.unit)
        assertEquals("", proto.currentLimit.name)
    }

    @Test
    fun `toProtoDescriptorStatus sets currentLimit name when present`() {
        val status = RateLimitStatus(
            code = RateLimitCode.OK,
            currentLimit = RateLimit(requestsPerUnit = 50, unit = RateLimitTimeUnit.MINUTE, name = "my-rule"),
        )
        val proto = status.toProtoDescriptorStatus()
        assertEquals("my-rule", proto.currentLimit.name)
    }

    @Test
    fun `toProtoDescriptorStatus does not set currentLimit when absent`() {
        val status = RateLimitStatus(code = RateLimitCode.OK, currentLimit = null)
        val proto = status.toProtoDescriptorStatus()
        assertEquals(RateLimitResponse.RateLimit.getDefaultInstance(), proto.currentLimit)
    }

    @Test
    fun `toProtoDescriptorStatus sets durationUntilReset when present`() {
        val status = RateLimitStatus(code = RateLimitCode.OK, durationUntilReset = 1500.milliseconds)
        val proto = status.toProtoDescriptorStatus()
        assertEquals(1L, proto.durationUntilReset.seconds)
        assertEquals(500_000_000, proto.durationUntilReset.nanos)
    }

    @Test
    fun `toProtoDescriptorStatus does not set durationUntilReset when absent`() {
        val status = RateLimitStatus(code = RateLimitCode.OK, durationUntilReset = null)
        val proto = status.toProtoDescriptorStatus()
        assertEquals(com.google.protobuf.Duration.getDefaultInstance(), proto.durationUntilReset)
    }

    // ──────────────── toProtoResponse ────────────────

    @Test
    fun `toProtoResponse maps overall code and statuses`() {
        val coreResponse = CoreRateLimitResponse(
            overallCode = RateLimitCode.OVER_LIMIT,
            statuses = listOf(RateLimitStatus(code = RateLimitCode.OVER_LIMIT, limitRemaining = 0)),
        )
        val proto = coreResponse.toProtoResponse()
        assertEquals(RateLimitResponse.Code.OVER_LIMIT, proto.overallCode)
        assertEquals(1, proto.statusesCount)
    }

    // ──────────────── helpers ────────────────

    private fun descriptor(key: String, value: String): RateLimitDescriptor.Builder =
        RateLimitDescriptor.newBuilder()
            .addEntries(RateLimitDescriptor.Entry.newBuilder().setKey(key).setValue(value))
}
