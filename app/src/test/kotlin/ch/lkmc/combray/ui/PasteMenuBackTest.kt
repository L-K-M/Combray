package ch.lkmc.combray.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class PasteMenuBackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun backDismissesTheMenuBeforePageNavigation() {
        var navigated = false
        val responses = mutableListOf<ControlResponse>()
        lateinit var dispatcher: OnBackPressedDispatcher
        val context = RuntimeEnvironment.getApplication()
        val staging = UploadStaging(File(context.cacheDir, "paste-menu-test"), context.contentResolver)

        compose.setContent {
            val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            SideEffect { dispatcher = owner.onBackPressedDispatcher }
            BackHandler { navigated = true }
            CombrayTheme {
                PageControlDialog(
                    control = PageControl(1, "example.test", PageControlContent.PasteMenu, offerBlocking = false),
                    staging = staging,
                    uploadScope = rememberCoroutineScope(),
                    onAnswer = { response, _ -> responses += response },
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

        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf(ControlResponse.Dismiss), responses)
            assertFalse("Back reached page navigation", navigated)
        }
    }
}
