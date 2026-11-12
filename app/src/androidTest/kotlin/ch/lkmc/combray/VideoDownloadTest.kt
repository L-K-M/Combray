package ch.lkmc.combray

import android.content.ContentUris
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.download.VideoDownloadFailure
import ch.lkmc.combray.download.VideoDownloadPhase
import ch.lkmc.combray.download.VideoDownloadState
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.UUID

/** Real Servo discovery, a native download action and standalone MP4s in Android's Downloads. */
@RunWith(AndroidJUnit4::class)
class VideoDownloadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val downloads get() = device.app.graph.videoDownloads
    private val resolver get() = device.app.contentResolver
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val key = UUID.randomUUID().toString().replace("-", "")
    private val saved = mutableListOf<String>()

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
        Fixtures.reset()
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { downloads.cancel(); downloads.dismiss() }
        for (name in saved) {
            resolver.query(COLLECTION, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { rows ->
                while (rows.moveToNext()) resolver.delete(ContentUris.withAppendedId(COLLECTION, rows.getLong(0)), null, null)
            }
        }
    }

    @Test
    fun theMenuDownloadsAProgressiveVideoWithoutLeavingItsPage() = checkDownload(Fixtures.video(key), audio = false)

    @Test
    fun anHlsVideoAndItsSeparateAudioBecomeOnePlayableMp4() = checkDownload(Fixtures.hls(key), audio = true)

    @Test
    fun anExtensionlessPlaylistStillDownloadsWithItsAudio() = checkDownload(Fixtures.hlsNoExt(key), audio = true)

    @Test
    fun aLivePlaylistIsNotSavedAsACompleteVideo() {
        startPlaylist("hls-live.m3u8")
        val result = awaitResult()
        assertEquals(VideoDownloadPhase.FAILED, result.phase)
        assertEquals(VideoDownloadFailure.LIVE, result.failure)
        assertNoUnfinishedDownload()
    }

    @Test
    fun aRefusedSegmentRedirectNeverProducesASavedVideo() {
        startPlaylist("hls-refused.m3u8")
        assertEquals(VideoDownloadPhase.FAILED, awaitResult().phase)
        assertTrue(Fixtures.state().getJSONObject("media").getInt("redirects") > 0)
        assertNoUnfinishedDownload()
    }

    @Test
    fun cancellingARealTransferRemovesPrivateOutputWithoutPublishing() {
        device.browser.openUrl(Fixtures.MEDIA, Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.MEDIA)
        val tab = device.awaitState { it.activeTab?.media?.size == 2 }.activeTab!!
        val video = tab.media.first { it.kind == ch.lkmc.combray.engine.MediaKind.VIDEO }
        val request = kotlinx.coroutines.runBlocking { device.browser.playbackRequest(tab.mediaPage!!, video.id) }!!
        instrumentation.runOnMainSync { assertTrue(downloads.start(request)) }
        val started = SystemClock.uptimeMillis() + 10_000
        while (Fixtures.state().getJSONObject("media").getInt("requests") == 0) {
            check(SystemClock.uptimeMillis() < started) { "download never reached the server" }
            Thread.sleep(50)
        }
        compose.onNodeWithText("Cancel").performClick()
        val deadline = SystemClock.uptimeMillis() + 15_000
        val staging = java.io.File(device.app.noBackupFilesDir, "video-downloads")
        while (downloads.current.value != null || staging.listFiles()?.isNotEmpty() == true) {
            check(SystemClock.uptimeMillis() < deadline) { "cancelled video left partial output" }
            Thread.sleep(50)
        }
        assertNoUnfinishedDownload()
    }

    private fun checkDownload(page: String, audio: Boolean) {
        // A real browser cookie is present, but never exported to the download client's requests.
        device.browser.openUrl(Fixtures.cookieSet("video-download"), Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.cookieSet("video-download"))
        device.browser.openUrl(page, Placement.CURRENT_TAB)
        device.awaitPage(page)
        val tab = device.awaitState { it.activeTab?.media?.isNotEmpty() == true }.activeTab!!
        val before = Fixtures.state().getJSONObject("media").getJSONObject(if (audio) "hls" else "clip")
        assertEquals("media was fetched before Download", 0, before.getInt("requests"))
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Download video").performClick()

        val result = awaitResult()
        assertEquals("download outcome: ${result.failure}", VideoDownloadPhase.SAVED, result.phase)
        val name = result.fileName!!
        saved += name
        resolver.query(COLLECTION, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.MIME_TYPE),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { rows ->
            assertTrue("no Downloads entry", rows.moveToFirst())
            assertEquals(0, rows.getInt(1))
            assertEquals("video/mp4", rows.getString(2))
            val uri = ContentUris.withAppendedId(COLLECTION, rows.getLong(0))
            resolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    // A file descriptor, not a URL: these samples can be read with no network.
                    extractor.setDataSource(descriptor.fileDescriptor)
                    val types = mutableSetOf<String>()
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME)!!
                        types += mime.substringBefore('/')
                        assertTrue("track has no duration", format.getLong(MediaFormat.KEY_DURATION) > 5_000_000)
                        extractor.selectTrack(index)
                        assertTrue("track has no samples", extractor.readSampleData(ByteBuffer.allocate(64 * 1024), 0) > 0)
                        extractor.unselectTrack(index)
                    }
                    assertTrue("missing video", "video" in types)
                    assertEquals("missing separate audio", audio, "audio" in types)
                } finally {
                    extractor.release()
                }
            }
        } ?: error("Downloads provider unavailable")

        assertEquals(tab.id, device.browser.browserState.value.activeTab?.id)
        assertEquals(page, device.browser.browserState.value.activeTab?.url)
        assertEquals(1, device.browser.browserState.value.openTabCount)
        val seen = Fixtures.state().getJSONObject("media").getJSONObject(if (audio) "hls" else "clip").getJSONArray("seen")
        assertTrue(seen.length() > 0)
        for (index in 0 until seen.length()) {
            val request = seen.getJSONObject(index)
            assertEquals("", request.getString("cookie"))
            assertEquals("${Fixtures.BASE}/", request.getString("referer"))
            assertTrue(request.getBoolean("servo_agent"))
        }
        val logcat = instrumentation.uiAutomation.executeShellCommand("logcat -d -v brief")
        val logs = android.os.ParcelFileDescriptor.AutoCloseInputStream(logcat).bufferedReader().use { it.readText() }
        assertFalse("a media address reached logcat", logs.contains("?k=$key"))
        assertNoUnfinishedDownload()
    }

    private fun startPlaylist(file: String) {
        val page = Fixtures.hls(key)
        device.browser.openUrl(page, Placement.CURRENT_TAB)
        device.awaitPage(page)
        val tab = device.awaitState { it.activeTab?.media?.isNotEmpty() == true }.activeTab!!
        val entry = tab.media.single()
        val request = kotlinx.coroutines.runBlocking { device.browser.playbackRequest(tab.mediaPage!!, entry.id) }!!
        val playlist = PlaybackRequest("${Fixtures.BASE}/media/$file?k=$key".toHttpUrl(), request.kind, request.origin, request.userAgent, request.name, request.site)
        instrumentation.runOnMainSync { assertTrue(downloads.start(playlist)) }
    }

    private fun awaitResult(): VideoDownloadState {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            val state = downloads.current.value
            if (state?.phase in listOf(VideoDownloadPhase.SAVED, VideoDownloadPhase.FAILED)) return state!!
            Thread.sleep(100)
        }
        error("Video download did not finish")
    }

    private fun assertNoUnfinishedDownload() {
        resolver.query(COLLECTION, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 1",
            arrayOf(device.app.packageName), null)!!.use { assertEquals("unfinished Downloads entry", 0, it.count) }
    }

    private companion object {
        val COLLECTION = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
}
