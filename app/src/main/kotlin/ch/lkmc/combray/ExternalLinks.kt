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
 * other apps that accept browsable links: no explicit component or selector, no flags (task
 * changes, URI permission grants), not Combray itself.
 */
object ExternalLinks {
    private const val INTENT_SCHEME = "intent:"
    private const val FALLBACK_EXTRA = "browser_fallback_url"

    /** The schemes the engine hands on (`native/src/navigation.rs`); anything else is not launched. */
    private val APP_LINK_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "mms", "geo", "market", "intent")

    /**
     * Device data an `intent:` link must not point another app at, as in Chrome. A `file:` URI
     * would also make startActivity throw (Android's FileUriExposedException) inside Combray.
     */
    private val LOCAL_DATA_SCHEMES = setOf("file", "content")

    /** Whether [url] has one of the schemes the engine hands to the application for another app. */
    internal fun isAppLink(url: String): Boolean =
        url.substringBefore(':', missingDelimiterValue = "").lowercase() in APP_LINK_SCHEMES

    /** [context] must be an Activity: the intents carry no flags, so no NEW_TASK either. */
    fun open(context: Context, url: String): ExternalOutcome {
        if (!isAppLink(url)) return ExternalOutcome.NoApp
        val intent = intentFor(url, context.packageName) ?: return ExternalOutcome.NoApp
        return try {
            context.startActivity(intent)
            ExternalOutcome.Opened
        } catch (none: ActivityNotFoundException) {
            fallbackOrNoApp(intent)
        } catch (refused: SecurityException) {
            // The app that matched does not let Combray start it (not exported, or it needs a
            // permission Combray lacks). Its fallback page still applies, as in Chrome.
            fallbackOrNoApp(intent)
        }
    }

    /** The link's own web page (`S.browser_fallback_url`) when no app opens it. */
    private fun fallbackOrNoApp(intent: Intent): ExternalOutcome =
        intent.getStringExtra(FALLBACK_EXTRA)
            ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            ?.let { ExternalOutcome.Fallback(it) }
            ?: ExternalOutcome.NoApp

    /**
     * The intent for [url]; null when it is malformed, names [ownPackage] (Combray itself), or
     * points at device files.
     */
    internal fun intentFor(url: String, ownPackage: String): Intent? {
        if (!url.startsWith(INTENT_SCHEME, ignoreCase = true)) {
            return Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }

        // parseUri throws more than its declared URISyntaxException: a malformed number in an
        // "i." extra or in launchFlags is a NumberFormatException. A page wrote it, so neither
        // may crash Combray.
        val parsed = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (malformed: URISyntaxException) {
            return null
        } catch (malformed: IllegalArgumentException) {
            return null
        }
        if (parsed.`package` == ownPackage) return null
        if (parsed.data?.scheme?.lowercase() in LOCAL_DATA_SCHEMES) return null
        return parsed.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
            // Plain links carry no flags either: no CLEAR_TASK or CLEAR_TOP, no URI grants.
            flags = 0
        }
    }
}
