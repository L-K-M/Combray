package ch.lkmc.combray.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import ch.lkmc.combray.core.integration.ScreenshotOmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A copy of the frame the engine surface shows, or why there is none. */
internal sealed interface FrameCopy {
    data class Copied(val bitmap: Bitmap) : FrameCopy

    data class Unavailable(val reason: ScreenshotOmission) : FrameCopy
}

/**
 * The frame on screen in [view], read back with PixelCopy. Only the surface's own buffer is
 * copied: the menu, sheets, snackbars and every other part of Combray's window are drawn
 * elsewhere and can never appear in it. Its bottom [coveredBottom] pixels lie under the bottom
 * controls and are left out. No engine handle is touched. The request is made on the main
 * thread, and a copy that does not finish within [timeoutMs] counts as failed.
 */
internal suspend fun copyFrame(view: SurfaceView, coveredBottom: Int = 0, timeoutMs: Long = COPY_TIMEOUT_MS): FrameCopy = withContext(Dispatchers.Main.immediate) {
    val height = view.height - coveredBottom.coerceAtLeast(0)
    // The surface can go at any time (Home, rotation): never let that crash the browser.
    if (view.width <= 0 || height <= 0 || !view.holder.surface.isValid) return@withContext FrameCopy.Unavailable(ScreenshotOmission.NOT_SHOWN)
    val bitmap = try {
        createBitmap(view.width, height, Bitmap.Config.ARGB_8888)
    } catch (tooLarge: OutOfMemoryError) {
        return@withContext FrameCopy.Unavailable(ScreenshotOmission.COPY_FAILED)
    }
    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { copied ->
            try {
                // A result after the timeout resumes a cancelled continuation, which ignores it.
                PixelCopy.request(view, Rect(0, 0, view.width, height), bitmap, { code -> copied.resume(code) }, Handler(Looper.getMainLooper()))
            } catch (failure: RuntimeException) {
                // IllegalArgumentException included: the surface became invalid since the check.
                Log.w(LOG_TAG, "copying the page frame failed: ${failure.javaClass.name}")
                copied.resume(PixelCopy.ERROR_UNKNOWN)
            }
        }
    }
    if (result == PixelCopy.SUCCESS) FrameCopy.Copied(bitmap) else FrameCopy.Unavailable(ScreenshotOmission.COPY_FAILED)
}

/** PixelCopy usually answers within a frame or two; a stuck compositor must not hold the report. */
private const val COPY_TIMEOUT_MS = 2_000L
private const val LOG_TAG = "CombrayReports"
