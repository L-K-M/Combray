package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.lkmc.combray.core.browser.TextSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native zoom is a projection, not persistence: a recreated UI must rebind that projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TextSizePreviewEffectTest {
    @get:Rule val compose = createComposeRule()
    private val durable = mutableStateOf(TextSize.DEFAULT)
    private val ready = mutableStateOf(true)
    private val applied = mutableListOf<TextSize>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelObservers() { scopes.forEach { it.cancel() } }

    @Test
    fun aRecreatedObserverRestoresDurableZoomAndFollowsALaterAcknowledgement() {
        val oldScope = newScope()
        val observer = mutableStateOf(draft(oldScope))
        compose.setContent {
            key(observer.value) {
                TextSizePreviewEffect(observer.value, durable.value, ready.value, ::apply)
            }
        }
        compose.runOnIdle { observer.value.choose(TextSize.PERCENT_150) }
        assertEquals(TextSize.PERCENT_150, applied.last())
        compose.runOnIdle {
            oldScope.cancel()
            observer.value = draft(newScope())
        }
        compose.waitForIdle()
        assertEquals("No acknowledgement means the recreated page must use durable zoom", TextSize.DEFAULT, applied.last())

        compose.runOnIdle {
            applied.clear()
            durable.value = TextSize.PERCENT_150
        }
        compose.waitForIdle()
        assertEquals(TextSize.PERCENT_150, applied.last())
    }

    @Test
    fun anOlderDurableValueDoesNotOverrideANewerPendingPreview() {
        val observer = draft(newScope())
        compose.setContent { TextSizePreviewEffect(observer, durable.value, ready.value, ::apply) }
        compose.runOnIdle {
            observer.choose(TextSize.PERCENT_110)
            observer.choose(TextSize.PERCENT_125)
            durable.value = TextSize.PERCENT_110
        }
        compose.waitForIdle()
        assertEquals(TextSize.PERCENT_125, applied.last())
    }

    @Test
    fun anAcknowledgedSizeIsNotOverwrittenByALaggingCollectedSnapshot() {
        val writes = mutableListOf<CompletableDeferred<Boolean>>()
        val observer = TextSizeDraft(
            scope = newScope(),
            durable = { durable.value },
            apply = ::apply,
            persist = { CompletableDeferred<Boolean>().also { writes += it } },
            onFailure = {},
        )
        // A stopped/lagging lifecycle collector still has the previous snapshot when the
        // independent process-owned write acknowledges and clears this observer's preview.
        compose.setContent { TextSizePreviewEffect(observer, TextSize.DEFAULT, ready.value, ::apply) }
        compose.runOnIdle { observer.choose(TextSize.PERCENT_125) }
        compose.runOnIdle {
            durable.value = TextSize.PERCENT_125
            writes.single().complete(true)
        }
        compose.waitForIdle()
        assertEquals(TextSize.PERCENT_125, applied.last())
    }

    @Test
    fun aPendingPreviewAppliesWhenTheEngineBecomesReady() {
        ready.value = false
        val observer = draft(newScope())
        compose.setContent { TextSizePreviewEffect(observer, durable.value, ready.value, ::apply) }
        compose.runOnIdle { observer.choose(TextSize.PERCENT_125) }
        assertTrue(applied.isEmpty())
        compose.runOnIdle { ready.value = true }
        compose.waitForIdle()
        assertEquals(TextSize.PERCENT_125, applied.last())
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }

    private fun draft(scope: CoroutineScope) = TextSizeDraft(
        scope = scope,
        durable = { durable.value },
        apply = ::apply,
        persist = { CompletableDeferred() },
        onFailure = {},
    )

    private fun apply(size: TextSize) { if (ready.value) applied += size }
}
