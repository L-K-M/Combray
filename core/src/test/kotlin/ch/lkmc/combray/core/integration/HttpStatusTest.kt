package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpStatusTest {
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
        assertEquals(6L * 60 * 60 * 1000, HttpStatus.retryAfterMs(mapOf("Retry-After" to "9223372036854775807")))
    }

    @Test
    fun aNegativeRetryAfterWaitsNothing() {
        assertEquals(0L, HttpStatus.retryAfterMs(mapOf("Retry-After" to "-5")))
    }

    @Test
    fun nonNumericRetryAfterIsIgnored() {
        assertNull(HttpStatus.retryAfterMs(mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertNull(HttpStatus.retryAfterMs(mapOf()))
    }
}
