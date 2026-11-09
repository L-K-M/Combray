package ch.lkmc.combray.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feedback fence with explicit [CompletableDeferred]s, so a test can complete an older
 * request after a newer one. The real model serialises writes, so this out-of-order completion
 * is only observable here.
 */
class PreferenceFeedbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val feedback = PreferenceFeedback(scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** An older failure that lands after the latest success must not appear. */
    @Test
    fun aDelayedOlderFailureDoesNotAppearAfterTheLatestSuccess() {
        val older = CompletableDeferred<Boolean>()
        val newer = CompletableDeferred<Boolean>()
        feedback.choose({ older })
        feedback.choose({ newer })

        newer.complete(true)
        older.complete(false)

        assertFalse(feedback.failure)
    }

    /** The latest success clears a failure that an older request already put on screen. */
    @Test
    fun theLatestSuccessClearsAnOlderFailure() {
        val older = CompletableDeferred<Boolean>()
        val newer = CompletableDeferred<Boolean>()
        feedback.choose({ older })
        older.complete(false)
        assertTrue(feedback.failure)

        feedback.choose({ newer })
        newer.complete(true)

        assertFalse(feedback.failure)
    }

    /** The latest failure is the one that shows, even after an older success. */
    @Test
    fun theLatestFailureShows() {
        val older = CompletableDeferred<Boolean>()
        val newer = CompletableDeferred<Boolean>()
        feedback.choose({ older })
        feedback.choose({ newer })

        older.complete(true)
        newer.complete(false)

        assertTrue(feedback.failure)
    }

    /** Every acknowledged write runs the business callback, even a stale one. */
    @Test
    fun everyAcknowledgedWriteRunsTheBusinessCallback() {
        val older = CompletableDeferred<Boolean>()
        val newer = CompletableDeferred<Boolean>()
        val acknowledged = mutableListOf<Int>()
        feedback.choose({ older }, { acknowledged += 1 })
        feedback.choose({ newer }, { acknowledged += 2 })

        newer.complete(true)
        older.complete(true)

        assertEquals(listOf(2, 1), acknowledged)
    }

    /** The write is admitted synchronously, at the click boundary. */
    @Test
    fun theWriteIsAdmittedAtTheClickBoundary() {
        var admitted = 0
        val result = CompletableDeferred<Boolean>()

        feedback.choose({ admitted += 1; result })

        assertEquals(1, admitted)
    }
}
