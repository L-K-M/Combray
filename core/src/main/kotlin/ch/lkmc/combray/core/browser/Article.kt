package ch.lkmc.combray.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** A page's main article for reader view. All text is page content: shown as text, never markup. */
data class Article(
    val title: String?,
    val byline: String?,
    val site: String?,
    val rightToLeft: Boolean,
    val blocks: List<ArticleBlock>,
    /** The article was longer than reader view keeps; the rest is only on the page. */
    val truncated: Boolean,
)

enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, QUOTE, PREFORMATTED, CAPTION }

/** [level]: 1..6 for headings, nesting depth (from 1) for list items, otherwise 0. */
data class ArticleBlock(val kind: BlockKind, val level: Int, val spans: List<ArticleSpan>)

/** A run of text; [href] is the absolute http(s) address of a link around it. */
data class ArticleSpan(val text: String, val href: String? = null)

/**
 * Reads the JSON the engine's reader script returns (native/src/reader.js). The page can shape
 * that JSON, so anything unexpected is dropped rather than trusted.
 */
object ArticleJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val KINDS = mapOf(
        "h" to BlockKind.HEADING,
        "p" to BlockKind.PARAGRAPH,
        "li" to BlockKind.LIST_ITEM,
        "q" to BlockKind.QUOTE,
        "pre" to BlockKind.PREFORMATTED,
        "cap" to BlockKind.CAPTION,
    )
    private const val MAX_HEADING_LEVEL = 6
    private const val MAX_LIST_DEPTH = 8

    /** Null when [text] is not an article with at least one block. */
    fun parse(text: String, truncated: Boolean): Article? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return null
        val blocks = (root["blocks"] as? JsonArray).orEmpty().mapNotNull(::block)
        if (blocks.isEmpty()) return null
        return Article(
            title = root.string("title"),
            byline = root.string("byline"),
            site = root.string("site"),
            rightToLeft = (root["rtl"] as? JsonPrimitive)?.booleanOrNull == true,
            blocks = blocks,
            truncated = truncated,
        )
    }

    private fun block(element: JsonElement): ArticleBlock? {
        val item = element as? JsonObject ?: return null
        val kind = KINDS[item.string("k")] ?: return null
        val spans = (item["s"] as? JsonArray).orEmpty().mapNotNull(::span)
        if (spans.isEmpty()) return null
        val level = (item["l"] as? JsonPrimitive)?.intOrNull ?: 0
        val bounded = when (kind) {
            BlockKind.HEADING -> level.coerceIn(1, MAX_HEADING_LEVEL)
            BlockKind.LIST_ITEM -> level.coerceIn(1, MAX_LIST_DEPTH)
            else -> 0
        }
        return ArticleBlock(kind, bounded, spans)
    }

    private fun span(element: JsonElement): ArticleSpan? {
        val run = element as? JsonArray ?: return null
        val text = (run.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val href = (run.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        return ArticleSpan(text, href)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
