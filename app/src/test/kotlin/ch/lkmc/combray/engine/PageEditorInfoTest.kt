package ch.lkmc.combray.engine

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class PageEditorInfoTest {
    @Test
    fun editorsAndTextareasOfferANewlineRatherThanAnAction() {
        val info = EditorInfo()
        val surface = EngineSurfaceView(ApplicationProvider.getApplicationContext()) { true }
        surface.prepareKeyboard("text", multiline = true)
        surface.onCreateInputConnection(info)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertEquals(EditorInfo.IME_ACTION_NONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    @Test
    fun singleLineSearchKeepsItsActionAndPasswordKeepsItsPrivacyFlags() {
        val search = EditorInfo()
        val surface = EngineSurfaceView(ApplicationProvider.getApplicationContext()) { true }
        surface.prepareKeyboard("search")
        surface.onCreateInputConnection(search)
        assertEquals(0, search.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, search.imeOptions and EditorInfo.IME_MASK_ACTION)
        val password = EditorInfo()
        surface.prepareKeyboard("password")
        surface.onCreateInputConnection(password)
        assertTrue(password.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(password.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}
