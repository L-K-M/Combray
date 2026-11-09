package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.engine.EngineSurfaceView
import ch.lkmc.combray.engine.PageRef
import ch.lkmc.combray.engine.ViewRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The page area is an editable field only while a page field has focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class EngineSurfaceViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun thePageIsNotEditableUntilAPageFieldTakesFocus() {
        val view = EngineSurfaceView(context) { true }
        assertFalse(view.onCheckIsTextEditor())

        view.prepareKeyboard("text")
        assertTrue(view.onCheckIsTextEditor())

        view.hideKeyboard()
        assertFalse(view.onCheckIsTextEditor())
    }

    @Test
    fun noConnectionIsOfferedWithoutAPreparedFieldOrARunningEngine() {
        val idle = EngineSurfaceView(context) { true }
        assertNull(idle.onCreateInputConnection(EditorInfo()))

        idle.prepareKeyboard("text", contextId = 1)
        assertNotNull(idle.onCreateInputConnection(EditorInfo()))

        val stopped = EngineSurfaceView(context) { false }
        stopped.prepareKeyboard("text", contextId = 1)
        assertFalse(stopped.onCheckIsTextEditor())
        assertNull(stopped.onCreateInputConnection(EditorInfo()))
    }

    /** The address editor keeps the keyboard: the page's field lets go, and this view stays uneditable. */
    @Test
    fun aDeclinedKeyboardRetiresThePageFieldWithoutAConnection() {
        var running = true
        val view = EngineSurfaceView(context) { running }
        view.prepareKeyboard("text", contextId = 1)
        assertTrue(view.onCheckIsTextEditor())

        // No engine to tell in a JVM test: the decline itself is what is checked.
        running = false
        view.declineKeyboard()
        running = true
        assertFalse(view.onCheckIsTextEditor())
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(view.isFocused)
    }

    /** Taking the keyboard for the address editor retires a page field; with none, nothing happens. */
    @Test
    fun yieldingTheKeyboardRetiresAPageFieldOnlyWhenThereIsOne() {
        var running = true
        val view = EngineSurfaceView(context) { running }
        // No page field: nothing to tell the engine, which a JVM test does not have.
        view.yieldKeyboard()
        assertFalse(view.onCheckIsTextEditor())

        view.prepareKeyboard("text", contextId = 1)
        running = false
        view.yieldKeyboard()
        running = true
        assertFalse(view.onCheckIsTextEditor())
        assertNull(view.onCreateInputConnection(EditorInfo()))
    }

    @Test
    fun aNewPreparedFieldIsEditableAgainAfterTheKeyboardWasHidden() {
        val view = EngineSurfaceView(context) { true }
        view.prepareKeyboard("text", contextId = 1)
        view.hideKeyboard()
        view.prepareKeyboard("search", contextId = 2)
        assertTrue(view.onCheckIsTextEditor())
    }

    @Test
    fun aPageChangeRetiresTheFieldAndItsConnection() {
        val view = EngineSurfaceView(context) { true }
        view.prepareKeyboard("text", contextId = 1)
        val connection = view.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)

        view.observeScroll(PageRef(ViewRef(tab = 1, generation = 1), document = 2)) {}

        assertFalse(view.onCheckIsTextEditor())
        assertNull(view.onCreateInputConnection(EditorInfo()))
        assertFalse(connection!!.commitText("late", 1))
        assertFalse(connection.setComposingText("late", 1))
        assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertFalse(connection.deleteSurroundingText(1, 0))

        // A replacement field prepares its own connection and context; the old one is not reused.
        view.prepareKeyboard("text", contextId = 2)
        val replacement = view.onCreateInputConnection(EditorInfo())
        assertNotNull(replacement)
        assertFalse(replacement === connection)
        assertFalse(connection.commitText("late", 1))
    }

    @Test
    fun detachingTheWindowClearsThePreparedField() {
        val view = EngineSurfaceView(context) { true }
        view.prepareKeyboard("text", contextId = 1)
        assertTrue(view.onCheckIsTextEditor())

        shadowOf(view).callOnDetachedFromWindow()

        assertFalse(view.onCheckIsTextEditor())
    }
}
