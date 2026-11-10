package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Combray's own report service, version 1 (services/site-reports/README.md). The phone uses two
 * routes: `POST {root}/v1/reports` creates a report, idempotently on its client-made id, and
 * `GET {root}/v1/whoami` checks the address and that the token is a reporter token. The token
 * travels only in `Authorization`.
 */
internal class SiteReportsClient(private val transport: HttpTransport) {
    /** Sends one report request built by [body]. Sending it again is safe: a repeat answers 200. */
    fun submit(root: String, token: String, body: ByteArray): DispatchResult {
        val outcome = transport.execute(
            HttpRequest(
                method = "POST",
                url = ServiceUrls.endpoint(root, REPORTS_PATH),
                headers = headers(token) + ("Content-Type" to "application/json"),
                body = body,
            ),
        )
        return when (outcome) {
            // Sent without an answer: the report may be stored. A retry with the same id is a 200.
            HttpOutcome.Timeout -> DispatchResult.Retry("timeout")
            is HttpOutcome.Failure -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.NotConnected -> DispatchResult.Retry(outcome.category, delivery = Delivery.NOT_SENT)
            is HttpOutcome.Response -> when (outcome.status) {
                201, 200 -> {
                    val id = parseObject(outcome.body)?.string("id")
                    val note = when {
                        id == null -> "malformed_response"
                        outcome.status == 200 -> "existing"
                        else -> null
                    }
                    DispatchResult.Done(id, note)
                }
                // The id already names another report: a client bug, never fixed by resending.
                409 -> DispatchResult.Rejected("id_conflict")
                413 -> DispatchResult.Rejected("too_large")
                // 401 and 403 pause, 408 and 429 wait (Retry-After), every 5xx waits too: a full
                // store (507) or busy server (503) frees up as agents resolve reports.
                else -> HttpStatus.classifyFailure(outcome)
            }
        }
    }

    /** Read-only: the address answers as this service and the token may send reports. */
    fun checkConnection(root: String, token: String): ConnectionCheck {
        val outcome = transport.execute(HttpRequest("GET", ServiceUrls.endpoint(root, WHOAMI_PATH), headers(token)))
        return when (outcome) {
            HttpOutcome.Timeout -> ConnectionCheck.Unreachable("timeout")
            is HttpOutcome.Failure -> ConnectionCheck.Unreachable(outcome.category)
            is HttpOutcome.NotConnected -> ConnectionCheck.Unreachable(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                200 -> {
                    val identity = parseObject(outcome.body)
                    when {
                        identity?.string("service") != SERVICE_NAME -> ConnectionCheck.WrongAddress(outcome.status)
                        identity.string("role") == REPORTER_ROLE -> ConnectionCheck.Connected(null)
                        // An agent token reads reports and cannot send them.
                        else -> ConnectionCheck.Unauthorized
                    }
                }
                else -> HttpStatus.classifyCheckFailure(outcome.status)
            }
        }
    }

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
    )

    private fun parseObject(body: ByteArray): JsonObject? = try {
        json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
    } catch (malformed: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val SERVICE_NAME = "combray-site-reports"
        private const val REPORTER_ROLE = "reporter"
        private const val REPORTS_PATH = "v1/reports"
        private const val WHOAMI_PATH = "v1/whoami"
        private const val APP = "combray"
        private const val ENGINE = "servo"
        private const val PNG_MEDIA_TYPE = "image/png"

        private val json = Json { ignoreUnknownKeys = true }

        /** RFC 3339 UTC with milliseconds, the form the service documents. */
        private val CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * The request body for [payload]: exactly the members the service validates
         * (validation.py), `screenshot.data` as standard padded base64 of [png]. [png] is the
         * file the payload references, already checked against its size and digest.
         */
        fun body(payload: ReportPayload, png: ByteArray?): ByteArray {
            val screenshot = payload.screenshot
            require((screenshot == null) == (png == null)) { "screenshot reference and bytes disagree" }
            val environment = payload.environment
            return buildJsonObject {
                put("id", payload.reportId)
                put("created_at", CREATED_AT.format(Instant.ofEpochMilli(payload.createdAt)))
                putJsonObject("page") {
                    put("url", payload.url)
                    put("url_edited", payload.urlEdited)
                    put("title", payload.title)
                    put("display", payload.display.wire)
                    put("load_failed", payload.loadFailed)
                    put("crashed", payload.crashed)
                }
                put("comment", payload.comment)
                // Client values are never null: an unknown value is left out.
                putJsonObject("client") {
                    put("app", APP)
                    put("app_version", environment.appVersion)
                    put("app_build", environment.appBuild)
                    put("build_type", environment.buildType)
                    put("engine", ENGINE)
                    put("engine_version", environment.engineVersion)
                    put("engine_revision", environment.engineRevision)
                    put("android_release", environment.androidRelease)
                    put("android_sdk", environment.androidSdk)
                    payload.zoomPercent?.let { put("page_zoom_percent", it) }
                    payload.density?.let { put("density", it) }
                }
                if (screenshot == null || png == null) {
                    put("screenshot", JsonNull)
                } else {
                    putJsonObject("screenshot") {
                        put("media_type", PNG_MEDIA_TYPE)
                        put("width", screenshot.width)
                        put("height", screenshot.height)
                        put("bytes", screenshot.bytes)
                        put("sha256", screenshot.sha256)
                        put("scaled", screenshot.scaled)
                        put("data", Base64.getEncoder().encodeToString(png))
                    }
                }
                put("screenshot_omitted", payload.omitted?.wire)
            }.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
