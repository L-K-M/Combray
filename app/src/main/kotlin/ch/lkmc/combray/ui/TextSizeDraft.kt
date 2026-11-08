package ch.lkmc.combray.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lkmc.combray.core.browser.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/** Rebinds native zoom when this UI is recreated or an admitted write finishes afterward. */
@Composable
internal fun TextSizePreviewEffect(
    draft: TextSizeDraft,
    durable: TextSize,
    engineReady: Boolean,
    apply: (TextSize) -> Unit,
) {
    val effective = draft.pending ?: durable
    val latestApply by rememberUpdatedState(apply)
    LaunchedEffect(effective, engineReady) {
        if (engineReady) latestApply(effective)
    }
}

/**
 * Provisional text-size preview. The dialog shows [displayed] and the engine zooms to it at once;
 * the durable flow is the source of truth. Rapid steps read the latest pending choice, and an
 * older completion or failure never overwrites a newer one.
 */
internal class TextSizeDraft(
    private val scope: CoroutineScope,
    private val durable: () -> TextSize,
    private val apply: (TextSize) -> Unit,
    private val persist: (TextSize) -> Deferred<Boolean>,
    private val onFailure: () -> Unit,
) {
    var pending by mutableStateOf<TextSize?>(null)
        private set

    private var writeId = 0

    val displayed: TextSize get() = pending ?: durable()

    fun choose(size: TextSize) {
        if (size == displayed) return
        pending = size
        apply(size)
        val id = ++writeId
        // Admit the write at the click boundary, not when a UI observer happens to run.
        val write = persist(size)
        scope.launch {
            val acknowledged = write.await()
            if (id != writeId) return@launch
            pending = null
            if (!acknowledged) {
                apply(durable())
                onFailure()
            }
        }
    }

    /** The dialog closed: the preview is gone, but an in-flight write still finishes. */
    fun reset() {
        pending = null
    }
}
