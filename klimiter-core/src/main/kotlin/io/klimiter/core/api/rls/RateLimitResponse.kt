@file:Suppress("DEPRECATION")

package io.klimiter.core.api.rls

data class RateLimitResponse(
    val overallCode: RateLimitCode,
    val statuses: List<RateLimitStatus> = emptyList(),
    @Deprecated("not implemented yet")
    val responseHeaders: List<RateLimitHeader> = emptyList(),
    @Deprecated("not implemented yet")
    val requestHeaders: List<RateLimitHeader> = emptyList(),
    @Deprecated("not implemented yet")
    val rawBody: ByteArray? = null
) {
    fun isOverLimit(): Boolean = overallCode == RateLimitCode.OVER_LIMIT
    fun isOk(): Boolean = overallCode == RateLimitCode.OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RateLimitResponse) return false
        return overallCode == other.overallCode &&
                statuses == other.statuses &&
                responseHeaders == other.responseHeaders &&
                requestHeaders == other.requestHeaders &&
                rawBody.contentEquals(other.rawBody)
    }

    override fun hashCode(): Int {
        var result = overallCode.hashCode()
        result = 31 * result + statuses.hashCode()
        result = 31 * result + responseHeaders.hashCode()
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + (rawBody?.contentHashCode() ?: 0)
        return result
    }
}
