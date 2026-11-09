package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** One Hister search hit. [excerpt] is plain text: never rendered as HTML. */
data class HisterHit(val url: String, val title: String?, val excerpt: String?, val fromHistory: Boolean)

sealed interface HisterSearch {
    data class Results(val hits: List<HisterHit>, val total: Int?, val nextPage: String?) : HisterSearch

    data object Unauthorized : HisterSearch

    data class Unreachable(val category: String) : HisterSearch

    data class ServerError(val status: Int) : HisterSearch

    /** The server answered, but not with Hister's search JSON. */
    data object Malformed : HisterSearch
}

/**
 * Hister's HTTP API at the inspected revision (docs/INTEGRATIONS.md): `POST {root}/api/add` takes
 * JSON `url`, `title`, `text` and answers 201; `GET {root}/search?q=&format=json` returns
 * `documents` and promoted `history` (different shapes) plus an opaque `page_key`.
 */
class HisterClient(private val transport: HttpTransport) {
    fun add(root: String, token: String, url: String, title: String?, text: String): DispatchResult {
        val body = boundedBody(url, title, text)
        if (body.size > MAX_REQUEST_BYTES) return DispatchResult.Rejected("payload_too_large")
        val outcome = transport.execute(
            HttpRequest(
                method = "POST",
                url = ServiceUrls.endpoint(root, "api/add"),
                headers = headers(token) + ("Content-Type" to "application/json"),
                body = body,
            ),
        )
        return when (outcome) {
            // Hister updates a document key in place, so resending the same content is harmless.
            HttpOutcome.Timeout -> DispatchResult.Retry("timeout")
            is HttpOutcome.Failure -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.NotConnected -> DispatchResult.Retry(outcome.category, delivery = Delivery.NOT_SENT)
            is HttpOutcome.Response -> when (outcome.status) {
                in 200..299 -> DispatchResult.Done(null)
                // Hister's rules skip the page: skip rules, an allow list it misses, Hister's own host.
                406 -> DispatchResult.Declined(SKIPPED_BY_SERVER)
                // Sensitive content. The pinned revision checks only submitted HTML and Combray sends
                // none, so this is defensive: such a refusal must not fail the capture.
                422 -> DispatchResult.Declined(SENSITIVE_CONTENT)
                else -> HttpStatus.classifyFailure(outcome)
            }
        }
    }

    fun search(root: String, token: String, query: String, pageKey: String? = null): HisterSearch {
        val parameters = buildString {
            append("q=").append(encode(query)).append("&format=json")
            pageKey?.let { append("&page_key=").append(encode(it)) }
        }
        val outcome = transport.execute(HttpRequest("GET", ServiceUrls.endpoint(root, "search?$parameters"), headers(token)))
        return when (outcome) {
            HttpOutcome.Timeout -> HisterSearch.Unreachable("timeout")
            is HttpOutcome.Failure -> HisterSearch.Unreachable(outcome.category)
            is HttpOutcome.NotConnected -> HisterSearch.Unreachable(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                200 -> parseResults(outcome.body)
                401, 403 -> HisterSearch.Unauthorized
                else -> HisterSearch.ServerError(outcome.status)
            }
        }
    }

    /** Read-only: an authorized search proves the root, the token and the namespace answer. */
    fun checkConnection(root: String, token: String): ConnectionCheck = when (val result = search(root, token, CHECK_QUERY)) {
        is HisterSearch.Results -> ConnectionCheck.Connected(result.total?.toString())
        HisterSearch.Unauthorized -> ConnectionCheck.Unauthorized
        is HisterSearch.Unreachable -> ConnectionCheck.Unreachable(result.category)
        is HisterSearch.ServerError -> ConnectionCheck.WrongAddress(result.status)
        HisterSearch.Malformed -> ConnectionCheck.WrongAddress(200)
    }

