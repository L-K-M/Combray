package ch.lkmc.combray.ui

import android.app.Application
import android.graphics.Bitmap
import ch.lkmc.combray.core.integration.ReportDisplay
import ch.lkmc.combray.core.integration.ReportDraft
import ch.lkmc.combray.core.integration.ReportRequest
import ch.lkmc.combray.core.integration.ScreenshotOmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReportDraftsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val submitted = mutableListOf<ReportDraft>()
    private var answer: suspend (ReportDraft) -> ReportRequest = { ReportRequest.Queued }
    private var ids = 0
    private val drafts = ReportDrafts(scope, { report -> submitted += report; answer(report) }, newId = { "draft-${++ids}" })

    @After
    fun stop() = scope.cancel()

    @Test
    fun withoutAFrameTheDraftSaysWhy() {
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.PAGE_CHANGED))
        assertEquals(ReportShot.Omitted(ScreenshotOmission.PAGE_CHANGED), draft.shot.value)
        assertSame(draft, drafts.current.value)
        assertEquals(HOST, draft.serviceHost)
    }

    @Test
    fun aCopiedFrameBecomesAPreviewAndAScreenshot() = runBlocking {
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Copied(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)))
        val ready = withTimeout(TIMEOUT_MS) { draft.shot.first { it !is ReportShot.Pending } } as ReportShot.Ready
        assertEquals(40 to 80, ready.screenshot.width to ready.screenshot.height)
        assertEquals(40 to 80, ready.preview.width to ready.preview.height)
        val report = checkNotNull(draft.toDraft(FORM, ready))
        assertSame(ready.screenshot, report.screenshot)
        assertNull(report.omitted)
    }

    /** One draft at a time: a lost sheet cannot pile up screenshots. */
    @Test
    fun aNewReportReplacesAnAbandonedOne() {
        val first = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.NOT_SHOWN))
        val second = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.NOT_SHOWN))
        assertSame(second, drafts.current.value)
        // Ending the abandoned one does not end the new one.
        drafts.remove(first.id)
        assertSame(second, drafts.current.value)
        drafts.remove(second.id)
        assertNull(drafts.current.value)
    }

    @Test
    fun aQueuedReportEndsItsDraft() = runBlocking {
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val report = checkNotNull(draft.toDraft(FORM, draft.shot.value))
        assertEquals(ReportRequest.Queued, checkNotNull(drafts.send(draft, report)).await())
        assertEquals(listOf(report), submitted)
        assertNull(drafts.current.value)
        assertEquals(ReportProgress(), draft.progress.value)
    }

    @Test
    fun aReportThatWasNotQueuedKeepsItsDraftAndSaysWhy() = runBlocking {
        answer = { ReportRequest.TooMany }
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val report = checkNotNull(draft.toDraft(FORM, draft.shot.value))
        assertEquals(ReportRequest.TooMany, checkNotNull(drafts.send(draft, report)).await())
        assertSame(draft, drafts.current.value)
        assertEquals(ReportProgress(sending = false, problem = ReportProblem.TOO_MANY), draft.progress.value)
    }

    @Test
    fun anEarlierStoredReportKeepsItsDraftAndOffersDeliverySettings() = runBlocking {
        answer = { ReportRequest.PreviouslyStored }
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val report = checkNotNull(draft.toDraft(FORM, draft.shot.value))
        assertEquals(ReportRequest.PreviouslyStored, checkNotNull(drafts.send(draft, report)).await())
        assertSame(draft, drafts.current.value)
        assertEquals(ReportProblem.PREVIOUSLY_STORED, draft.progress.value.problem)
        assertTrue(ReportProblem.PREVIOUSLY_STORED.offersSettings)
    }

    @Test
    fun aFailingStoreIsNotStoredRatherThanACrash() = runBlocking {
        answer = { throw IllegalStateException("disk") }
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val report = checkNotNull(draft.toDraft(FORM, draft.shot.value))
        assertEquals(ReportRequest.NotStored, checkNotNull(drafts.send(draft, report)).await())
        assertEquals(ReportProblem.NOT_STORED, draft.progress.value.problem)
    }

    /** A second tap while storing does not store the report twice. */
    @Test
    fun aReportIsSentOnceAtATime() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        answer = { gate.await(); ReportRequest.Queued }
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val report = checkNotNull(draft.toDraft(FORM, draft.shot.value))
        val first = assertSending(drafts.send(draft, report))
        assertTrue(draft.progress.value.sending)
        assertNull(drafts.send(draft, report))
        gate.complete(Unit)
        assertEquals(ReportRequest.Queued, first.await())
        assertEquals(1, submitted.size)
    }

    /** Leaving the screen ends the draft, never a report already being stored. */
    @Test
    fun removingADraftWhileStoringStillStoresIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        answer = { gate.await(); ReportRequest.Queued }
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.READER_VIEW))
        val stored = assertSending(drafts.send(draft, checkNotNull(draft.toDraft(FORM, draft.shot.value))))
        drafts.remove(draft.id)
        gate.complete(Unit)
        assertEquals(ReportRequest.Queued, stored.await())
        assertEquals(1, submitted.size)
    }

    @Test
    fun theDraftCarriesTheSnapshotAndTheFormAsShown() {
        val draft = drafts.start(CREATED_AT, SNAPSHOT, HOST, FrameCopy.Unavailable(ScreenshotOmission.NOT_SHOWN))
        val report = checkNotNull(draft.toDraft(ReportForm(SNAPSHOT.url + "?x", "  ", "\n Broken menu \n", includeScreenshot = true), draft.shot.value))
        assertEquals("draft-1", report.id)
        assertEquals(CREATED_AT, report.createdAt)
        assertTrue(report.urlEdited)
        assertNull(report.title)
        assertEquals("Broken menu", report.comment)
        assertEquals(ReportDisplay.PAGE, report.display)
        assertEquals(125, report.zoomPercent)
        assertEquals(2.75f, report.density)
        assertNull(report.screenshot)
        assertEquals(ScreenshotOmission.NOT_SHOWN, report.omitted)
    }

    private fun <T> assertSending(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val CREATED_AT = 1_762_247_564_123L
        const val HOST = "reports.example.org"
        const val TIMEOUT_MS = 10_000L
        val SNAPSHOT = ReportSnapshot(
            url = "https://example.org/article",
            title = "Article",
            display = ReportDisplay.PAGE,
            loadFailed = false,
            crashed = false,
            zoomPercent = 125,
            density = 2.75f,
        )
        val FORM = ReportForm(SNAPSHOT.url, "Article", "", includeScreenshot = true)
    }
}
