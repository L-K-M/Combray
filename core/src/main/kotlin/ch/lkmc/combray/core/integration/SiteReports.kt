package ch.lkmc.combray.core.integration

import android.util.Log
import androidx.room.withTransaction
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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

/** A PNG of the page as it was on screen, encoded by the app. */
class EncodedScreenshot(val png: ByteArray, val width: Int, val height: Int, val scaled: Boolean)

/**
 * Everything the user saw in the report preview when they tapped Send. [id] is a lowercase
 * UUIDv4 made when the report was started; it becomes the report's id on the service, so a
 * repeated delivery is recognised there. Exactly one of [screenshot] and [omitted] is set.
 */
data class ReportDraft(
    val id: String,
    val createdAt: Long,
    val url: String,
    val urlEdited: Boolean,
    val title: String?,
    val comment: String?,
    val display: ReportDisplay,
    val loadFailed: Boolean,
    val crashed: Boolean,
    val zoomPercent: Int?,
    val density: Float?,
    val screenshot: EncodedScreenshot?,
    val omitted: ScreenshotOmission?,
)

/** The part of a [ReportDraft] that cannot be sent as it is. */
enum class ReportField { ID, URL, COMMENT, SCREENSHOT, DETAILS }

sealed interface ReportRequest {
    /** Stored durably (screenshot file and job); it is sent when the service can be reached. */
    data object Queued : ReportRequest

    /** No report service is set up (or it was removed before the report was stored). */
    data object NotConfigured : ReportRequest

    /** [SiteReports.MAX_UNSENT_REPORTS] reports are waiting already; Settings lists them. */
    data object TooMany : ReportRequest

    /** The device could not store the report; nothing was queued and no file is left. */
    data object NotStored : ReportRequest

    data class Invalid(val field: ReportField) : ReportRequest
}

/**
 * More › Report this site: reports go to the owner's own report service
 * (services/site-reports/README.md), where Paseo's agents read them.
 *
 * A report is durable before [submit] says it is queued: its screenshot file is written first,
 * then the job that references it, so a stored job never points to a file that was not
 * written. Delivery checks the file against the size and digest recorded at that moment, so the
 * request carries exactly what the preview showed. Files of finished or discarded reports are
 * swept around every drain.
 */
