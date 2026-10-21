package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        val outcome = transport.execute(
            HttpRequest(
                method = "POST",
                url = ServiceUrls.endpoint(root, "api/add"),
                headers = headers(token) + ("Content-Type" to "application/json"),
                body = boundedBody(url, title, text),
            ),
        )
        return when (outcome) {
            // Hister updates a document key in place, so resending the same content is harmless.
            HttpOutcome.Timeout -> DispatchResult.Retry("timeout")
            is HttpOutcome.Failure -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.NotConnected -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.Response -> if (outcome.status in 200..299) {
                DispatchResult.Done(null)
            } else {
                HttpStatus.classifyFailure(outcome)
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
     * is cut further, on a character boundary.
     */
    private fun boundedBody(url: String, title: String?, text: String): ByteArray {
        var kept = text
        while (true) {
            val body = buildJsonObject {
                put("url", url)
                title?.let { put("title", it.take(MAX_TITLE_CHARS)) }
                put("text", kept)
            }.toString().toByteArray(Charsets.UTF_8)
            if (body.size <= MAX_REQUEST_BYTES || kept.isEmpty()) return body
            val excess = body.size - MAX_REQUEST_BYTES
            kept = kept.dropLastCodePoints(maxOf(excess / 2, 1))
        }
    }

    private fun parseResults(body: ByteArray): HisterSearch {
        val root = try {
            json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return HisterSearch.Malformed
        val documents = root["documents"]
        val history = root["history"]
        // A missing field is an empty list. A missing object, or a field of the wrong type, is not
        // an empty search: that would hide a server that is not speaking this API.
        if ((documents == null && history == null) || (documents != null && documents !is JsonArray) || (history != null && history !is JsonArray)) {
            return HisterSearch.Malformed
        }
        val documentHits = documents.orEmpty().mapNotNull { hit(it, fromHistory = false) }
        val historyHits = history.orEmpty().mapNotNull { hit(it, fromHistory = true) }
        // Promoted history first, then documents not already listed: neither array is dropped.
        // Each address appears once, even when the server repeats it within one array.
        val hits = (historyHits + documentHits).distinctBy { it.url }
        return HisterSearch.Results(hits, (root["total"] as? JsonPrimitive)?.content?.toIntOrNull(), (root["page_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
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

    private fun String.dropLastCodePoints(count: Int): String {
        val keep = codePointCount(0, length) - count
        return if (keep <= 0) "" else substring(0, offsetByCodePoints(0, keep))
    }

    companion object {
        /** Stays below Hister's general 1 MiB request limit even with JSON escaping. */
        const val MAX_REQUEST_BYTES = 900 * 1024
        const val MAX_TITLE_CHARS = 4 * 1024
        const val MAX_EXCERPT_CHARS = 300
        const val CHECK_QUERY = "combray connection check"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
