package ch.lkmc.combray

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.URISyntaxException

/** What became of a link handed to another app. */
sealed interface ExternalOutcome {
    data object Opened : ExternalOutcome

    /** No app takes it; the link named a web page to show instead (`S.browser_fallback_url`). */
    data class Fallback(val url: String) : ExternalOutcome

    data object NoApp : ExternalOutcome
}

/**
 * Opens a page's link for another app: `mailto:`, `tel:`, `geo:`, `market:` as plain views, and
 * Android `intent:` links the way Chrome does. A page wrote the intent, so it may only reach
 * apps that accept browsable links: no explicit component or selector, no URI permission grants.
 */
object ExternalLinks {
    private const val INTENT_SCHEME = "intent:"
    private const val FALLBACK_EXTRA = "browser_fallback_url"

    /** URI grants a page could use to hand Combray's own files to another app. */
    private const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun open(context: Context, url: String): ExternalOutcome {
        val intent = intentFor(url) ?: return ExternalOutcome.NoApp
        return try {
            context.startActivity(intent)
            ExternalOutcome.Opened
        } catch (none: ActivityNotFoundException) {
            intent.getStringExtra(FALLBACK_EXTRA)
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { ExternalOutcome.Fallback(it) }
                ?: ExternalOutcome.NoApp
        }
    }

    private fun intentFor(url: String): Intent? {
        if (!url.startsWith(INTENT_SCHEME, ignoreCase = true)) {
            return Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val parsed = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (malformed: URISyntaxException) {
            return null
        }
        return parsed.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
            flags = flags and URI_GRANTS.inv()
        }
    }
}