class SiteReports internal constructor(
    private val database: CombrayDatabase,
    secrets: SecretStore,
    transport: HttpTransport,
    private val clock: Clock,
    private val outbox: Outbox,
    private val files: ReportFiles,
    private val environment: ReportEnvironment,
    private val kick: () -> Unit,
) {
    private val dao = database.integrations()
    private val routes = ServiceRoutes(transport)

    /** Configuration changes and dispatch read/write the service row and its token together. */
    private val config = Mutex()
    private val credentials = ServiceCredentials(secrets)

    /**
     * Screenshot files and the jobs that reference them change together: a sweep never runs
     * between a file's write and its job's insert, so it cannot delete a file being queued.
     */
    private val filesLock = Mutex()

    val service: Flow<ServiceEntity?> = dao.observeService(ServiceKind.SITE_REPORTS)

    /**
     * Saves the service's address, an optional alternate address and, if given, a new reporter
     * token. As for the other services, a new main address parks unsent reports until the user
     * sends them there or discards them, and a new token pauses them until resumed.
     */
    suspend fun configure(rootInput: String, token: String?, alternateInput: String? = null): Configured {
        val addresses = when (val checked = ServiceUrls.normalizeAddresses(rootInput, alternateInput, REPORTS_SUFFIXES)) {
            is ServiceAddresses.Invalid -> return Configured.InvalidRoot(checked.problem, checked.field)
            is ServiceAddresses.Valid -> checked
        }
        val secret = token?.trim()?.takeIf { it.isNotEmpty() }
        if (secret != null && !isHeaderSafe(secret)) return Configured.InvalidCredential
        // Not cancellable: leaving the screen must not stop between the token and the configuration.
        return withContext(NonCancellable) {
            config.withLock {
                val previous = dao.service(ServiceKind.SITE_REPORTS)
                // The stored identity is opaque: an equivalent re-spelling of the same destination
                // keeps the fingerprint, token generation and queued reports bound to it.
                val keepsServer = previous != null && ServiceUrls.sameDestination(previous.baseUrl, addresses.root)
                val fingerprint = if (previous != null && keepsServer) previous.fingerprint else addresses.fingerprint
                // A stored token belongs to the addresses it was entered for, never to a new one.
                val knownAddresses = keepsServer &&
                    (addresses.alternate == null || ServiceUrls.sameDestination(addresses.alternate, previous?.alternateUrl))
                val stored = credentials.read(SecretName.SITE_REPORTS_TOKEN, fingerprint) != null
                if (secret == null && !(knownAddresses && stored)) return@withLock Configured.MissingCredential
                // Same server, stored token kept: queued reports keep going. At most the alternate was dropped or re-spelled.
                if (secret == null) {
                    if (previous != null && previous.alternateUrl != addresses.alternate) {
                        dao.setService(previous.copy(alternateUrl = addresses.alternate, updatedAt = clock.now()))
                    }
                    return@withLock Configured.Saved
                }
                if (!credentials.write(SecretName.SITE_REPORTS_TOKEN, fingerprint, secret)) return@withLock Configured.CredentialNotStored
                database.withTransaction {
                    val current = ServiceEntity(
                        kind = ServiceKind.SITE_REPORTS,
                        baseUrl = addresses.root,
                        fingerprint = fingerprint,
                        generation = outbox.nextGeneration(ServiceKind.SITE_REPORTS, previous),
                        captureEnabled = false,
                        updatedAt = clock.now(),
                        alternateUrl = addresses.alternate,
                    )
                    dao.setService(current)
                    outbox.onServiceChanged(previous, current)
                }
                Configured.Saved
            }
        }
    }

    /** Removes the service and its token; unsent reports are cancelled and their files deleted. */
    suspend fun remove() {
        withContext(NonCancellable) {
            config.withLock {
                database.withTransaction {
                    val previous = dao.service(ServiceKind.SITE_REPORTS)
                    dao.deleteService(ServiceKind.SITE_REPORTS)
                    outbox.onServiceChanged(previous, null)
                }
                credentials.delete(SecretName.SITE_REPORTS_TOKEN)
            }
            sweep()
        }
    }

    /** Read-only request with the stored address and token. Null when not set up. */
    suspend fun check(): ConnectionCheck? {
        val (service, token) = snapshot()
        service ?: return null
        token ?: return ConnectionCheck.CredentialMissing
        return withContext(Dispatchers.IO) {
            routes.call(service) { transport, root -> SiteReportsClient(transport).checkConnection(root, token) }
        }
    }

    /**
     * Queues [draft] for the report service configured now. Returns [ReportRequest.Queued] only
     * once the screenshot file and the job are both stored (invariant 7); on any other result
     * nothing of this report is left on the device.
     */
    suspend fun submit(draft: ReportDraft): ReportRequest {
        val payload = when (val checked = payloadOf(draft, environment)) {
            is Checked.Invalid -> return ReportRequest.Invalid(checked.field)
            is Checked.Valid -> checked.payload
        }
        // Not cancellable: leaving the screen must not stop between the file and the job.
        val result = withContext(NonCancellable) {
            try {
                val service = dao.service(ServiceKind.SITE_REPORTS) ?: return@withContext ReportRequest.NotConfigured
                filesLock.withLock { store(service, payload, draft.screenshot?.png) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The class only: a message can quote a path or a database detail.
                Log.w(LOG_TAG, "storing a report failed: ${failure.javaClass.name}")
                ReportRequest.NotStored
            }
        }
        if (result == ReportRequest.Queued) kick()
        return result
    }

    private suspend fun store(service: ServiceEntity, payload: ReportPayload, png: ByteArray?): ReportRequest {
        val id = payload.reportId
        // The same draft sent twice (a repeated tap): its job exists and holds its file.
        if (id in dao.reportIdsHoldingFiles()) return ReportRequest.Queued
        if (dao.unsentReports() >= MAX_UNSENT_REPORTS) return ReportRequest.TooMany

        val enqueued = try {
            png?.let { withContext(Dispatchers.IO) { files.write(id, it) } }
            outbox.enqueue(JobKind.SITE_REPORT, service, Binding.CURRENT, dedupeKey = id, payload = json.encodeToString(ReportPayload.serializer(), payload), supersede = false)
        } catch (failure: Exception) {
            // No job references the file: the insert was not committed.
            withContext(Dispatchers.IO) { files.delete(id) }
            throw failure
        }
        return when (enqueued) {
            is Enqueued.Queued, is Enqueued.AlreadyQueued -> ReportRequest.Queued
            // Removed in Settings between the tap and queuing.
            Enqueued.DestinationChanged -> {
                withContext(Dispatchers.IO) { files.delete(id) }
                ReportRequest.NotConfigured
            }
        }
    }

    /** Sends one queued report: its own payload, its own file, to the destination it was made for. */
    internal suspend fun dispatch(job: OutboxJobEntity): DispatchResult {
        val (service, token) = snapshot()
        // Settings may have changed after the claim: never send to another destination.
        if (service == null || service.fingerprint != job.fingerprint) return DispatchResult.Cancelled("destination_changed")
        if (service.generation != job.generation) return DispatchResult.AuthRequired("credential_changed")
        token ?: return DispatchResult.AuthRequired("credential_missing")
        val payload = try {
            json.decodeFromString(ReportPayload.serializer(), job.payload)
        } catch (malformed: IllegalArgumentException) {
            // SerializationException included.
            return DispatchResult.Rejected("invalid_payload")
        }
        if (payload.reportId != job.dedupeKey || !ReportFiles.isReportId(payload.reportId)) return DispatchResult.Rejected("invalid_payload")

        val png = payload.screenshot?.let { screenshot ->
            val bytes = files.read(payload.reportId) ?: return DispatchResult.Rejected("screenshot_missing")
            // Only the bytes that were captured and previewed may leave the device.
            if (bytes.size != screenshot.bytes || sha256Hex(bytes) != screenshot.sha256) return DispatchResult.Rejected("screenshot_corrupt")
            bytes
        }
        val body = SiteReportsClient.body(payload, png)
        return routes.call(service) { transport, root -> SiteReportsClient(transport).submit(root, token, body) }
    }

    /** Deletes every screenshot file no unfinished report needs, and anything else in the directory. */
    internal suspend fun sweep() {
        filesLock.withLock {
            val keep = dao.reportIdsHoldingFiles()
            withContext(Dispatchers.IO) { files.retainOnly(keep) }
        }
    }

    /** The service row and its token, read together. */
    private suspend fun snapshot(): Pair<ServiceEntity?, String?> = config.withLock {
        val service = dao.service(ServiceKind.SITE_REPORTS)
        service to service?.let { credentials.read(SecretName.SITE_REPORTS_TOKEN, it.fingerprint) }
    }

    private sealed interface Checked {
        data class Valid(val payload: ReportPayload) : Checked

        data class Invalid(val field: ReportField) : Checked
    }

    companion object {
        private const val LOG_TAG = "CombrayReports"

        /** API suffixes users paste with the address; removed so endpoints are not doubled. */
        private val REPORTS_SUFFIXES = listOf("/v1")

        /** Proposed: at most this many reports wait on the device, about 80 MiB of screenshots. */
        const val MAX_UNSENT_REPORTS = 20

        /** The phone's limit; the app scales a larger screenshot down (the service allows 8 MiB). */
        const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024

        /** The service's limits (services/site-reports/README.md). */
        const val MAX_URL_BYTES = 8 * 1024
        const val MAX_TITLE_CHARS = 4 * 1024
        const val MAX_COMMENT_CHARS = 8000
        private const val MAX_CLIENT_TEXT_CHARS = 256
        private const val MAX_SCREENSHOT_SIDE = 8192

        // Signature (8), IHDR length (4) and type (4), then width and height (4 each, big-endian).
        private const val IHDR_TYPE_OFFSET = 12
        private const val IHDR_WIDTH_OFFSET = 16
        private const val IHDR_HEIGHT_OFFSET = 20
        private const val IHDR_END = 24

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)

        /** What the service refuses in text: C0, DEL and C1 controls. */
        private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

        /** In a comment, as above but for tab and newline. */
        private val COMMENT_CONTROL = Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f-\\u009f]")
        private val LINE_BREAK = Regex("\\r\\n?")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Checks [draft] at the entry point and makes the payload that will be queued. The
         * address is sent as the user left it; a title is cleaned and shortened (it came from
         * the page); a comment keeps its line breaks and tabs, other control characters become
         * spaces, and one over [MAX_COMMENT_CHARS] is refused rather than cut.
         */
        private fun payloadOf(draft: ReportDraft, environment: ReportEnvironment): Checked {
            if (!ReportFiles.isReportId(draft.id)) return Checked.Invalid(ReportField.ID)
            val url = draft.url
            if (url.isBlank() || url.toByteArray(Charsets.UTF_8).size > MAX_URL_BYTES || CONTROL.containsMatchIn(url)) {
                return Checked.Invalid(ReportField.URL)
            }
            val title = draft.title?.replace(CONTROL, " ")?.trim()?.takeCodePoints(MAX_TITLE_CHARS)?.trim()?.ifEmpty { null }
            val comment = draft.comment?.replace(LINE_BREAK, "\n")?.replace(COMMENT_CONTROL, " ")?.trim()?.ifEmpty { null }
            if (comment != null && comment.codePointCount(0, comment.length) > MAX_COMMENT_CHARS) return Checked.Invalid(ReportField.COMMENT)
            val screenshot = draft.screenshot
            if ((screenshot == null) == (draft.omitted == null)) return Checked.Invalid(ReportField.SCREENSHOT)
            if (screenshot != null && !isPng(screenshot)) return Checked.Invalid(ReportField.SCREENSHOT)
            val zoomValid = draft.zoomPercent == null || draft.zoomPercent > 0
            val densityValid = draft.density == null || (draft.density.isFinite() && draft.density > 0f)
            if (!zoomValid || !densityValid || !environment.isSendable()) return Checked.Invalid(ReportField.DETAILS)

            return Checked.Valid(
                ReportPayload(
                    reportId = draft.id,
                    createdAt = draft.createdAt,
                    url = url,
                    urlEdited = draft.urlEdited,
                    title = title,
                    comment = comment,
                    display = draft.display,
                    loadFailed = draft.loadFailed,
                    crashed = draft.crashed,
                    environment = environment,
                    zoomPercent = draft.zoomPercent,
                    density = draft.density,
                    screenshot = screenshot?.let {
                        ScreenshotRef(bytes = it.png.size, sha256 = sha256Hex(it.png), width = it.width, height = it.height, scaled = it.scaled)
                    },
                    omitted = draft.omitted,
                ),
            )
        }

        /** A PNG within the phone's limit whose header matches its declared size (the service checks the rest). */
        private fun isPng(screenshot: EncodedScreenshot): Boolean {
            val png = screenshot.png
            if (png.size !in 1..MAX_SCREENSHOT_BYTES || png.size < IHDR_END) return false
            if (screenshot.width !in 1..MAX_SCREENSHOT_SIDE || screenshot.height !in 1..MAX_SCREENSHOT_SIDE) return false
            if (!png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return false
            if (!png.copyOfRange(IHDR_TYPE_OFFSET, IHDR_TYPE_OFFSET + IHDR.size).contentEquals(IHDR)) return false
            return int32(png, IHDR_WIDTH_OFFSET) == screenshot.width && int32(png, IHDR_HEIGHT_OFFSET) == screenshot.height
        }

        private fun int32(bytes: ByteArray, offset: Int): Int =
            (0 until Int.SIZE_BYTES).fold(0) { value, index -> (value shl Byte.SIZE_BITS) or (bytes[offset + index].toInt() and 0xff) }

        private fun ReportEnvironment.isSendable(): Boolean =
            listOf(appVersion, buildType, engineVersion, engineRevision, androidRelease).all {
                it.isNotBlank() && it.length <= MAX_CLIENT_TEXT_CHARS && !CONTROL.containsMatchIn(it)
            }

        /** At most [count] code points: a cut never splits a surrogate pair. */
        private fun String.takeCodePoints(count: Int): String =
            if (codePointCount(0, length) <= count) this else substring(0, offsetByCodePoints(0, count))

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
