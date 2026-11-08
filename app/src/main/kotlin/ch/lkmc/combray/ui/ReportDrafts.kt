package ch.lkmc.combray.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ch.lkmc.combray.core.integration.EncodedScreenshot
import ch.lkmc.combray.core.integration.ReportDisplay
import ch.lkmc.combray.core.integration.ReportDraft
import ch.lkmc.combray.core.integration.ReportRequest
import ch.lkmc.combray.core.integration.ScreenshotOmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The page as it was when the user tapped Report this site (invariant 6): switching tabs or
 * loading another page afterwards does not change what the report describes.
 */
internal data class ReportSnapshot(
    val url: String,
    /** Null on an error page: its title is the engine's, not the page's. */
    val title: String?,
    val display: ReportDisplay,
    val loadFailed: Boolean,
    val crashed: Boolean,
    val zoomPercent: Int,
    val density: Float,
)

/** The screenshot of a draft as it is prepared after the copy. */
internal sealed interface ReportShot {
    /** Being scaled for the preview ([preview] null until then) and encoded. */
    data class Pending(val preview: ImageBitmap?) : ReportShot

    data class Ready(val preview: ImageBitmap, val screenshot: EncodedScreenshot) : ReportShot

    /** No screenshot can be sent, for [reason]. */
    data class Omitted(val reason: ScreenshotOmission) : ReportShot
}

/** Whether a draft is being stored, and what went wrong the last time it was sent. */
internal data class ReportProgress(val sending: Boolean = false, val problem: ReportProblem? = null)

/** What the preview's fields held when the user tapped Send. */
internal data class ReportForm(val url: String, val title: String, val comment: String, val includeScreenshot: Boolean)

/**
 * One report being previewed. [id] is a new lowercase UUIDv4 that becomes the report's id on
 * the service; [serviceHost] names the service configured when the report started. Send uses
 * the one configured at the tap on Send, which Settings opened over the sheet can change.
 */
internal class ReportDraftState(
    val id: String,
    val createdAt: Long,
    val snapshot: ReportSnapshot,
    val serviceHost: String,
    val shot: StateFlow<ReportShot>,
    internal val preparation: Job?,
) {
    /** Written only by [ReportDrafts]. */
    internal val progress = MutableStateFlow(ReportProgress())

    /**
     * The report to send for [form], or null while the screenshot the user wants is still being
     * encoded. Exactly one of screenshot and omission reason is set, as the service requires.
     */
    fun toDraft(form: ReportForm, current: ReportShot): ReportDraft? {
        val (screenshot, omitted) = when {
            current is ReportShot.Omitted -> null to current.reason
            !form.includeScreenshot -> null to ScreenshotOmission.USER
            current is ReportShot.Ready -> current.screenshot to null
            else -> return null
        }
        return ReportDraft(
            id = id,
            createdAt = createdAt,
            url = form.url,
            urlEdited = form.url != snapshot.url,
            title = form.title.trim().ifEmpty { null },
            comment = form.comment.trim().ifEmpty { null },
            display = snapshot.display,
            loadFailed = snapshot.loadFailed,
            crashed = snapshot.crashed,
            zoomPercent = snapshot.zoomPercent,
            density = snapshot.density,
            screenshot = screenshot,
            omitted = omitted,
        )
    }
}

/**
 * The report being previewed, process-scoped like the engine so rotation keeps it; the sheet
 * keeps only its id. Nothing here is durable: until Send stores the report, process death
 * loses the draft, and the sheet closes because its id no longer resolves. One draft at a time:
 * a new report replaces an abandoned one, so a lost sheet cannot pile up screenshots.
 */
internal class ReportDrafts(
    /** Outlives the screen: preparing and storing a report must finish when the sheet goes. */
    private val scope: CoroutineScope,
    private val submit: suspend (ReportDraft) -> ReportRequest,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val encode: (Bitmap) -> EncodedScreenshot? = ::encodeScreenshot,
) {
    private val slot = MutableStateFlow<ReportDraftState?>(null)

    val current: StateFlow<ReportDraftState?> = slot.asStateFlow()

    /** Starts a draft from the page [snapshot] and the [frame] copied at the tap. */
    fun start(createdAt: Long, snapshot: ReportSnapshot, serviceHost: String, frame: FrameCopy): ReportDraftState {
        val shot = MutableStateFlow<ReportShot>(
            when (frame) {
                is FrameCopy.Copied -> ReportShot.Pending(preview = null)
                is FrameCopy.Unavailable -> ReportShot.Omitted(frame.reason)
            },
        )
        val preparation = (frame as? FrameCopy.Copied)?.let { copied -> scope.launch(Dispatchers.Default) { prepare(copied, shot) } }
        val draft = ReportDraftState(newId(), createdAt, snapshot, serviceHost, shot.asStateFlow(), preparation)
        val abandoned = synchronized(this) { slot.value.also { slot.value = draft } }
        abandoned?.preparation?.cancel()
        return draft
    }

    /**
     * Stores [report] for delivery. Not cancellable: leaving the screen must not stop between
     * the screenshot file and the job. Null when [draft] is already being stored. On
     * [ReportRequest.Queued] the draft is done and removed; otherwise it stays with the problem.
     */
    fun send(draft: ReportDraftState, report: ReportDraft): Deferred<ReportRequest>? {
        synchronized(this) {
            if (draft.progress.value.sending) return null
            draft.progress.value = ReportProgress(sending = true)
        }
        return scope.async {
            val result = try {
                withContext(NonCancellable) { submit(report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The class only: a message can quote a path or a database detail.
                Log.w(LOG_TAG, "storing a report failed: ${failure.javaClass.name}")
                ReportRequest.NotStored
            }
            draft.progress.value = ReportProgress(problem = ReportProblem.of(result))
            if (result == ReportRequest.Queued) remove(draft.id)
            result
        }
    }

    /** Ends the draft [id] (sent, cancelled or dismissed); its screenshot is dropped. */
    fun remove(id: String) {
        val removed = synchronized(this) { slot.value?.takeIf { it.id == id }?.also { slot.value = null } }
        removed?.preparation?.cancel()
    }

    private suspend fun prepare(frame: FrameCopy.Copied, shot: MutableStateFlow<ReportShot>) {
        shot.value = try {
            val preview = previewOf(frame.bitmap).asImageBitmap()
            shot.value = ReportShot.Pending(preview)
            // A dismissed draft need not be encoded.
            currentCoroutineContext().ensureActive()
            encode(frame.bitmap)?.let { ReportShot.Ready(preview, it) } ?: ReportShot.Omitted(ScreenshotOmission.TOO_LARGE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // An Error is not a report either: an OutOfMemoryError from the scale, a
            // StackOverflowError from the encoder. A report without a screenshot is better than a
            // crashed browser.
            Log.w(LOG_TAG, "preparing the screenshot failed: ${failure.javaClass.name}")
            ReportShot.Omitted(ScreenshotOmission.COPY_FAILED)
        }
    }

    private companion object {
        const val LOG_TAG = "CombrayReports"
    }
}
