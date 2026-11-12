package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.PlayableMedia
import ch.lkmc.combray.engine.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class VideoDownloadMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aPageWithVideoOffersDownloadingWithoutRemovingPlay() {
        val page = ActiveTab("tab", "https://example.test/page", "Page", false, false, false, false, true, media = listOf(VIDEO))
        val labels = moreActions(page, ReaderDisplay.PAGE).map { RuntimeEnvironment.getApplication().getString(it.label) }
        assertTrue(labels.contains("Play audio or video"))
        assertTrue("a video page has no download action", labels.contains("Download video"))
    }

    @Test
    fun aVideoHasADownloadActionBesideItsPlayAction() {
        val played = mutableListOf<Long>()
        val downloaded = mutableListOf<Long>()
        compose.setContent { CombrayTheme { MediaEntries(listOf(VIDEO), onPlay = { played += it.id }, onDownload = { downloaded += it.id }) } }
        compose.onNodeWithContentDescription("Download video clip.mp4 from example.test").performClick()
        compose.runOnIdle {
            assertTrue(played.isEmpty())
            assertEquals(listOf(1L), downloaded)
        }
        compose.onNodeWithContentDescription("Play video clip.mp4 from example.test").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), played) }
    }

    @Test
    fun audioKeepsPlayWithoutPromisingAVideoDownload() {
        val audio = VIDEO.copy(kind = MediaKind.AUDIO)
        compose.setContent { CombrayTheme { MediaEntries(listOf(audio), onPlay = {}, onDownload = { error("audio download") }) } }
        compose.onNodeWithContentDescription("Play audio clip.mp4 from example.test").assertExists()
        compose.onNodeWithContentDescription("Download video clip.mp4 from example.test").assertDoesNotExist()
        val page = ActiveTab("tab", "https://example.test/page", "Page", false, false, false, false, true, media = listOf(audio))
        assertTrue(MoreAction.DOWNLOAD_VIDEO !in moreActions(page, ReaderDisplay.PAGE))
    }

    private companion object {
        val VIDEO = PlayableMedia(1, MediaKind.VIDEO, "clip.mp4", "example.test")
    }
}
