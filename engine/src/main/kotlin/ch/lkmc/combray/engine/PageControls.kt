package ch.lkmc.combray.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `<option>`; [id] is Servo's and answers the control. */
data class SelectEntry(val id: Int, val label: String, val disabled: Boolean, val group: String?)

/** What a page asks the user (native/src/controls.rs). Strings are page-chosen and bounded natively. */
sealed interface PageControlContent {
    data class Select(val options: List<SelectEntry>, val selected: List<Int>, val multiple: Boolean) : PageControlContent

    data class Alert(val message: String) : PageControlContent

    data class Confirm(val message: String) : PageControlContent

    data class Prompt(val message: String, val default: String) : PageControlContent

    /** [extensions] come from the `accept` attribute, without the dot. */
    data class Files(val multiple: Boolean, val extensions: List<String>) : PageControlContent

    /** A long-press on a link and/or image; answer with [ControlResponse.Dismiss] after acting. */
    data class LinkMenu(val link: String?, val image: String?) : PageControlContent
}

/** The answer to a page control. */
sealed interface ControlResponse {
    data class Select(val ids: List<Int>) : ControlResponse

    /** OK on `alert` or `confirm`. */
    data object Accept : ControlResponse

    /** OK on `prompt`. */
    data class Text(val value: String) : ControlResponse

    /** Absolute paths of files the app copied for upload. */
    data class Files(val paths: List<String>) : ControlResponse

    /** Cancel or Back: Servo's default response. */
    data object Dismiss : ControlResponse

    fun toJson(): String = buildJsonObject {
        when (this@ControlResponse) {
            is Select -> {
                put("type", "select")
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            }
            Accept -> put("type", "accept")
            is Text -> {
                put("type", "text")
                put("value", value)
            }
            is Files -> {
                put("type", "files")
                put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
            }
            Dismiss -> put("type", "dismiss")
        }
    }.toString()
}
