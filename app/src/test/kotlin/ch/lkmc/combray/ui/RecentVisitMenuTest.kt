package ch.lkmc.combray.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.lkmc.combray.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A Recent row's actions name the exact recorded address: Copy puts it on the clipboard with
 * the sensitive hint, Share offers it unchanged, and only Android 13+ confirms the copy itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33], application = Application::class)
class RecentVisitMenuTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val notifications = mutableListOf<String>()
    private val url = "https://example.test/article?token=fixture#part"

    @Test
    fun copyPutsTheExactRecordedAddressOnTheClipboard() {
        showMenu()

        compose.onNodeWithContentDescription("Actions for Article").performClick()
        compose.onNodeWithText(context.getString(R.string.link_copy)).performClick()

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(url, clip.getItemAt(0).text.toString())
        assertEquals("Address", clip.description.label.toString())
        assertNotNull(clip.description.extras)
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun shareOffersTheExactRecordedAddress() {
        showMenu()

        compose.onNodeWithContentDescription("Actions for Article").performClick()
        shadowOf(context).clearNextStartedActivities()
        compose.onNodeWithText(context.getString(R.string.action_share)).performClick()

        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun olderAndroidConfirmsTheCopyNewerAndroidLetsTheSystemDoIt() {
        showMenu()

        compose.onNodeWithContentDescription("Actions for Article").performClick()
        compose.onNodeWithText(context.getString(R.string.link_copy)).performClick()

        compose.runOnIdle {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                assertEquals(listOf(context.getString(R.string.link_copied)), notifications)
            } else {
                assertTrue("Android 13+ confirms the copy itself", notifications.isEmpty())
            }
        }
    }

    private fun showMenu() {
        compose.setContent {
            CombrayTheme {
                VisitMenu(
                    title = "Article",
                    host = "example.test",
                    url = url,
                    onRemove = {},
                    onForgetSite = {},
                    onNotify = { notifications += it },
                )
            }
        }
    }
}
