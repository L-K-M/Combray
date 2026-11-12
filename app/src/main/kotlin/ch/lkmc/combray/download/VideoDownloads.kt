package ch.lkmc.combray.download

import androidx.annotation.MainThread
import ch.lkmc.combray.core.media.MediaPolicy
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.engine.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

internal enum class VideoDownloadPhase { DOWNLOADING, SAVING, SAVED, FAILED }
internal enum class VideoDownloadFailure { FAILED, LIVE, TOO_LARGE, TIMEOUT }

/** Only transfer state, never a page or media address. */
internal data class VideoDownloadState(
    val phase: VideoDownloadPhase,
    val progress: Int? = null,
    val fileName: String? = null,
    val failure: VideoDownloadFailure? = null,
)

internal class VideoDownloadException(val failure: VideoDownloadFailure) : IOException(failure.name)

internal interface VideoDownloadFiles {
    suspend fun prepare(): File
    suspend fun publish(file: File): String
    suspend fun discard(file: File)
}

/**
 * One explicit, foreground download at a time. Its immutable request survives tab changes and
 * Activity recreation, not process death. Nothing says "saved" until a complete file is public.
 * Cancellation finishes the exporter before removing its private output. Publication is a short,
 * non-cancellable commit; Cancel is unavailable during that step.
 *
 * Owned by the main thread, as are Transformer and the UI that invokes these methods.
 */
internal class VideoDownloads(
    private val scope: CoroutineScope,
    private val files: VideoDownloadFiles,
    private val export: suspend (PlaybackRequest, File, (Int?) -> Unit) -> Unit,
) {
    private val state = MutableStateFlow<VideoDownloadState?>(null)
    val current = state.asStateFlow()
    private var job: Job? = null

    @MainThread
    fun start(request: PlaybackRequest): Boolean {
        if (job?.isActive == true) return false
        state.value = VideoDownloadState(VideoDownloadPhase.DOWNLOADING)
        job = scope.launch {
            var file: File? = null
            try {
                if (request.kind != MediaKind.VIDEO || MediaPolicy.check(request.origin, request.url) != null) {
                    throw VideoDownloadException(VideoDownloadFailure.FAILED)
                }
                val output = files.prepare()
                file = output
                withTimeout(DOWNLOAD_TIMEOUT_MS) {
                    export(request, output) { progress ->
                        state.value = VideoDownloadState(VideoDownloadPhase.DOWNLOADING, progress?.coerceIn(0, 100))
                    }
                }
                withContext(NonCancellable) {
                    state.value = VideoDownloadState(VideoDownloadPhase.SAVING)
                    val name = files.publish(output)
                    state.value = VideoDownloadState(VideoDownloadPhase.SAVED, fileName = name)
                }
            } catch (_: TimeoutCancellationException) {
                failed(VideoDownloadFailure.TIMEOUT)
            } catch (cancelled: CancellationException) {
                state.value = null
                throw cancelled
            } catch (error: Exception) {
                // A transport/provider exception can contain the full address. Show categories only.
                val reason = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<VideoDownloadException>().firstOrNull()?.failure
                failed(reason ?: VideoDownloadFailure.FAILED)
            } finally {
                withContext(NonCancellable) {
                    file?.let { files.discard(it) }
                }
            }
        }
        return true
    }

    @MainThread
    fun cancel() {
        if (state.value?.phase == VideoDownloadPhase.DOWNLOADING) job?.cancel()
    }

    @MainThread
    fun dismiss() {
        if (state.value?.phase in listOf(VideoDownloadPhase.SAVED, VideoDownloadPhase.FAILED)) state.value = null
    }

    private fun failed(reason: VideoDownloadFailure) {
        state.value = VideoDownloadState(VideoDownloadPhase.FAILED, failure = reason)
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L
    }
}
