package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.engine.EngineSurfaceView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
