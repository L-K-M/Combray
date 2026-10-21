package ch.lkmc.combray.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One native view incarnation: the caller's tab ID plus the engine-wide generation. */
data class ViewRef(val tab: Long, val generation: Long)

enum class HistoryKind {
    /** A new top-level document (new load, redirect target, reload, non-cached traversal). */
    DOCUMENT_COMMITTED,

    /** Same document, new URL: fragment change or `pushState`. */
    SAME_DOCUMENT,

    /** Same document and URL: `replaceState` or a list-only change. */
    LIST_UPDATED,
}

enum class LoadPhase { STARTED, HEAD_PARSED, COMPLETE }

/**
 * How a report relates to its tab's current document. Reports from a replaced document are
 * [STALE]; reports that overtook their document's commit were [HELD_UNTIL_COMMIT].
 */
enum class Attribution { CURRENT, HELD_UNTIL_COMMIT, STALE }

enum class CaptureStatus {
    CAPTURED,
    EMPTY,
    STALE_DOCUMENT,
    DOCUMENT_NOT_FOUND,
    UNKNOWN_DOCUMENT,
    TAB_CLOSED,
    TIMED_OUT,
    NO_SUCH_TAB,
    MALFORMED,
    SCRIPT_ERROR,
    UNRECOGNIZED,
}

data class CapturedContent(
    val url: String,
    val title: String,
    val text: String,
    val textBytes: Int,
    val sha256: String,
    val truncated: Boolean,
)

/** Typed form of the JSON events `libcombray_engine.so` emits (native/src/events.rs). */
sealed interface EngineEvent {
    data class EngineReady(val width: Int, val height: Int) : EngineEvent

    data class SurfaceBound(val width: Int, val height: Int) : EngineEvent

    data object SurfaceUnbound : EngineEvent

    data class TabCreated(val view: ViewRef) : EngineEvent

    data class TabSelected(val view: ViewRef) : EngineEvent

    data class TabClosed(val view: ViewRef) : EngineEvent

    /** A page opened a window: a new hidden view with an engine-assigned tab ID. */
    data class TabOpened(val view: ViewRef, val opener: ViewRef) : EngineEvent

    data class NavigationRequested(val view: ViewRef, val request: Long, val url: String) : EngineEvent

    data class PageNavigationRequested(val view: ViewRef, val url: String) : EngineEvent

    /** The user tapped a link for another app (`mailto:`, `tel:`, `intent:` ...); the page stayed. */
    data class ExternalLink(val view: ViewRef, val url: String) : EngineEvent

    /** The page asked for a window without the user's tap; the engine did not open it. */
    data class PopupBlocked(val view: ViewRef) : EngineEvent

    /** [index] and [length] describe the view's session history after the change. */
    data class HistoryChanged(
        val view: ViewRef,
        val kind: HistoryKind,
        val document: Long,
        val url: String,
        val index: Int,
        val length: Int,
    ) : EngineEvent {
        val canGoBack get() = index > 0
        val canGoForward get() = index < length - 1
    }

    data class LoadStatus(
        val view: ViewRef,
        val document: Long,
        val phase: LoadPhase,
        val attribution: Attribution,
    ) : EngineEvent

    data class TitleChanged(
        val view: ViewRef,
        val document: Long,
        val title: String?,
        val attribution: Attribution,
    ) : EngineEvent

    data class CaptureResult(
        val capture: Long,
        val view: ViewRef,
        val document: Long,
        val status: CaptureStatus,
        val content: CapturedContent?,
        val detail: String?,
    ) : EngineEvent

    data class LateCaptureResult(val capture: Long) : EngineEvent

    data class EvaluationResult(
        val view: ViewRef,
        val request: Long,
        val ok: Boolean,
        val value: JsonElement?,
        val error: String?,
    ) : EngineEvent

    data class Painted(val view: ViewRef, val frames: Long) : EngineEvent

    /** A page field wants the keyboard; [kind] is "text", "password", "email", "url", "number", "tel" or "search". */
    data class ImeShown(val view: ViewRef, val multiline: Boolean, val kind: String) : EngineEvent

    data class ImeHidden(val view: ViewRef) : EngineEvent

    data class Crashed(val view: ViewRef, val reason: String) : EngineEvent

    /** A page control waits for [EnginePort.respondToControl] with [control]. */
    data class ControlShown(val view: ViewRef, val control: Long, val content: PageControlContent) : EngineEvent

    /** The page withdrew [control] or its view closed; an answer would be ignored. */
    data class ControlHidden(val view: ViewRef, val control: Long) : EngineEvent

    data class EngineError(val message: String, val tab: Long?, val request: Long?) : EngineEvent

    data object ShutdownComplete : EngineEvent

    data class ProfilePersisted(val ok: Boolean) : EngineEvent

