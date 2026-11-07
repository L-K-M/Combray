package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import android.view.WindowManager

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File

/**
 * The page field's Paste menu opens where the thumb is: at the bottom, just above the keyboard,
 * not at the window's top-left corner where an unanchored popup lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class PasteMenuPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theMenuOpensAtTheBottomNotAtTheTopLeft() {
        val context = RuntimeEnvironment.getApplication()
        val staging = UploadStaging(File(context.cacheDir, "paste-menu-position-test"), context.contentResolver)
        compose.setContent {
            CombrayTheme {
                PageControlDialog(
                    control = PageControl(1, "example.test", PageControlContent.PasteMenu, offerBlocking = false),
                    staging = staging,
                    uploadScope = rememberCoroutineScope(),
                    onAnswer = { _, _ -> },
                    onUploadFailed = {},
                    isWebAddress = { true },
                    onOpenInNewTab = {},
                    onOpenInBackground = {},
                    onSaveLink = {},
                    onShare = {},
                    onNotify = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(android.R.string.paste)).assertExists()

        // The menu is a popup window: its window's position on the screen is what the user sees.
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shadow: ShadowWindowManagerImpl = Shadow.extract(windowManager)
        val views = shadow.views
        val popup = views.last()
        val params = popup.layoutParams as WindowManager.LayoutParams
        assertTrue(
            "menu window at x=${params.x}, y=${params.y} must open in the bottom half of the 900dp screen",
            params.y > 450,
        )
    }
}
