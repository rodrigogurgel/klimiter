package io.klimiter.core.api.config

enum class RateLimitTimeUnit {
    UNKNOWN,
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    /** Number of seconds covered by one window of this unit. UNKNOWN is not a valid bucket. */
    fun windowSeconds(): Long = when (this) {
        SECOND -> SECONDS_PER_SECOND
        MINUTE -> SECONDS_PER_MINUTE
        HOUR -> SECONDS_PER_HOUR
        DAY -> SECONDS_PER_DAY
        WEEK -> SECONDS_PER_WEEK
        MONTH -> SECONDS_PER_MONTH
        YEAR -> SECONDS_PER_YEAR
        UNKNOWN -> error("RateLimitTimeUnit.UNKNOWN is not supported")
    }

    private companion object {
        const val SECONDS_PER_SECOND = 1L
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val SECONDS_PER_DAY = 86_400L
        const val SECONDS_PER_WEEK = 604_800L
        const val SECONDS_PER_MONTH = 2_592_000L
        const val SECONDS_PER_YEAR = 31_536_000L
    }
}