    /**
     * The request stays under [MAX_REQUEST_BYTES] after JSON escaping: when it would not, the text
     * is cut on a character boundary.
     *
     * Count the escaped UTF-8 bytes removed from the tail, not a byte excess as code points.
     * Only the final prefix is rebuilt, preserving all text that fits, including supplementary
     * characters and control characters that expand to six-byte JSON escapes.
     */
    private fun boundedBody(url: String, title: String?, text: String): ByteArray {
        val full = body(url, title, text)
        if (full.size <= MAX_REQUEST_BYTES) return full
        var excess = full.size - MAX_REQUEST_BYTES
        var end = text.length
        while (excess > 0 && end > 0) {
            val point = text.codePointBefore(end)
            excess -= escapedUtf8Width(point)
            end -= Character.charCount(point)
        }
        return body(url, title, text.substring(0, end))
    }

    private fun body(url: String, title: String?, text: String): ByteArray =
        buildJsonObject {
            put("url", url)
            title?.let { put("title", it.takeUtf8Bytes(MAX_TITLE_CHARS)) }
            put("text", text)
        }.toString().toByteArray(Charsets.UTF_8)

    /** At most [maxBytes] of UTF-8, without splitting a character. */
    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        val bytes = toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return this
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun parseResults(body: ByteArray): HisterSearch {
        val root = try {
            json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return HisterSearch.Malformed
        // A null list is an empty one: the pinned server is written in Go, which sends an empty
        // list as null ("history": null when nothing is promoted). A response with neither key,
        // or a list of the wrong type, is not an empty search: that would hide a server that is
        // not speaking this API.
        if ("documents" !in root && "history" !in root) return HisterSearch.Malformed
        val documents = root["documents"]?.takeUnless { it is JsonNull }
        val history = root["history"]?.takeUnless { it is JsonNull }
        if ((documents != null && documents !is JsonArray) || (history != null && history !is JsonArray)) {
            return HisterSearch.Malformed
        }
        val documentHits = (documents as? JsonArray).orEmpty().mapNotNull { hit(it, fromHistory = false) }
        val historyHits = (history as? JsonArray).orEmpty().mapNotNull { hit(it, fromHistory = true) }
        // Promoted history first, then documents not already listed: neither array is dropped.
        // Each address appears once, even when the server repeats it within one array.
        val hits = (historyHits + documentHits).distinctBy { it.url }
        // Hister always sends page_key and leaves it empty on the last page; the server ignores
        // an empty one, so asking with it would return the first page again.
        return HisterSearch.Results(hits, (root["total"] as? JsonPrimitive)?.content?.toIntOrNull(), (root["page_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() })
    }

    private fun hit(element: JsonElement, fromHistory: Boolean): HisterHit? {
        val item = element as? JsonObject ?: return null
        val url = item.string("url") ?: return null
        val excerpt = listOf("text", "snippet", "description").firstNotNullOfOrNull { item.string(it) }
        return HisterHit(url, item.string("title"), excerpt?.take(MAX_EXCERPT_CHARS), fromHistory)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun headers(token: String) = mapOf(
        "X-Access-Token" to token,
        "Accept" to "application/json",
        // Hister's documented native-client convention for its CSRF guard; auth still applies.
        // Android's HttpURLConnection sends it (the desktop JDK drops it by default).
        "Origin" to "hister://",
    )

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Matches JSON string escaping followed by the JVM's UTF-8 encoding. */
    private fun escapedUtf8Width(point: Int): Int = when (point) {
        '"'.code, '\\'.code, '\b'.code, '\u000C'.code, '\n'.code, '\r'.code, '\t'.code -> 2
        in 0..0x1F -> 6
        in 0..0x7F -> 1
        in 0..0x7FF -> 2
        // A lone UTF-16 surrogate is encoded as the single-byte replacement '?'.
        in 0xD800..0xDFFF -> 1
        in 0..0xFFFF -> 3
        else -> 4
    }

    companion object {
        /** Stays below Hister's general 1 MiB request limit even with JSON escaping. */
        const val MAX_REQUEST_BYTES = 900 * 1024
        const val MAX_TITLE_CHARS = 4 * 1024
        const val MAX_EXCERPT_CHARS = 300
        const val CHECK_QUERY = "combray connection check"

        private const val SKIPPED_BY_SERVER = "skipped_by_server"
        private const val SENSITIVE_CONTENT = "sensitive_content"

        /** Error categories for a deliberate refusal: the server saw the content and said no. */
        internal val DECLINE_CATEGORIES = setOf(SKIPPED_BY_SERVER, SENSITIVE_CONTENT)

        private val json = Json { ignoreUnknownKeys = true }
    }
}
