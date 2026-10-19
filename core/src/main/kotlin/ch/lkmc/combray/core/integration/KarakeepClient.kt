package ch.lkmc.combray.core.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Karakeep's REST API (docs/INTEGRATIONS.md): `POST {root}/api/v1/bookmarks` creates a link;
 * 201 is new, 200 is the existing bookmark for that URL. Both are successful saves, which also
 * makes a retry after a lost answer safe.
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

    /** Read-only: lists at most one bookmark to prove the root and key work. */
    fun checkConnection(root: String, apiKey: String): ConnectionCheck {
        val outcome = transport.execute(
            HttpRequest("GET", ServiceUrls.endpoint(root, "api/v1/bookmarks?limit=1"), headers(apiKey)),
        )
        return when (outcome) {
            HttpOutcome.Timeout -> ConnectionCheck.Unreachable("timeout")
            is HttpOutcome.Failure -> ConnectionCheck.Unreachable(outcome.category)
            is HttpOutcome.Response -> when (outcome.status) {
                200 -> ConnectionCheck.Connected(null)
                401, 403 -> ConnectionCheck.Unauthorized
                else -> ConnectionCheck.WrongAddress(outcome.status)
            }
        }
    }

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
    }
}
