package ch.lkmc.combray

import android.app.Activity
import android.content.Context
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

        // Some apps share a link as the subject only.
        val url = listOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT).firstNotNullOfOrNull { name ->
            intent.getCharSequenceExtra(name)?.toString()?.let(IncomingIntents::firstWebAddress)
        }
        if (url == null) {
            report(R.string.save_no_link)
            return
        }

        val title = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf { it.isNotBlank() }
        val graph = (application as CombrayApplication).graph
        val app = applicationContext
        // The process scope: closing this screen must not cancel a save while it is queued.
        graph.scope.launch {
            val message = when (graph.integrations.save(url, title)) {
                SaveRequest.Queued -> R.string.save_state_pending
                SaveRequest.NotConfigured -> R.string.save_not_set_up
                SaveRequest.NotSaveable -> R.string.save_not_saveable
                SaveRequest.Sensitive -> R.string.save_sensitive
                SaveRequest.AlreadyQueued -> R.string.save_already_queued
                SaveRequest.NeedsAttention -> R.string.save_needs_attention
            }
            withContext(Dispatchers.Main) { toast(app, message) }
        }
        // At once: an invisible window still takes focus and touches from the app that shared.
        finish()
    }

    private fun report(@StringRes message: Int) {
        toast(applicationContext, message)
        finish()
    }

    // The application context: the toast outlives this activity. Long enough to read the
    // longer messages.
    private fun toast(context: Context, @StringRes message: Int) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
