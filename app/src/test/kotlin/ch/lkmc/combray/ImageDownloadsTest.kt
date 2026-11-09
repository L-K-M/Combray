package ch.lkmc.combray

import android.app.Application
import android.app.DownloadManager
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ImageDownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val downloads = ImageDownloads(context)

    @Test
    fun aWebImageIsQueuedInDownloadsWithSystemNotifications() = runBlocking {
        assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("https://example.test/photo.png?key=secret#view"))
        val request = shadowOf(shadowOf(manager).getRequest(0))
        assertEquals("https://example.test/photo.png?key=secret", request.uri.toString())
        val destination = File(request.destination.path!!)
        assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), destination.parentFile)
        assertTrue(destination.name.matches(Regex("photo-[a-f0-9-]{36}\\.png")))
        assertEquals(destination.name, request.title.toString())
        assertFalse(request.title.toString().contains("secret"))
        assertEquals(context.getString(R.string.app_name), request.description.toString())
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request.notificationVisibility)
        assertTrue("no cookies, referrer or integration authorization", request.requestHeaders.isEmpty())
        assertNull("the response determines the MIME type", request.mimeType)
    }

    @Test
    fun unsupportedOrCredentialBearingAddressesNeverReachAndroid() = runBlocking {
        val addresses = listOf(
            "data:image/png;base64,AA==", "blob:https://example.test/id", "file:///private/image.png",
            "content://provider/image", "javascript:alert(1)", "intent://example.test", "mailto:a@example.test",
            "https://user:password@example.test/photo.png", "https://@example.test/photo.png",
            "https://example.test\\@private.test/photo.png", "https:///photo.png", "example.test/photo.png", "",
            "https://example.test/" + "x".repeat(9_000),
        )
        addresses.forEach { address ->
            assertEquals(ImageDownloadResult.UNSUPPORTED_ADDRESS, downloads.enqueue(address))
        }
        assertEquals(0, shadowOf(manager).requestCount)
    }

    @Test
    fun repeatedDownloadsDoNotOverwriteOneAnother() = runBlocking {
        repeat(2) { assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("http://localhost:8766/photo.png")) }
        val first = shadowOf(shadowOf(manager).getRequest(0))
        val second = shadowOf(shadowOf(manager).getRequest(1))
        assertNotEquals(first.destination, second.destination)
    }

    @Test
    fun namesCannotChooseDirectoriesOrKeepControlCharacters() = runBlocking {
        assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("https://example.test/%2F..%2F%5Csecret%00%E2%80%AE.PNG?token=hidden"))
        val destination = File(shadowOf(shadowOf(manager).getRequest(0)).destination.path!!)
        assertEquals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), destination.parentFile)
        assertTrue(destination.name.matches(Regex("secret-[a-f0-9-]{36}\\.png")))
    }

    @Test
    fun longNamesStayWithinTheFilesystemByteLimit() = runBlocking {
        assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("https://example.test/${"界".repeat(200)}.jpg"))
        val name = File(shadowOf(shadowOf(manager).getRequest(0)).destination.path!!).name
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun unknownExtensionsAreNotPresentedAsExecutableFiles() = runBlocking {
        assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("https://example.test/picture.apk"))
        val name = File(shadowOf(shadowOf(manager).getRequest(0)).destination.path!!).name
        assertTrue(name.matches(Regex("picture-[a-f0-9-]{36}")))
    }

    @Test
    fun emptyImageNamesHaveASafeFallback() = runBlocking {
        assertEquals(ImageDownloadResult.QUEUED, downloads.enqueue("https://example.test/?image=one"))
        val name = File(shadowOf(shadowOf(manager).getRequest(0)).destination.path!!).name
        assertTrue(name.matches(Regex("image-[a-f0-9-]{36}")))
    }

    @Test
    fun aProviderFailureDoesNotClaimTheDownloadWasQueued() = runBlocking {
        val unavailable = ImageDownloads({ throw SecurityException("not available") }, "Combray")
        assertEquals(ImageDownloadResult.UNAVAILABLE, unavailable.enqueue("https://example.test/photo.png"))
        val rejected = ImageDownloads({ -1L }, "Combray")
        assertEquals(ImageDownloadResult.UNAVAILABLE, rejected.enqueue("https://example.test/photo.png"))
    }

    @Test
    fun cancellationIsNotReportedAsADownloadFailure() {
        val cancelled = ImageDownloads({ throw CancellationException() }, "Combray")
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.enqueue("https://example.test/photo.png") }
        }
    }
}
