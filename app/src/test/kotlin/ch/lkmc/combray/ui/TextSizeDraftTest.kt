package ch.lkmc.combray.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import ch.lkmc.combray.core.browser.TextSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The text-size preview is provisional: rapid steps and stale completions must not cross. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextSizeDraftTest {
    @Test
    fun rapidChoicesKeepTheirProvisionalSteps() {
        val durable = mutableStateOf(TextSize.PERCENT_100)
        val applied = mutableListOf<TextSize>()
        val draft = draft(durable, applied)

        draft.choose(TextSize.PERCENT_110)
        draft.choose(TextSize.PERCENT_125)

        assertEquals(TextSize.PERCENT_125, draft.displayed)
        assertEquals(listOf(TextSize.PERCENT_110, TextSize.PERCENT_125), applied)
    }

    @Test
    fun anOlderFailureDoesNotRollBackANewerPendingChoice() {
        val durable = mutableStateOf(TextSize.PERCENT_100)
        val applied = mutableListOf<TextSize>()
        val failures = mutableListOf<Boolean>()
        val writes = mutableListOf<CompletableDeferred<Boolean>>()
        val draft = draft(durable, applied, failures, writes)

        draft.choose(TextSize.PERCENT_110)
        draft.choose(TextSize.PERCENT_125)
        writes[0].complete(false)

        assertEquals(TextSize.PERCENT_125, draft.displayed)
        assertEquals(listOf(TextSize.PERCENT_110, TextSize.PERCENT_125), applied)
        assertTrue(failures.isEmpty())

        writes[1].complete(false)

        assertEquals(TextSize.PERCENT_100, draft.displayed)
        assertEquals(listOf(TextSize.PERCENT_110, TextSize.PERCENT_125, TextSize.PERCENT_100), applied)
        assertEquals(1, failures.size)
    }

    @Test
    fun anOlderSuccessDoesNotClearANewerPendingChoice() {
        val durable = mutableStateOf(TextSize.PERCENT_100)
        val applied = mutableListOf<TextSize>()
        val writes = mutableListOf<CompletableDeferred<Boolean>>()
        val draft = draft(durable, applied, writes = writes)

        draft.choose(TextSize.PERCENT_110)
        draft.choose(TextSize.PERCENT_125)
        durable.value = TextSize.PERCENT_110
        writes[0].complete(true)

        assertEquals(TextSize.PERCENT_125, draft.displayed)

        durable.value = TextSize.PERCENT_125
        writes[1].complete(true)

        assertEquals(TextSize.PERCENT_125, draft.displayed)
    }

    @Test
    fun closingTheDialogClearsThePreviewButKeepsAnInFlightFailureRollback() {
        val durable = mutableStateOf(TextSize.PERCENT_100)
        val applied = mutableListOf<TextSize>()
        val failures = mutableListOf<Boolean>()
        val writes = mutableListOf<CompletableDeferred<Boolean>>()
        val draft = draft(durable, applied, failures, writes)

        draft.choose(TextSize.PERCENT_150)
        draft.reset()
        assertEquals(TextSize.PERCENT_100, draft.displayed)

        writes[0].complete(false)

        assertEquals(listOf(TextSize.PERCENT_150, TextSize.PERCENT_100), applied)
        assertEquals(1, failures.size)
    }

    private fun draft(
        durable: MutableState<TextSize>,
        applied: MutableList<TextSize>,
        failures: MutableList<Boolean> = mutableListOf(),
        writes: MutableList<CompletableDeferred<Boolean>> = mutableListOf(),
    ): TextSizeDraft {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return TextSizeDraft(
            scope = scope,
            durable = { durable.value },
            apply = { applied += it },
            persist = { CompletableDeferred<Boolean>().also { writes += it } },
            onFailure = { failures += true },
        )
    }
}
