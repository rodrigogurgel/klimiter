package io.klimiter.core.api.rls

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitResponseTest {

    @Test
    fun `isOk returns true for OK`() {
        assertTrue(RateLimitResponse(RateLimitCode.OK).isOk())
    }

    @Test
    fun `isOk returns false for OVER_LIMIT`() {
        assertFalse(RateLimitResponse(RateLimitCode.OVER_LIMIT).isOk())
    }

    @Test
    fun `isOk returns false for UNKNOWN`() {
        assertFalse(RateLimitResponse(RateLimitCode.UNKNOWN).isOk())
    }

    @Test
    fun `isOverLimit returns true for OVER_LIMIT`() {
        assertTrue(RateLimitResponse(RateLimitCode.OVER_LIMIT).isOverLimit())
    }

    @Test
    fun `isOverLimit returns false for OK`() {
        assertFalse(RateLimitResponse(RateLimitCode.OK).isOverLimit())
    }

    @Test
    fun `isOverLimit returns false for UNKNOWN`() {
        assertFalse(RateLimitResponse(RateLimitCode.UNKNOWN).isOverLimit())
    }
}
