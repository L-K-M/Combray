package ch.lkmc.combray.download

import android.annotation.SuppressLint
import android.content.Context
import android.media.metrics.LogSessionId
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import ch.lkmc.combray.core.media.MediaNetwork
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.core.media.refusalIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Remuxes a progressive video or finite HLS (including separate audio) to one MP4. No page
 * scripts, cached Servo bytes or browser cookies are involved. Every load, including HLS keys,
 * goes through the player's per-hop network policy, never Media3's default/local data sources.
 */
@OptIn(UnstableApi::class)
internal class VideoExporter(context: Context, private val network: MediaNetwork) {
    private val context = context.applicationContext

    suspend fun export(request: PlaybackRequest, output: File, progress: (Int?) -> Unit) {
        val budget = VideoByteBudget(MAX_DOWNLOAD_BYTES)
        val http = OkHttpDataSource.Factory(network.callFactory(request))
        val factory = DataSource.Factory {
            val source = http.createDataSource()
            object : DataSource by source {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val reserved = budget.reserve(length)
                    var read = 0
                    try {
                        return source.read(buffer, offset, reserved).also { read = it.coerceAtLeast(0) }
                    } finally {
                        budget.refund(reserved - read)
                    }
                }
            }
        }
        val hls = request.url.pathSegments.last().endsWith(".m3u8", ignoreCase = true)
        try {
            exportOnce(request, output, factory, hls, progress)
        } catch (error: ExportException) {
            // Playlists often have extensionless addresses, or sit behind redirects. Exactly as
            // in the player, retry only an unrecognized progressive input, never a policy refusal.
            val unrecognized = generateSequence<Throwable>(error) { it.cause }.any { it is UnrecognizedInputFormatException }
            if (hls || !unrecognized || refusalIn(error) != null) throw error
            withContext(Dispatchers.IO) { if (output.exists() && !output.delete()) throw error }
            exportOnce(request, output, factory, hls = true, progress)
        }
    }

    private suspend fun exportOnce(
        request: PlaybackRequest,
        output: File,
        data: DataSource.Factory,
        hls: Boolean,
        progress: (Int?) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        val sources: MediaSource.Factory = if (hls) HlsMediaSource.Factory(data) else ProgressiveMediaSource.Factory(data)
        sources.setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }
        sources.setLoadErrorHandlingPolicy(DownloadLoadPolicy)
        val finiteSources = object : MediaSource.Factory by sources {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource = FiniteVideoSource(sources.createMediaSource(mediaItem))
        }
        val completion = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(ExoPlayerAssetLoader.Factory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, finiteSources))
            .setEncoderFactory(RemuxOnly)
            .setUsePlatformDiagnostics(false)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    // Media3's code only: its exception and causes can contain the media address.
                    Log.w("CombrayVideo", "export failed: ${exportException.errorCodeName}")
                    completion.completeExceptionally(exportException)
                }
            })
            .build()
        try {
            transformer.start(MediaItem.fromUri(request.url.toString()), output.absolutePath)
            val result = coroutineScope {
                val ticker = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (output.length() > MAX_DOWNLOAD_BYTES) throw VideoDownloadException(VideoDownloadFailure.TOO_LARGE)
                        progress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    completion.await()
                } finally {
                    ticker.cancel()
                }
            }
            if (result.videoFrameCount <= 0) throw VideoDownloadException(VideoDownloadFailure.FAILED)
            if (output.length() > MAX_DOWNLOAD_BYTES) throw VideoDownloadException(VideoDownloadFailure.TOO_LARGE)
        } finally {
            // This runs on Transformer's owner looper, including on cancellation. It must finish
            // before the caller deletes staging or publishes the completed file.
            transformer.cancel()
        }
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

/** A shared byte cap for concurrent HLS tracks and the one extensionless-playlist retry. */
internal class VideoByteBudget(limit: Long) {
    private val remaining = AtomicLong(limit)

    fun reserve(requested: Int): Int {
        while (true) {
            val available = remaining.get()
            if (available <= 0) throw VideoDownloadException(VideoDownloadFailure.TOO_LARGE)
            val count = minOf(available, requested.toLong()).toInt()
            if (remaining.compareAndSet(available, available - count)) return count
        }
    }

    fun refund(unused: Int) {
        remaining.addAndGet(unused.toLong())
    }
}

/** Live playlists cannot be honestly presented as a complete downloaded video. */
@OptIn(UnstableApi::class)
private class FiniteVideoSource(source: MediaSource) : WrappingMediaSource(source) {
    private var live = false

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        val window = Timeline.Window()
        live = (0 until newTimeline.windowCount).any { newTimeline.getWindow(it, window).isLive }
        if (!live) refreshSourceInfo(newTimeline)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        if (live) throw VideoDownloadException(VideoDownloadFailure.LIVE)
        super.maybeThrowSourceInfoRefreshError()
    }
}

/** Downloads never change codecs, consume a hardware encoder or silently lose a track. */
@SuppressLint("NewApi") // Only the nullable signature of Media3's EncoderFactory uses API-31 LogSessionId.
@OptIn(UnstableApi::class)
private object RemuxOnly : Codec.EncoderFactory {
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec = unsupported()
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec = unsupported()
    private fun unsupported(): Nothing = throw ExportException.createForUnexpected(UnsupportedOperationException("Video requires encoding"))
}

@OptIn(UnstableApi::class)
private object DownloadLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (refusalIn(loadErrorInfo.exception) != null || loadErrorInfo.exception is VideoDownloadException) C.TIME_UNSET
        else super.getRetryDelayMsFor(loadErrorInfo)
}
