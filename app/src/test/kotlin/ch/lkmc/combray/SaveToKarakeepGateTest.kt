package ch.lkmc.combray

import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.os.BadParcelableException
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * The "Save to Karakeep" question cannot be tapped through: Save stays off until the dialog has
 * been in front for a moment, and ignores touches that another app's window covers. None of these
 * reach the save itself, which needs the app's integrations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33], application = Application::class)
class SaveToKarakeepGateTest {
    private fun share() = Intent(Intent.ACTION_SEND)
        .setClassName(RuntimeEnvironment.getApplication(), SaveToKarakeepActivity::class.java.name)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "https://example.org/a")

    private fun ask(intent: Intent = share()): Pair<SaveToKarakeepActivity, AlertDialog> {
        val activity = Robolectric.buildActivity(SaveToKarakeepActivity::class.java, intent).setup().get()
        return activity to ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    }

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    /** Focus moving to or from the dialog's window, as the window manager reports it. */
    private fun focus(view: View, hasFocus: Boolean) {
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnWindowFocusChange", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(view.viewTreeObserver, hasFocus)
    }

    private fun tap(view: View, flags: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val properties = MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = view.width / 2f
                y = view.height / 2f
            }
            val event = MotionEvent.obtain(
                time, time, action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, flags,
            )
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        idleFor(0)
    }

    @Test
    fun saveWaitsUntilTheEnterAnimationHasEndedForAMoment() {
        val (activity, dialog) = ask()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertFalse(save.isEnabled)
        save.performClick()
        assertFalse(activity.isFinishing)
        assertTrue(dialog.isShowing)

        // Time alone does not open it: the dialog may still be invisible.
        idleFor(1_000)
        assertFalse(save.isEnabled)

        activity.onEnterAnimationComplete()
        idleFor(400)
        assertFalse(save.isEnabled)
        idleFor(200)
        assertTrue(save.isEnabled)
    }

    @Test
    fun theSharedSubjectIsQuotedBelowTheSite() {
        val (_, dialog) = ask(share().putExtra(Intent.EXTRA_SUBJECT, "accounts.google.com"))
        // The sending app picks the subject: quoted, it cannot pass for the site above it.
        assertEquals("example.org\n\u201Caccounts.google.com\u201D", shadowOf(dialog).message.toString())

        // Its own quotation marks cannot close the quote and leave a site name outside it.
        val (_, closing) = ask(share().putExtra(Intent.EXTRA_SUBJECT, "Digest\u201D accounts.google.com \u201CSaved"))
        assertEquals("example.org\n\u201CDigest accounts.google.com Saved\u201D", shadowOf(closing).message.toString())
    }

    @Test
    fun saveWaitsAgainEachTimeTheDialogComesBackToFront() {
        val (_, dialog) = ask()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        focus(save, true)
        idleFor(400)
        assertFalse(save.isEnabled)
        idleFor(200)
        assertTrue(save.isEnabled)

        // Another window took the front, then left it.
        focus(save, false)
        assertFalse(save.isEnabled)
        focus(save, true)
        idleFor(400)
        assertFalse(save.isEnabled)
        idleFor(200)
        assertTrue(save.isEnabled)
    }

    @Test
    fun touchesThroughAnotherAppsWindowDoNotSave() {
        val (activity, dialog) = ask()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        activity.onEnterAnimationComplete()
        idleFor(600)
        assertTrue(save.isEnabled)

        // Stands in for the save, to count the taps that get through.
        var taps = 0
        save.setOnClickListener { taps++ }
        tap(save, flags = 0)
        assertEquals(1, taps)

        // An overlay that hides the question but leaves Save uncovered, or one over Save itself.
        tap(save, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        tap(save, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(1, taps)
    }

    @Test
    fun anExtraThatCannotBeReadIsNoLink() {
        // Another app's Parcelable that Combray does not have, read on API 30 to 32.
        val intent = object : Intent(share()) {
            override fun getCharSequenceExtra(name: String?): CharSequence? = throw BadParcelableException("ClassNotFoundException when unmarshalling")
        }
        val activity = Robolectric.buildActivity(SaveToKarakeepActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
        assertEquals(activity.getString(R.string.save_no_link), ShadowToast.getTextOfLatestToast())
    }
}
