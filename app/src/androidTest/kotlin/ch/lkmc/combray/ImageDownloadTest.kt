package ch.lkmc.combray

import android.app.DownloadManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Real long-press input, Servo's image menu and Android's actual download provider. */
@RunWith(AndroidJUnit4::class)
class ImageDownloadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val manager get() = device.app.getSystemService(DownloadManager::class.java)

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun aLongPressDownloadsTheOriginalImageWithoutOpeningATab() = checkDownload(ImageFixture.PLAIN)

    @Test
    fun aLinkedImageDownloadsItsImageThroughARedirectNotItsLink() = checkDownload(ImageFixture.LINKED_REDIRECT)

    @Test
    fun aRefusedDownloadIsAFailureRatherThanASavedImage() = checkDownload(ImageFixture.REFUSED)

    private fun checkDownload(fixture: ImageFixture) {
        val key = "image${SystemClock.uptimeMillis()}"
        val page = "${Fixtures.BASE}/image-download.html?k=$key${fixture.query}"
        val image = "${Fixtures.BASE}/${fixture.path}?k=$key&fail=${fixture.failureFlag}"
        try {
            device.browser.openUrl(page, Placement.CURRENT_TAB)
            val reading = device.awaitPage(page).activeTab!!
            assertTrue("fixture image not painted", device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))
            longPressImage()
            val control = device.awaitState { it.pageControl != null }.pageControl!!
            val menu = control.content as PageControlContent.LinkMenu
            assertEquals(image, menu.image)
            assertEquals(if (fixture == ImageFixture.LINKED_REDIRECT) Fixtures.solid(Device.BLUE) else null, menu.link)
            compose.waitUntil(MENU_TIMEOUT_MS) { compose.onAllNodesWithText("Download image").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Download image").performClick()
            device.awaitState { it.pageControl == null }

            val id = awaitDownload(image, fixture.status)
            if (fixture.status == DownloadManager.STATUS_SUCCESSFUL) {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { it.readBytes() }
                assertEquals(IMAGE_BYTES, bytes.size)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(IMAGE_SHA256, digest)
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("image/png", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)))
                }
            }

            assertEquals(reading.id, device.browser.browserState.value.activeTab?.id)
            assertEquals(page, device.browser.browserState.value.activeTab?.url)
            assertEquals(1, device.browser.browserState.value.openTabCount)
            assertTrue("download replaced the page", device.matches(device.awaitScreen(Device.GREEN), Device.GREEN))

            val requests = Fixtures.state().getJSONArray("log")
            var sawBrowser = false
            var sawDownloader = false
            for (index in 0 until requests.length()) {
                val request = requests.getJSONObject(index)
                if (request.optString("service") != "image-download" || request.optString("key") != key) continue
                if (request.getBoolean("servo_agent")) {
                    assertTrue("the browser fixture cookie was not set", request.getBoolean("has_cookie"))
                    sawBrowser = true
                } else {
                    assertFalse("the downloader received browser cookies", request.getBoolean("has_cookie"))
                    assertFalse("the downloader received the page referrer", request.getBoolean("has_referrer"))
                    sawDownloader = true
                }
            }
            assertTrue("no browser image request", sawBrowser)
            assertTrue("no separate download request", sawDownloader)
        } finally {
            // Remove only this check's uniquely addressed requests and their files, even on failure.
            val ids = mutableListOf<Long>()
            manager.query(DownloadManager.Query()).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == image) {
                        ids += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    }
                }
            }
            if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
        }
    }

    private fun awaitDownload(address: String, expected: Int): Long {
        val deadline = SystemClock.uptimeMillis() + DOWNLOAD_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            manager.query(DownloadManager.Query()).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) != address) continue
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == expected) return cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        throw AssertionError("Android download ended with status $status and reason $reason; expected $expected")
                    }
                }
            }
            Thread.sleep(DOWNLOAD_POLL_MS)
        }
        throw AssertionError("Android download did not reach status $expected")
    }

    private fun longPressImage() {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.4f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        Thread.sleep(LONG_PRESS_MS)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + LONG_PRESS_MS, MotionEvent.ACTION_UP, x, y, 0))
    }

    private enum class ImageFixture(val query: String, val path: String, val failureFlag: Int, val status: Int) {
        PLAIN("", "download-image.png", 0, DownloadManager.STATUS_SUCCESSFUL),
        LINKED_REDIRECT("&linked=1&redirect=1", "download-image-redirect.png", 0, DownloadManager.STATUS_SUCCESSFUL),
        REFUSED("&fail=1", "download-image.png", 1, DownloadManager.STATUS_FAILED),
    }

    private companion object {
        const val IMAGE_BYTES = 69
        const val IMAGE_SHA256 = "71123a98501f2e9a093d18927e529396c01b7fbd7c41879b1c07bf4470263cd9"
        const val MENU_TIMEOUT_MS = 30_000L
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val DOWNLOAD_POLL_MS = 200L
        const val LONG_PRESS_MS = 1_500L
    }
}
