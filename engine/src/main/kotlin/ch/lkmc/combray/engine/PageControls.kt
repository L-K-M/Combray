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

    /**
     * HTTP authentication for a navigation. [origin] is the server asking (scheme, host, port),
     * which may not be the page that linked there. The load waits for the answer.
     */
    data class SignIn(val origin: String, val proxy: Boolean) : PageControlContent

    /**
     * The page asks to confirm leaving it (`beforeunload`), usually over unsaved form text; its
     * scripts wait for the answer. Answer with a [LeavePageChoice]'s response.
     */
    data object LeavePage : PageControlContent
}

/** The answers to [PageControlContent.LeavePage]: Leave like OK, Stay like Cancel or Back. */
enum class LeavePageChoice(val response: ControlResponse) {
    LEAVE(ControlResponse.Accept),
    STAY(ControlResponse.Dismiss),
}

/** The answer to a page control. */
sealed interface ControlResponse {
    data class Select(val ids: List<Int>) : ControlResponse

    /** OK on `alert` or `confirm`; Leave on a leave-page question. */
    data object Accept : ControlResponse

    /** OK on `prompt`. */
    data class Text(val value: String) : ControlResponse

    /** Absolute paths of files the app copied for upload. */
    data class Files(val paths: List<String>) : ControlResponse

    /** HTTP sign-in. Printing it leaves out the password. */
    data class Credentials(val username: String, val password: String) : ControlResponse {
        override fun toString() = "Credentials(username=$username, password=<redacted>)"
    }

    /** Cancel or Back: Servo's default response. Stay on a leave-page question, where Servo's default would leave. */
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
            is Credentials -> {
                put("type", "credentials")
                put("username", username)
                put("password", password)
            }
            Dismiss -> put("type", "dismiss")
        }
    }.toString()
}
