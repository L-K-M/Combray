package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.lkmc.combray.R
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The long-press menu for an image: a web image offers Download image, which dismisses the
 * control before handing the exact address on. A linked image downloads the image, not the
 * link; data, blob and file images keep Copy image address and get no download action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class ImageDownloadMenuTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    private val answers = mutableListOf<ControlResponse>()
    private val downloads = mutableListOf<String>()
    private val openedInBackground = mutableListOf<String>()
    private val events = mutableListOf<String>()

    @Test
    fun aWebImageOffersDownloadAndDismissesBeforeDeliveringItOnce() {
        val image = "https://example.test/images/picture.png?size=large"
        showMenu(link = null, image = image)

        compose.onNodeWithText(context.getString(R.string.image_download)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ControlResponse.Dismiss), answers)
            assertEquals(listOf(image), downloads)
            assertEquals(listOf("dismiss", "download"), events)
        }
    }

    @Test
    fun aLinkedImageDownloadsTheImageAddressNotTheLink() {
        val link = "https://example.test/articles/garden"
        val image = "https://example.test/images/picture.png?size=large"
        showMenu(link = link, image = image)

        compose.onNodeWithText(context.getString(R.string.image_download)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ControlResponse.Dismiss), answers)
            assertEquals(listOf(image), downloads)
            assertTrue("the link was opened instead of downloading the image", openedInBackground.isEmpty())
        }
    }

    @Test
    fun aLinkWithoutAnImageKeepsItsBackgroundActionWithoutDownload() {
        val link = "https://example.test/articles/garden"
        showMenu(link = link, image = null)

        compose.onNodeWithText(context.getString(R.string.image_download)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.link_open_background)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ControlResponse.Dismiss), answers)
            assertEquals(listOf(link), openedInBackground)
            assertTrue(downloads.isEmpty())
        }
    }

    @Test
    fun aDataImageHasNoDownloadButKeepsCopyImageAddress() {
        unsupportedImageHasNoDownload("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==")
    }

    @Test
    fun aBlobImageHasNoDownloadButKeepsCopyImageAddress() {
        unsupportedImageHasNoDownload("blob:https://example.test/9f3c0a5e-1b2c-4d5e-8f9a-0b1c2d3e4f5a")
    }

    @Test
    fun aFileImageHasNoDownloadButKeepsCopyImageAddress() {
        unsupportedImageHasNoDownload("file:///storage/emulated/0/Pictures/example.png")
    }

    private fun unsupportedImageHasNoDownload(image: String) {
        showMenu(link = null, image = image)

        compose.onNodeWithText(context.getString(R.string.image_download)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.image_copy)).assertExists()
        compose.runOnIdle { assertTrue(downloads.isEmpty()) }
    }

    private fun showMenu(link: String?, image: String?) {
        val staging = UploadStaging(File(context.cacheDir, "image-download-menu-test"), context.contentResolver)
        compose.setContent {
            CombrayTheme {
                PageControlDialog(
                    control = PageControl(
                        1,
                        "example.test",
                        PageControlContent.LinkMenu(link = link, image = image),
                        offerBlocking = false,
                    ),
                    staging = staging,
                    uploadScope = rememberCoroutineScope(),
                    onAnswer = { response, _ ->
                        answers += response
                        if (response == ControlResponse.Dismiss) events += "dismiss"
                    },
                    onUploadFailed = {},
                    // The same address policy the browser uses for typed and tapped addresses.
                    isWebAddress = { url -> AddressPolicy.interpret(url).primary is AddressDecision.Navigate },
                    onOpenInNewTab = { error("Download must not open a foreground tab") },
                    onOpenInBackground = { url ->
                        openedInBackground += url
                        events += "background"
                    },
                    onSaveLink = { error("Download must not save to Karakeep") },
                    onShare = { error("Download must not share the address") },
                    onDownloadImage = { url ->
                        downloads += url
                        events += "download"
                    },
                    onNotify = {},
                )
            }
        }
    }
}
