package ch.lkmc.combray

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33], manifest = Config.NONE, application = Application::class)
class PageClipboardTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun aPastePreservesTextAndDoesNotChangeTheClipboard() {
        val text = "été\nAda \"password\""
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", text))
        assertEquals(text, readPagePaste(context))
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun anEmptyClipboardOrUriClipPastesNothing() {
        clipboard.clearPrimaryClip()
        assertNull(readPagePaste(context))
        clipboard.setPrimaryClip(ClipData.newRawUri("fixture", Uri.parse("content://fixture/private")))
        assertNull(readPagePaste(context))
    }

    @Test
    fun aLongPasswordIsRefusedWholeByItsUtf8Size() {
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "é".repeat(128 * 1024)))
        assertEquals(128 * 1024, readPagePaste(context)!!.length)
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "é".repeat(128 * 1024 + 1)))
        assertNull(readPagePaste(context))
    }
}
