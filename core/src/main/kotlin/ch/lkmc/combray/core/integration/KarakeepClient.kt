package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** A manual Karakeep list saves can be added to. [name] includes the list's icon. */
data class KarakeepList(val id: String, val name: String)

/** One link bookmark. Notes and uploaded files have no address to open and are left out. */
data class KarakeepBookmark(val id: String, val url: String, val title: String?)

data class KarakeepPage(val bookmarks: List<KarakeepBookmark>, val nextCursor: String?)

/** A read from Karakeep: the [Ok] value, or why there is none. */
sealed interface KarakeepRead<out T> {
    data class Ok<T>(val value: T) : KarakeepRead<T>

    data object Unauthorized : KarakeepRead<Nothing>

    data class Unreachable(val category: String) : KarakeepRead<Nothing>

    data class ServerError(val status: Int) : KarakeepRead<Nothing>

    /** The server answered, but not with Karakeep's JSON. */
    data object Malformed : KarakeepRead<Nothing>
}

/**
 * Karakeep's REST API (docs/INTEGRATIONS.md): `POST {root}/api/v1/bookmarks` creates a link;
 * 201 is new, 200 is the existing bookmark for that URL. Both are successful saves, which also
 * makes a retry after a lost answer safe. `PUT {root}/api/v1/lists/{list}/bookmarks/{id}` adds a
 * bookmark to a manual list (204; repeating it changes nothing). Reads list bookmarks, search
 * results (`GET .../bookmarks/search?q=`) and lists (`GET .../lists`).
 */
