package ch.lkmc.combray

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import ch.lkmc.combray.ui.copyAddressToClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33], manifest = Config.NONE, application = Application::class)
class AddressClipboardTest {
    @Test
    fun copiedAddressesKeepExactTextAndCarryTheSensitiveHint() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        for (url in listOf(
            "https://example.test/%7Earticle?q=one%20two#part",
            "https://example.test/?token=fixture#access_token=fixture",
        )) {
            copyAddressToClipboard(context, "Address", url)
            val clip = clipboard.primaryClip!!
            assertEquals(1, clip.itemCount)
            assertEquals(url, clip.getItemAt(0).text.toString())
            assertEquals("Address", clip.description.label.toString())
            assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            assertNotNull(clip.description.extras)
            assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        }
    }
}
