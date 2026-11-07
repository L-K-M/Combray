package ch.lkmc.combray.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings form's one-action-at-a-time rule and its stale-completion guard. */
class ServiceFormActionsTest {
    @Test
    fun aCompletionAppliesWhenTheFormWasNotEdited() = runBlocking {
        val actions = ServiceFormActions()
        var applied = false
        actions.perform({ "result" }) { applied = true }
        assertTrue(applied)
        assertFalse(actions.isBusy)
    }

    @Test
    fun anEditDropsTheCompletionOfAnActionThatStartedBeforeIt() = runBlocking {
        val actions = ServiceFormActions()
        var applied = false
        val action = async(start = CoroutineStart.UNDISPATCHED) { actions.perform({ delay(50); "result" }) { applied = true } }
        // The user keeps typing while the delayed check or save is still running.
        actions.edited()
        action.await()
        assertFalse(applied)
    }

    @Test
    fun aCompletionAppliesAgainOnceTheFormSettles() = runBlocking {
        val actions = ServiceFormActions()
        var first = false
        var second = false
        val stale = async(start = CoroutineStart.UNDISPATCHED) { actions.perform({ delay(50); "old" }) { first = true } }
        actions.edited()
        stale.await()
        assertFalse(first)

        actions.perform({ "new" }) { second = true }
        assertTrue(second)
    }

    @Test
    fun actionsRunOneAtATime() = runBlocking {
        val actions = ServiceFormActions()
        val order = mutableListOf<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            actions.perform({
                order += 1
                delay(50)
                order += 10
            }) { }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            actions.perform({
                order += 2
                delay(50)
                order += 20
            }) { }
        }
        first.await()
        second.await()
        // The second action starts only after the first has finished: no overlapping operations.
        assertEquals(listOf(1, 10, 2, 20), order)
    }

    @Test
    fun theButtonsWaitWhileAnActionIsInFlight() = runBlocking {
        val actions = ServiceFormActions()
        val action = async(start = CoroutineStart.UNDISPATCHED) { actions.perform({ delay(50); "result" }) { } }
        assertTrue(actions.isBusy)
        action.await()
        assertFalse(actions.isBusy)
    }

    @Test
    fun aQueuedActionKeepsTheFormBusyUntilItFinishes() = runBlocking {
        val actions = ServiceFormActions()
        val first = async(start = CoroutineStart.UNDISPATCHED) { actions.perform({ delay(50); "first" }) { } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { actions.perform({ delay(50); "second" }) { } }
        assertTrue(actions.isBusy)
        first.await()
        // The second action is now running, so the form is still busy.
        assertTrue(actions.isBusy)
        second.await()
        assertFalse(actions.isBusy)
    }
}
