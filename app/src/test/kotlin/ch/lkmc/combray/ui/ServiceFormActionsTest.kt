package ch.lkmc.combray.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The Settings form's one-action-at-a-time rule and its stale-completion guard. */
class ServiceFormActionsTest {
    @Test
    fun aCompletionAppliesWhenTheFormWasNotEdited() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        var applied = false
        actions.perform(admission, { "result" }) { applied = true }
        assertTrue(applied)
        assertFalse(actions.isBusy)
    }

    @Test
    fun aSecondClickWhileAnOperationIsInFlightIsRejected() {
        val actions = ServiceFormActions()
        assertNotNull(actions.admit())
        assertNull(actions.admit())
        assertTrue(actions.isBusy)
    }

    @Test
    fun anEditAfterAdmissionDropsTheCompletion() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        val gate = CompletableDeferred<Unit>()
        var applied = false
        val operation = launch {
            actions.perform(admission, { gate.await(); "result" }) { applied = true }
        }
        // The user keeps typing while the delayed check or save is still running.
        actions.edited()
        gate.complete(Unit)
        operation.join()
        assertFalse(applied)
        assertFalse(actions.isBusy)
    }

    @Test
    fun aCompletionAppliesAgainOnceTheFormSettles() = runBlocking {
        val actions = ServiceFormActions()
        val stale = checkNotNull(actions.admit())
        actions.edited()
        var dropped = false
        actions.perform(stale, { "old" }) { dropped = true }
        assertFalse(dropped)

        val fresh = checkNotNull(actions.admit())
        var applied = false
        actions.perform(fresh, { "new" }) { applied = true }
        assertTrue(applied)
    }

    @Test
    fun aLateCallbackFromASupersededOperationDoesNotApply() = runBlocking {
        val actions = ServiceFormActions()
        val first = checkNotNull(actions.admit())
        actions.release(first)
        val second = checkNotNull(actions.admit())
        var applied = false
        actions.complete(first, "old") { applied = true }
        assertFalse(applied)
        actions.release(second)
    }

    @Test
    fun aFailureReleasesTheAdmission() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        try {
            actions.perform(admission, { throw IOException("store failed") }) { }
            throw AssertionError("the failure was swallowed")
        } catch (expected: IOException) {
            // The failure stays visible; only the admission is released.
        }
        assertFalse(actions.isBusy)
        assertNotNull(actions.admit())
    }

    @Test
    fun acancellationBeforeTheOperationStartsReleasesTheAdmission() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        val gate = CompletableDeferred<Unit>()
        val operation = launch {
            actions.perform(admission, { gate.await(); "result" }) { }
        }
        // The form releases the admission when the coroutine ends, however it ends.
        operation.invokeOnCompletion { actions.release(admission) }
        operation.cancelAndJoin()
        assertFalse(actions.isBusy)
        assertNotNull(actions.admit())
    }

    @Test
    fun acancellationWhileTheOperationRunsReleasesTheAdmission() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val operation = launch {
            started.complete(Unit)
            actions.perform(admission, { gate.await(); "result" }) { }
        }
        operation.invokeOnCompletion { actions.release(admission) }
        started.await()
        operation.cancelAndJoin()
        assertFalse(actions.isBusy)
        assertNotNull(actions.admit())
    }

    @Test
    fun aDelayedActionStillAppliesWhenNoEditIntervened() = runBlocking {
        val actions = ServiceFormActions()
        val admission = checkNotNull(actions.admit())
        var applied = false
        actions.perform(admission, { delay(10); "result" }) { applied = true }
        assertTrue(applied)
        assertFalse(actions.isBusy)
    }
}
