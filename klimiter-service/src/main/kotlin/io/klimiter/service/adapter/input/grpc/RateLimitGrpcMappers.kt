package io.klimiter.service.adapter.input.grpc

import io.klimiter.core.api.config.RateLimitTimeUnit
import io.klimiter.core.api.rls.RateLimitCode
import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.api.rls.RateLimitOverride
import io.klimiter.core.api.rls.RateLimitRequestDescriptor
import io.klimiter.core.api.rls.RateLimitStatus
import io.klimiter.generated.service.proto.RateLimitDescriptor
import io.klimiter.generated.service.proto.RateLimitRequest
import io.klimiter.generated.service.proto.RateLimitResponse
import io.klimiter.generated.service.proto.RateLimitUnit
import io.klimiter.core.api.rls.RateLimitRequest as CoreRateLimitRequest
import io.klimiter.core.api.rls.RateLimitResponse as CoreRateLimitResponse
import io.klimiter.generated.service.proto.RateLimitOverride as ProtoRateLimitOverride

fun RateLimitRequest.toCoreRequest(): CoreRateLimitRequest = CoreRateLimitRequest(
    domain = domain,
    descriptors = descriptorsList.map { it.toCoreDescriptor() },
    hitsAddend = if (hitsAddend > 0) hitsAddend else 1,
)

fun RateLimitDescriptor.toCoreDescriptor(): RateLimitRequestDescriptor = RateLimitRequestDescriptor(
    entries = entriesList.map { RateLimitDescriptorEntry(key = it.key, value = it.value) },
    limit = if (hasLimit()) limit.toCoreOverride() else null,
    hitsAddend = if (hasHitsAddend()) hitsAddend.value else null,
)

fun ProtoRateLimitOverride.toCoreOverride(): RateLimitOverride = RateLimitOverride(
    requestsPerUnit = requestsPerUnit,
    unit = unit.toCoreTimeUnit(),
)

fun RateLimitUnit.toCoreTimeUnit(): RateLimitTimeUnit = when (this) {
    RateLimitUnit.SECOND -> RateLimitTimeUnit.SECOND
    RateLimitUnit.MINUTE -> RateLimitTimeUnit.MINUTE
    RateLimitUnit.HOUR -> RateLimitTimeUnit.HOUR
    RateLimitUnit.DAY -> RateLimitTimeUnit.DAY
    RateLimitUnit.MONTH -> RateLimitTimeUnit.MONTH
    RateLimitUnit.YEAR -> RateLimitTimeUnit.YEAR
    RateLimitUnit.WEEK -> RateLimitTimeUnit.WEEK
    RateLimitUnit.RATE_LIMIT_UNIT_UNKNOWN, RateLimitUnit.UNRECOGNIZED -> RateLimitTimeUnit.UNKNOWN
}

fun CoreRateLimitResponse.toProtoResponse(): RateLimitResponse = RateLimitResponse.newBuilder()
    .setOverallCode(overallCode.toProtoCode())
    .addAllStatuses(statuses.map { it.toProtoDescriptorStatus() })
    .build()

fun RateLimitStatus.toProtoDescriptorStatus(): RateLimitResponse.DescriptorStatus {
    val builder = RateLimitResponse.DescriptorStatus.newBuilder()
        .setCode(code.toProtoCode())
        .setLimitRemaining(limitRemaining)

    currentLimit?.let { limit ->
        builder.setCurrentLimit(
            RateLimitResponse.RateLimit.newBuilder()
                .setRequestsPerUnit(limit.requestsPerUnit)
                .setUnit(limit.unit.toProtoRateLimitUnit())
                .apply { limit.name?.let { setName(it) } }
                .build(),
        )
    }

    durationUntilReset?.let { dur ->
        builder.setDurationUntilReset(
            com.google.protobuf.Duration.newBuilder()
                .setSeconds(dur.toSeconds())
                .setNanos(dur.nano)
                .build(),
        )
    }

    return builder.build()
}

fun RateLimitCode.toProtoCode(): RateLimitResponse.Code = when (this) {
    RateLimitCode.OK -> RateLimitResponse.Code.OK
    RateLimitCode.OVER_LIMIT -> RateLimitResponse.Code.OVER_LIMIT
    RateLimitCode.UNKNOWN -> RateLimitResponse.Code.UNKNOWN
}

fun RateLimitTimeUnit.toProtoRateLimitUnit(): RateLimitResponse.RateLimit.Unit = when (this) {
    RateLimitTimeUnit.SECOND -> RateLimitResponse.RateLimit.Unit.SECOND
    RateLimitTimeUnit.MINUTE -> RateLimitResponse.RateLimit.Unit.MINUTE
    RateLimitTimeUnit.HOUR -> RateLimitResponse.RateLimit.Unit.HOUR
    RateLimitTimeUnit.DAY -> RateLimitResponse.RateLimit.Unit.DAY
    RateLimitTimeUnit.MONTH -> RateLimitResponse.RateLimit.Unit.MONTH
    RateLimitTimeUnit.YEAR -> RateLimitResponse.RateLimit.Unit.YEAR
    RateLimitTimeUnit.WEEK -> RateLimitResponse.RateLimit.Unit.WEEK
    RateLimitTimeUnit.UNKNOWN -> RateLimitResponse.RateLimit.Unit.UNKNOWN
}