class KarakeepClient(private val transport: HttpTransport) {
    fun createLink(root: String, apiKey: String, url: String, title: String?): DispatchResult {
        val body = buildJsonObject {
            put("type", "link")
            put("url", url)
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it.take(MAX_TITLE_CHARS)) }
        }
        val outcome = transport.execute(
            HttpRequest(
                method = "POST",
                url = ServiceUrls.endpoint(root, "api/v1/bookmarks"),
                headers = headers(apiKey) + ("Content-Type" to "application/json"),
                body = body.toString().toByteArray(Charsets.UTF_8),
            ),
        )
        return when (outcome) {
            // Sent without an answer: the bookmark may exist. Retrying is safe (200 existing).
            HttpOutcome.Timeout -> DispatchResult.Retry("timeout")
            is HttpOutcome.Failure -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.NotConnected -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                201, 200 -> {
                    val id = bookmarkId(outcome.body)
                    val note = when {
                        id == null -> "malformed_response"
                        outcome.status == 200 -> "existing"
                        else -> null
                    }
                    DispatchResult.Done(id, note)
                }
                else -> HttpStatus.classifyFailure(outcome)
            }
        }
    }

    /** Adds [bookmarkId] to the manual list [listId]. Idempotent, so a retry is safe. */
    fun addToList(root: String, apiKey: String, listId: String, bookmarkId: String): DispatchResult {
        val outcome = transport.execute(
            HttpRequest(
                method = "PUT",
                url = ServiceUrls.endpoint(root, "api/v1/lists/${encode(listId)}/bookmarks/${encode(bookmarkId)}"),
                headers = headers(apiKey),
                // Empty but present: some HTTP stacks refuse a PUT without a body.
                body = ByteArray(0),
            ),
        )
        return when (outcome) {
            HttpOutcome.Timeout -> DispatchResult.Retry("timeout")
            is HttpOutcome.Failure -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.NotConnected -> DispatchResult.Retry(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                in 200..299 -> DispatchResult.Done(bookmarkId)
                // The list was deleted (or the bookmark): the user has to choose another list.
                404 -> DispatchResult.Rejected("list_not_found")
                else -> HttpStatus.classifyFailure(outcome)
            }
        }
    }

    /** The lists a bookmark can be added to: manual ones the user may edit. */
    fun lists(root: String, apiKey: String): KarakeepRead<List<KarakeepList>> = read(root, apiKey, "api/v1/lists") { body ->
        val lists = body["lists"] as? JsonArray ?: return@read null
        lists.mapNotNull { list(it) }.sortedBy { it.name.lowercase() }
    }

    /** Newest bookmarks first when [query] is null, otherwise Karakeep's search for it. */
    fun bookmarks(root: String, apiKey: String, query: String?, cursor: String? = null): KarakeepRead<KarakeepPage> {
        val parameters = buildString {
            query?.let { append("q=").append(encode(it)).append('&') }
            append("limit=").append(PAGE_SIZE).append("&includeContent=false")
            cursor?.let { append("&cursor=").append(encode(it)) }
        }
        val path = if (query == null) "api/v1/bookmarks?$parameters" else "api/v1/bookmarks/search?$parameters"
        return read(root, apiKey, path) { body ->
            val bookmarks = body["bookmarks"] as? JsonArray ?: return@read null
            KarakeepPage(bookmarks.mapNotNull { bookmark(it) }, body.string("nextCursor"))
        }
    }

    /** Read-only: lists at most one bookmark to prove the root and key work. */
    fun checkConnection(root: String, apiKey: String): ConnectionCheck {
        val outcome = transport.execute(
            HttpRequest("GET", ServiceUrls.endpoint(root, "api/v1/bookmarks?limit=1"), headers(apiKey)),
        )
        return when (outcome) {
            HttpOutcome.Timeout -> ConnectionCheck.Unreachable("timeout")
            is HttpOutcome.Failure -> ConnectionCheck.Unreachable(outcome.category)
            is HttpOutcome.NotConnected -> ConnectionCheck.Unreachable(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                200 -> ConnectionCheck.Connected(null)
                401, 403 -> ConnectionCheck.Unauthorized
                else -> ConnectionCheck.WrongAddress(outcome.status)
            }
        }
    }

    private fun <T> read(root: String, apiKey: String, path: String, parse: (JsonObject) -> T?): KarakeepRead<T> {
        val outcome = transport.execute(HttpRequest("GET", ServiceUrls.endpoint(root, path), headers(apiKey)))
        return when (outcome) {
            HttpOutcome.Timeout -> KarakeepRead.Unreachable("timeout")
            is HttpOutcome.Failure -> KarakeepRead.Unreachable(outcome.category)
            is HttpOutcome.NotConnected -> KarakeepRead.Unreachable(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                200 -> parseObject(outcome.body)?.let(parse)?.let { KarakeepRead.Ok(it) } ?: KarakeepRead.Malformed
                401, 403 -> KarakeepRead.Unauthorized
                else -> KarakeepRead.ServerError(outcome.status)
            }
        }
    }

    /** Smart lists are filled by a query, and a viewer may not add: neither can take a save. */
    private fun list(element: JsonElement): KarakeepList? {
        val list = element as? JsonObject ?: return null
        val id = list.string("id") ?: return null
        val name = list.string("name") ?: return null
        if ((list.string("type") ?: MANUAL_LIST) != MANUAL_LIST) return null
        if (list.string("userRole") == VIEWER_ROLE) return null
        return KarakeepList(id, listOfNotNull(list.string("icon"), name).joinToString(" "))
    }

    private fun bookmark(element: JsonElement): KarakeepBookmark? {
        val bookmark = element as? JsonObject ?: return null
        val content = bookmark["content"] as? JsonObject ?: return null
        if (content.string("type") != LINK) return null
        val id = bookmark.string("id") ?: return null
        val url = content.string("url") ?: return null
        // The user's own title wins over the crawled one, as in Karakeep.
        return KarakeepBookmark(id, url, bookmark.string("title") ?: content.string("title"))
    }

    private fun parseObject(body: ByteArray): JsonObject? = try {
        json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
    } catch (malformed: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun headers(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Accept" to "application/json",
    )

    private fun bookmarkId(body: ByteArray): String? = try {
        val element = json.parseToJsonElement(body.toString(Charsets.UTF_8))
        ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    } catch (malformed: IllegalArgumentException) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Karakeep's schema bounds titles to 1000 characters (docs/INTEGRATIONS.md). */
        const val MAX_TITLE_CHARS = 1000

        /** Bookmarks per page in Recent's Karakeep scope. */
        const val PAGE_SIZE = 20

        const val LINK = "link"
        const val MANUAL_LIST = "manual"
        const val VIEWER_ROLE = "viewer"
    }
}
