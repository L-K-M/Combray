package ch.lkmc.combray

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.annotation.StringRes
import ch.lkmc.combray.core.integration.SaveRequest
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.ui.displayHost
import ch.lkmc.combray.ui.shareTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Save to Karakeep" share target: it asks once, then queues the first web link in the
 * shared text the way the Save button does, says what happened in a toast and closes, without
 * bringing the browser forward (translucent, in a task of its own).
 *
 * The question is the owner's decision (22 October 2026): this component is exported, so any app
 * can start it, and nothing reaches the owner's Karakeep without a tap here.
 */
class SaveToKarakeepActivity : Activity() {
    // The question's Save button, once it is shown.
    private var saveButton: Button? = null
    private val enableSave = Runnable { saveButton?.isEnabled = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only shares are read. A recreated instance asks again: nothing was saved yet.
        if (intent.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        // Some apps share a link as the subject only. The link goes through the address policy,
        // like a typed address, before it can be saved.
        val url = listOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT).firstNotNullOfOrNull { name ->
            IncomingIntents.textExtra(intent, name)?.let(IncomingIntents::firstWebAddress)
        }?.let { (AddressPolicy.interpret(it).primary as? AddressDecision.Navigate)?.url }
        if (url == null) {
            report(R.string.save_no_link)
            return
        }

        // A subject that only holds the link is no title: Karakeep then uses the page's own. The
        // sharing app chooses the subject, so it is one bounded line, quoted below the real site:
        // "accounts.google.com" must not read as a second site name.
        val title = IncomingIntents.textExtra(intent, Intent.EXTRA_SUBJECT)
            ?.takeIf { IncomingIntents.firstWebAddress(it) == null }
            ?.let(::shareTitle)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.share_save_confirm)
            .setMessage(listOfNotNull(displayHost(url), title?.let { quoted(it) }).joinToString("\n"))
            // Its click is set below, where the button exists.
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            // A click while Save is off, from an accessibility service or posted just before Save
            // went off, does nothing.
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                dialog.dismiss()
                save(url, title)
            }
            // Another app's overlay must not trick the tap that saves: not over Save, and not
            // elsewhere over the dialog, hiding what it asks while Save stays uncovered. The
            // listener only drops such touches; the others still click through performClick.
            filterTouchesWhenObscured = true
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event -> event.flags and OBSCURED != 0 }
            // Off until the dialog has been in front for a moment, and again whenever another
            // window takes the front from it.
            isEnabled = false
            viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> holdSave(enableLater = hasFocus) }
        }
    }

    // The end of the enter animation counts like focus, for a dialog whose focus is not reported.
    // Focus can also come first, while the sending app's own enter animation still keeps the
    // dialog almost transparent: this gate does not cover that, on Android versions that do not
    // drop touches on a window still animating in.
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        holdSave(enableLater = true)
    }

    /**
     * Turns Save off, and back on after [SAVE_DELAY_MILLIS] when [enableLater]: a tap the user
     * aimed at the sharing app, landing as the dialog appears under it, must not save.
     */
    private fun holdSave(enableLater: Boolean) {
        val button = saveButton ?: return
        button.removeCallbacks(enableSave)
        button.isEnabled = false
        if (enableLater) button.postDelayed(enableSave, SAVE_DELAY_MILLIS)
    }

    // Its own quotation marks could close the quote early and leave a site name outside it.
    private fun quoted(subject: String) = getString(R.string.share_save_subject, subject.filterNot { it in QUOTATION_MARKS })

    private fun save(url: String, title: String?) {
        val graph = (application as CombrayApplication).graph
        val app = applicationContext
        // The process scope: closing this screen must not cancel a save while it is queued.
        graph.scope.launch {
            val message = try {
                when (graph.integrations.save(url, title)) {
                    SaveRequest.Queued -> R.string.save_state_pending
                    SaveRequest.NotConfigured -> R.string.save_not_set_up
                    SaveRequest.NotSaveable -> R.string.save_not_saveable
                    SaveRequest.Sensitive -> R.string.save_sensitive
                    SaveRequest.AlreadyQueued -> R.string.save_already_queued
                    SaveRequest.NeedsAttention -> R.string.save_needs_attention
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Storage failed (a full disk, an I/O error): nothing was queued, and this screen
                // is gone, so a crash here would end the process without a word.
                Log.e(LOG_TAG, "saving a shared link failed", failure)
                R.string.save_failed
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

    private companion object {
        const val LOG_TAG = "CombraySave"

        const val QUOTATION_MARKS = "\"“”„‟«»‹›"

        /** How long the dialog is in front before Save works: longer than a tap already under way. */
        const val SAVE_DELAY_MILLIS = 500L

        /**
         * Touches through or beside another app's window. The view's own filter only drops the
         * first, so an overlay that hides the question but leaves Save uncovered would pass.
         */
        const val OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    }
}
