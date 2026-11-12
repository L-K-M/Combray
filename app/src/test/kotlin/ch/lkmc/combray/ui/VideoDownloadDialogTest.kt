package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import ch.lkmc.combray.download.VideoDownloadPhase
import ch.lkmc.combray.download.VideoDownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h480dp")
class VideoDownloadDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cancelStopsTheTransferRatherThanDismissingItsProgress() {
        var cancelled = 0
        var dismissed = 0
        compose.setContent {
            CombrayTheme {
                VideoDownloadDialog(VideoDownloadState(VideoDownloadPhase.DOWNLOADING), { cancelled++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, dismissed) }
    }

    @Test
    fun theLocalPublicationCannotBeCancelledHalfwayThrough() {
        compose.setContent {
            CombrayTheme {
                VideoDownloadDialog(VideoDownloadState(VideoDownloadPhase.SAVING), { error("cancelled publication") }, {})
            }
        }
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun aLongFailureMessageScrollsWhileDoneStaysReachableAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                CombrayTheme {
                    VideoDownloadDialog(VideoDownloadState(VideoDownloadPhase.FAILED), {}, {})
                }
            }
        }
        compose.onNodeWithText("Could not download this video. Protected videos and unsupported formats cannot be saved.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
