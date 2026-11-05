package ch.lkmc.combray.core.integration

import kotlinx.serialization.Serializable

/**
 * The versions a report names, read once at start (BuildConfig and `android.os.Build`). Sent
 * with every report and shown in its preview; nothing else about the device is sent.
 */
@Serializable
data class ReportEnvironment(
    val appVersion: String,
    val appBuild: Int,
    val buildType: String,
    val engineVersion: String,
    val engineRevision: String,
    val androidRelease: String,
    val androidSdk: Int,
)

/** What was on screen when the user reported the page. */
enum class ReportDisplay(internal val wire: String) {
    PAGE("page"),

    /** Reader view covered the page: no screenshot of it is taken. */
    READER("reader"),
}

/** Why a report has no screenshot. [wire] is the report service's `screenshot_omitted` value. */
enum class ScreenshotOmission(internal val wire: String) {
    /** The user unticked it in the preview. */
    USER("user"),
    READER_VIEW("reader_view"),

    /** No page frame was on screen (engine not ready, crashed tab, no surface). */
    NOT_SHOWN("not_shown"),

    /** Another document was on screen by the time the frame was copied. */
    PAGE_CHANGED("page_changed"),
    COPY_FAILED("copy_failed"),

    /** Still over the phone's screenshot limit after scaling down. */
    TOO_LARGE("too_large"),
}

/**
 * What a queued report holds, immutable once queued. The screenshot itself is a file named
 * after [reportId] in [ReportFiles]; [ScreenshotRef] records its size and digest, so the
 * request can only ever carry the bytes that were captured. Never a path or a token.
 */
@Serializable
internal data class ReportPayload(
    val reportId: String,
    val createdAt: Long,
    val url: String,
    val urlEdited: Boolean,
    val title: String? = null,
    val comment: String? = null,
    val display: ReportDisplay,
    val loadFailed: Boolean,
    val crashed: Boolean,
    val environment: ReportEnvironment,
    val zoomPercent: Int? = null,
    val density: Float? = null,
    val screenshot: ScreenshotRef? = null,
    val omitted: ScreenshotOmission? = null,
)

@Serializable
internal data class ScreenshotRef(
    val bytes: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
    val scaled: Boolean,
)
