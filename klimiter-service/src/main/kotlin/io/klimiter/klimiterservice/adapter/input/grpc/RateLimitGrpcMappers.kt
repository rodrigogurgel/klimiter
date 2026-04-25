package io.klimiter.klimiterservice.adapter.input.grpc

import io.klimiter.klimiterservice.domain.model.RateLimitCheckResult
import io.klimiter.klimiterservice.domain.model.RateLimitDecision
import io.klimiter.klimiterservice.domain.model.RateLimitKey
import io.klimiter.klimiterservice.domain.model.RateLimitKeyStatus
import io.klimiter.klimiterservice.proto.Decision
import io.klimiter.klimiterservice.proto.KeyRequest
import io.klimiter.klimiterservice.proto.KeyStatus
import io.klimiter.klimiterservice.proto.ShouldRateLimitRequest
import io.klimiter.klimiterservice.proto.ShouldRateLimitResponse
import io.klimiter.klimiterservice.proto.keyStatus
import io.klimiter.klimiterservice.proto.shouldRateLimitResponse

internal fun ShouldRateLimitRequest.toDomain(): List<RateLimitKey> = keysList.map { it.toDomain() }

private fun KeyRequest.toDomain(): RateLimitKey = RateLimitKey(
    key = key,
    value = value,
    cost = cost,
)

internal fun RateLimitCheckResult.toProto(): ShouldRateLimitResponse = shouldRateLimitResponse {
    overallDecision = overall.toProto()
    statuses += this@toProto.statuses.map { it.toProto() }
}

private fun RateLimitKeyStatus.toProto(): KeyStatus = keyStatus {
    key = this@toProto.key
    value = this@toProto.value
    decision = this@toProto.decision.toProto()
    limit = this@toProto.limit
    remaining = this@toProto.remaining
    windowSeconds = this@toProto.windowSeconds
    windowStartEpochSeconds = this@toProto.windowStartEpochSeconds
    resetAtEpochSeconds = this@toProto.resetAtEpochSeconds
}

private fun RateLimitDecision.toProto(): Decision = when (this) {
    RateLimitDecision.OK -> Decision.OK
    RateLimitDecision.OVER_LIMIT -> Decision.OVER_LIMIT
    RateLimitDecision.ERROR -> Decision.ERROR
}
