package ch.lkmc.combray.ui

import android.graphics.Bitmap
import androidx.core.graphics.scale
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.integration.EncodedScreenshot
import ch.lkmc.combray.core.integration.ScreenshotOmission
import ch.lkmc.combray.core.integration.SiteReports
import java.io.ByteArrayOutputStream

/**
 * Why the engine surface does not show the page the user is reporting, or null when its frame
 * is the page. Reader view is drawn over the surface, so a copy would show the page under it,
 * which the user did not see; the engine's start and crash screens and session restore are
 * overlays as well.
 */
internal fun screenshotBlocker(state: BrowserState, surface: Boolean): ScreenshotOmission? {
    val tab = state.activeTab
    return when {
        state.reader != null -> ScreenshotOmission.READER_VIEW
        state.engine != EngineStatus.READY || state.session == SessionState.RESTORING -> ScreenshotOmission.NOT_SHOWN
        tab?.page == null || tab.crashed || !surface -> ScreenshotOmission.NOT_SHOWN
        else -> null
    }
}

/**
 * [frame] as a PNG within [maxBytes]. A larger one is halved in each direction and encoded
 * again, at most [MAX_DOWNSCALES] times; still too large, it is not sent (null).
 */
internal fun encodeScreenshot(
    frame: Bitmap,
    maxBytes: Int = SiteReports.MAX_SCREENSHOT_BYTES,
    encode: (Bitmap) -> ByteArray = ::encodePng,
): EncodedScreenshot? {
    var bitmap = frame
    for (downscales in 0..MAX_DOWNSCALES) {
        if (downscales > 0) {
            bitmap = bitmap.scale((bitmap.width / 2).coerceAtLeast(1), (bitmap.height / 2).coerceAtLeast(1))
        }
        val png = encode(bitmap)
        if (png.size <= maxBytes) return EncodedScreenshot(png, bitmap.width, bitmap.height, scaled = downscales > 0)
    }
    return null
}

/** [frame] at most [maxHeight] pixels tall, for the preview; a smaller frame is used as it is. */
internal fun previewOf(frame: Bitmap, maxHeight: Int = PREVIEW_MAX_HEIGHT_PX): Bitmap {
    if (frame.height <= maxHeight) return frame
    val width = (frame.width.toLong() * maxHeight / frame.height).toInt().coerceAtLeast(1)
    return frame.scale(width, maxHeight)
}

private fun encodePng(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    // PNG is lossless: the quality argument is ignored.
    check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
    return out.toByteArray()
}

/** Each step quarters the pixels; two steps reach a sixteenth of the frame. */
private const val MAX_DOWNSCALES = 2
private const val PNG_QUALITY = 100

/** Enough to judge the frame in the sheet; the preview is shown at most 240dp tall. */
private const val PREVIEW_MAX_HEIGHT_PX = 720
