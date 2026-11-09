package ch.lkmc.combray.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * One preference control's failure feedback, fenced to the click-time request generation.
 *
 * [choose] admits the model write synchronously, at the click boundary, and captures the
 * generation then. The generation is checked only after the process-owned [Deferred] completes:
 * a late failure from an older request is ignored, while the latest request's success clears an
 * old failure and its failure sets the message. [onAcknowledged] runs for every acknowledged
 * write, even when its feedback is stale, so business side effects stay bound to the durable
 * write rather than to this UI.
 */
internal class PreferenceFeedback(private val scope: CoroutineScope) {
    var failure by mutableStateOf(false)
        private set

    private var generation = 0

    fun choose(write: () -> Deferred<Boolean>, onAcknowledged: () -> Unit = {}) {
        generation += 1
        val admitted = generation
        failure = false
        val result = write()
        scope.launch {
            val acknowledged = result.await()
            if (admitted == generation) failure = !acknowledged
            if (acknowledged) onAcknowledged()
        }
    }
}
