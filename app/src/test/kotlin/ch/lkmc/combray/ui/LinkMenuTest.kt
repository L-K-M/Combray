package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class LinkMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aLinkHasOneNewTabAction() = openFromMenu(
        PageControlContent.LinkMenu(LINK, image = null),
        "Open in new tab",
        LINK,
    )

    @Test
    fun anImageUsesTheSameNewTabAction() = openFromMenu(
        PageControlContent.LinkMenu(link = null, IMAGE),
        "Open image in new tab",
        IMAGE,
    )

    @Test
    fun aLinkedImageOpensTheImageAddressWhenChosen() = openFromMenu(
        PageControlContent.LinkMenu(LINK, IMAGE),
        "Open image in new tab",
        IMAGE,
    )

    private fun openFromMenu(content: PageControlContent.LinkMenu, label: String, expectedUrl: String) {
        val actions = mutableListOf<String>()
        val context = RuntimeEnvironment.getApplication()
        val staging = UploadStaging(File(context.cacheDir, "link-menu-test"), context.contentResolver)
        compose.setContent {
            CombrayTheme {
                PageControlDialog(
                    control = PageControl(1, "example.test", content, offerBlocking = false),
                    staging = staging,
                    uploadScope = rememberCoroutineScope(),
                    onAnswer = { response, _ ->
                        assertEquals(ControlResponse.Dismiss, response)
                        actions += "dismiss"
                    },
                    onUploadFailed = {},
                    isWebAddress = { true },
                    onOpenInNewTab = { actions += it },
                    onSaveLink = { error("unexpected save") },
                    onShare = { error("unexpected share") },
                    onNotify = {},
                )
            }
        }

        compose.onNodeWithText("Open in background").assertDoesNotExist()
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss", expectedUrl), actions) }
    }

    private companion object {
        const val LINK = "https://example.test/article"
        const val IMAGE = "https://example.test/image.png"
    }
}
