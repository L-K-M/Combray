package ch.lkmc.combray.download

import ch.lkmc.combray.core.media.MediaPolicy
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.engine.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VideoDownloadsTest {
    @Test
    fun onlyACompletedPublicationIsReportedAsSaved() = runTest {
        val finishedExport = CompletableDeferred<Unit>()
        val finishedCopy = CompletableDeferred<Unit>()
        val files = Files().apply { beforePublish = { finishedCopy.await() } }
        val seen = mutableListOf<PlaybackRequest>()
        val downloads = VideoDownloads(this, files) { request, _, progress ->
            seen += request
            progress(35)
            finishedExport.await()
        }
        assertTrue(downloads.start(REQUEST))
        assertFalse(downloads.start(request("other.mp4")))
        runCurrent()
        assertEquals(listOf(REQUEST), seen)
        assertEquals(35, downloads.current.value?.progress)
        assertTrue(files.published.isEmpty())

        finishedExport.complete(Unit)
        runCurrent()
        assertEquals(VideoDownloadPhase.SAVING, downloads.current.value?.phase)
        downloads.cancel() // A publication already in progress finishes atomically.
        finishedCopy.complete(Unit)
        advanceUntilIdle()
        assertEquals(VideoDownloadPhase.SAVED, downloads.current.value?.phase)
        assertEquals("video.mp4", downloads.current.value?.fileName)
        assertEquals(listOf(files.file), files.published)
        assertEquals(listOf(files.file), files.discarded)
    }

    @Test
    fun cancellationStopsTheExporterBeforeDiscardingAndNeverPublishes() = runTest {
        val events = mutableListOf<String>()
        val files = Files().apply { beforeDiscard = { events += "discard" } }
        val downloads = VideoDownloads(this, files) { _, _, _ ->
            try { awaitCancellation() } finally { events += "export stopped" }
        }
        downloads.start(REQUEST)
        runCurrent()
        downloads.cancel()
        advanceUntilIdle()
        assertEquals(listOf("export stopped", "discard"), events)
        assertNull(downloads.current.value)
        assertTrue(files.published.isEmpty())
        assertTrue(downloads.start(REQUEST))
        runCurrent()
        downloads.cancel()
        advanceUntilIdle()
    }

    @Test
    fun anExportFailureDiscardsThePartialFileWithoutExposingItsAddress() = runTest {
        val files = Files()
        val downloads = VideoDownloads(this, files) { _, _, _ -> throw IOException("https://secret.test/?token=private") }
        downloads.start(REQUEST)
        advanceUntilIdle()
        assertEquals(VideoDownloadFailure.FAILED, downloads.current.value?.failure)
        assertFalse(downloads.current.value.toString().contains("private"))
        assertEquals(listOf(files.file), files.discarded)
        assertTrue(files.published.isEmpty())
        downloads.dismiss()
        assertNull(downloads.current.value)
    }

    @Test
    fun aFailedCopyDoesNotClaimTheVideoWasSaved() = runTest {
        val files = Files().apply { beforePublish = { throw IOException("provider failed") } }
        val downloads = VideoDownloads(this, files) { _, _, _ -> }
        downloads.start(REQUEST)
        advanceUntilIdle()
        assertEquals(VideoDownloadPhase.FAILED, downloads.current.value?.phase)
        assertEquals(listOf(files.file), files.discarded)
    }

    @Test
    fun aStalledExportIsBoundedAndNotReportedAsSaved() = runTest {
        val files = Files()
        val downloads = VideoDownloads(this, files) { _, _, _ -> awaitCancellation() }
        downloads.start(REQUEST)
        runCurrent()
        advanceTimeBy(20 * 60_000L + 1)
        runCurrent()
        assertEquals(VideoDownloadFailure.TIMEOUT, downloads.current.value?.failure)
        assertTrue(files.published.isEmpty())
        assertEquals(listOf(files.file), files.discarded)
    }

    @Test
    fun refusedMediaNeverPreparesStorageOrStartsAnExport() = runTest {
        val files = Files()
        val downloads = VideoDownloads(this, files) { _, _, _ -> error("must not fetch") }
        downloads.start(request("http://localhost/video.mp4"))
        advanceUntilIdle()
        assertEquals(VideoDownloadPhase.FAILED, downloads.current.value?.phase)
        assertEquals(0, files.prepared)
    }

    private class Files : VideoDownloadFiles {
        val file = File("/staging/video.mp4")
        var prepared = 0
        var beforePublish: suspend () -> Unit = {}
        var beforeDiscard: () -> Unit = {}
        val published = mutableListOf<File>()
        val discarded = mutableListOf<File>()
        override suspend fun prepare(): File = file.also { prepared++ }
        override suspend fun publish(file: File): String {
            beforePublish()
            published += file
            return file.name
        }
        override suspend fun discard(file: File) {
            beforeDiscard()
            discarded += file
        }
    }

    private companion object {
        val REQUEST = request("clip.mp4")
        fun request(path: String) = PlaybackRequest(
            (if (path.startsWith("http")) path else "https://example.test/$path").toHttpUrl(),
            MediaKind.VIDEO,
            MediaPolicy.origin("https://example.test/page", "https://example.test/"),
            "test-agent", "clip.mp4", "example.test",
        )
    }
}