    /** Diagnostics the product does not act on (`default_handled`, `unattributed_title`, ...). */
    data class Other(val type: String) : EngineEvent

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses one event. Throws [IllegalArgumentException] for malformed JSON or a known
         * event type with missing fields: the native side and this parser must agree.
         */
        fun parse(text: String): EngineEvent {
            val event = json.parseToJsonElement(text).jsonObject
            return when (val type = event.string("type")) {
                "engine_ready" -> EngineReady(event.int("width"), event.int("height"))
                "surface_bound" -> SurfaceBound(event.int("width"), event.int("height"))
                "surface_unbound" -> SurfaceUnbound
                "tab_created" -> TabCreated(event.view())
                "tab_selected" -> TabSelected(event.view())
                "tab_closed" -> TabClosed(event.view())
                "tab_opened" -> TabOpened(event.view(), ViewRef(event.long("opener_tab"), event.long("opener_generation")))
                "navigation_requested" -> NavigationRequested(event.view(), event.long("request"), event.string("url"))
                "page_navigation_requested" -> PageNavigationRequested(event.view(), event.string("url"))
                "external_link" -> ExternalLink(event.view(), event.string("url"))
                "popup_blocked" -> PopupBlocked(event.view())
                "document_committed" -> event.history(HistoryKind.DOCUMENT_COMMITTED)
                "same_document_navigation" -> event.history(HistoryKind.SAME_DOCUMENT)
                "history_updated" -> event.history(HistoryKind.LIST_UPDATED)
                "load_status" -> LoadStatus(
                    event.view(),
                    event.long("document"),
                    loadPhase(event.string("phase")),
                    attribution(event.string("attribution")),
                )
                "title" -> TitleChanged(
                    event.view(),
                    event.long("document"),
                    event.optionalString("title"),
                    attribution(event.string("attribution")),
                )
                "capture" -> event.capture()
                "late_capture_result" -> LateCaptureResult(event.long("capture"))
                "evaluation" -> EvaluationResult(
                    event.view(),
                    event.long("request"),
                    event.boolean("ok"),
                    event["value"]?.takeUnless { it is JsonNull },
                    event.optionalString("error"),
                )
                "painted" -> Painted(event.view(), event.long("frames"))
                "ime_shown" -> ImeShown(event.view(), event.boolean("multiline"), event.optionalString("kind") ?: "text")
                "ime_hidden" -> ImeHidden(event.view())
                "crashed" -> Crashed(event.view(), event.string("reason"))
                "control_shown" -> ControlShown(event.view(), event.long("control"), event.pageControl())
                "control_hidden" -> ControlHidden(event.view(), event.long("control"))
                "error" -> EngineError(event.string("message"), event.optionalLong("tab"), event.optionalLong("request"))
                "shutdown_complete" -> ShutdownComplete
                "profile_persisted" -> ProfilePersisted(event.boolean("ok"))
                else -> Other(type)
            }
        }

        private fun loadPhase(name: String) = when (name) {
            "started" -> LoadPhase.STARTED
            "head_parsed" -> LoadPhase.HEAD_PARSED
            "complete" -> LoadPhase.COMPLETE
            else -> throw IllegalArgumentException("unknown load phase $name")
        }

        private fun attribution(name: String) = when (name) {
            "current" -> Attribution.CURRENT
            "held_until_commit" -> Attribution.HELD_UNTIL_COMMIT
            "stale" -> Attribution.STALE
            else -> throw IllegalArgumentException("unknown attribution $name")
        }

        private fun captureStatus(name: String) =
            CaptureStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CaptureStatus.UNRECOGNIZED

        private fun JsonObject.history(kind: HistoryKind) = HistoryChanged(
            view(),
            kind,
            long("document"),
            string("url"),
            int("index"),
            int("length"),
        )

        private fun JsonObject.capture(): CaptureResult {
            val content = (get("content") as? JsonObject)?.let {
                CapturedContent(
                    url = it.string("url"),
                    title = it.string("title"),
                    text = it.string("text"),
                    textBytes = it.int("text_bytes"),
                    sha256 = it.string("sha256"),
                    truncated = it.boolean("truncated_in_page") || it.boolean("truncated_natively"),
                )
            }
            return CaptureResult(
                long("capture"),
                view(),
                long("document"),
                captureStatus(string("status")),
                content,
                optionalString("detail"),
            )
        }

        private fun JsonObject.pageControl(): PageControlContent = when (val kind = string("kind")) {
            "select" -> PageControlContent.Select(
                array("options").map {
                    val option = it as? JsonObject ?: throw IllegalArgumentException("option is not an object")
                    SelectEntry(option.int("id"), option.string("label"), option.boolean("disabled"), option.optionalString("group"))
                },
                array("selected").map { (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("selected is not an integer") },
                boolean("multiple"),
            )
            "alert" -> PageControlContent.Alert(string("message"))
            "confirm" -> PageControlContent.Confirm(string("message"))
            "prompt" -> PageControlContent.Prompt(string("message"), string("default"))
            "files" -> PageControlContent.Files(
                boolean("multiple"),
                array("extensions").map { (it as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("extension is not a string") },
            )
            "link_menu" -> PageControlContent.LinkMenu(optionalString("link"), optionalString("image"))
            "sign_in" -> PageControlContent.SignIn(string("origin"), boolean("proxy"))
            else -> throw IllegalArgumentException("unknown control kind $kind")
        }

        private fun JsonObject.array(key: String): JsonArray =
            get(key) as? JsonArray ?: throw IllegalArgumentException("$key is not an array")

        private fun JsonObject.view() = ViewRef(long("tab"), long("generation"))

        private fun JsonObject.primitive(key: String): JsonPrimitive =
            (get(key) as? JsonPrimitive) ?: throw IllegalArgumentException("missing $key")

        private fun JsonObject.string(key: String): String =
            primitive(key).takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key is not a string")

        private fun JsonObject.optionalString(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

        private fun JsonObject.long(key: String): Long =
            primitive(key).longOrNull ?: throw IllegalArgumentException("$key is not an integer")

        private fun JsonObject.optionalLong(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

        private fun JsonObject.int(key: String): Int =
            primitive(key).intOrNull ?: throw IllegalArgumentException("$key is not an integer")

        private fun JsonObject.boolean(key: String): Boolean =
            primitive(key).booleanOrNull ?: throw IllegalArgumentException("$key is not a boolean")
    }
}
