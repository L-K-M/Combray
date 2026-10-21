package ch.lkmc.combray

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import ch.lkmc.combray.core.integration.SaveRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Save to Karakeep" share target: queues the first web link in the shared text the way the
 * Save button does, says what happened in a toast and closes, without bringing the browser
 * forward. It shows nothing itself (translucent, in a task of its own).
 */
class SaveToKarakeepActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only shares are read. Recreated while the first instance was saving: that one reports.
        if (savedInstanceState != null || intent.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val url = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(IncomingIntents::firstWebAddress)
        if (url == null) {
            report(R.string.save_no_link)
            return
        }

        val title = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf { it.isNotBlank() }
        val graph = (application as CombrayApplication).graph
        // The process scope: leaving this screen must not cancel a save while it is being queued.
        graph.scope.launch {
            val message = when (graph.integrations.save(url, title)) {
                SaveRequest.Queued -> R.string.save_state_pending
                SaveRequest.NotConfigured -> R.string.save_not_set_up
                SaveRequest.NotSaveable -> R.string.save_not_saveable
                SaveRequest.Sensitive -> R.string.save_sensitive
                SaveRequest.AlreadyQueued -> R.string.save_already_queued
                SaveRequest.NeedsAttention -> R.string.save_needs_attention
            }
            withContext(Dispatchers.Main) { report(message) }
        }
    }

    private fun report(@StringRes message: Int) {
        // The application context: the toast outlives this activity. Long enough to read the
        // longer messages.
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
