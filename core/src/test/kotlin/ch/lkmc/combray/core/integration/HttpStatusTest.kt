package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpStatusTest {
    @Test
    fun aConnectionCheckCallsExactlyTheStatusesTheOutboxRetriesAServerError() {
        for (status in 100..699) {
            val dispatch = HttpStatus.classifyFailure(HttpOutcome.Response(status, emptyMap(), ByteArray(0)))
            val expected = when (dispatch) {
                is DispatchResult.AuthRequired -> ConnectionCheck.Unauthorized
                is DispatchResult.Retry -> ConnectionCheck.ServerError(status)
                else -> ConnectionCheck.WrongAddress(status)
            }
            assertEquals("HTTP $status", expected, HttpStatus.classifyCheckFailure(status))
        }
        assertEquals(ConnectionCheck.ServerError(408), HttpStatus.classifyCheckFailure(408))
        assertEquals(ConnectionCheck.ServerError(429), HttpStatus.classifyCheckFailure(429))
        assertEquals(ConnectionCheck.ServerError(500), HttpStatus.classifyCheckFailure(500))
        assertEquals(ConnectionCheck.ServerError(599), HttpStatus.classifyCheckFailure(599))
        assertEquals(ConnectionCheck.WrongAddress(404), HttpStatus.classifyCheckFailure(404))
        assertEquals(ConnectionCheck.WrongAddress(600), HttpStatus.classifyCheckFailure(600))
    }

    @Test
    fun retryAfterSecondsBecomeMilliseconds() {
        assertEquals(30_000L, HttpStatus.retryAfterMs(mapOf("Retry-After" to "30")))
    }

    @Test
    fun retryAfterIsCaseInsensitiveAndTrimmed() {
        assertEquals(60_000L, HttpStatus.retryAfterMs(mapOf("retry-after" to " 60 ")))
    }

    @Test
    fun anHugeRetryAfterClampsToTheCeilingInsteadOfWrapping() {
        // Long.MAX_VALUE seconds times 1000 overflows negative; the wait must stay at the cap.
        assertEquals(6L * 60 * 60 * 1000, HttpStatus.retryAfterMs(mapOf("Retry-After" to Long.MAX_VALUE.toString())))
        assertEquals(6L * 60 * 60 * 1000, HttpStatus.retryAfterMs(mapOf("Retry-After" to "100000")))
    }

    @Test
    fun aNegativeRetryAfterWaitsNothing() {
        assertEquals(0L, HttpStatus.retryAfterMs(mapOf("Retry-After" to "-5")))
    }

    @Test
    fun nonNumericRetryAfterIsIgnored() {
        assertNull(HttpStatus.retryAfterMs(mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")))
        // Beyond Long's range: not a number this parser knows, so the backoff applies.
        assertNull(HttpStatus.retryAfterMs(mapOf("Retry-After" to "99999999999999999999")))
        assertNull(HttpStatus.retryAfterMs(mapOf()))
    }
}
