package ch.lkmc.combray.core.integration

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Addresses that look like they carry a login or session credential (a Kagi private-session
 * link, an emailed sign-in link, user:password@). They are not sent to Karakeep or Hister, and
 * the user is told why. This is a narrow guard, not a secret detector.
 */
object SensitiveUrls {
    private val CREDENTIAL_PARAMETERS = setOf(
        "token", "access_token", "id_token", "refresh_token", "auth", "password", "passwd", "pwd",
        "secret", "api_key", "apikey", "session", "sessionid", "sid",
    )

    fun looksSensitive(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (malformed: URISyntaxException) {
            return true
        }
        if (uri.rawUserInfo != null) return true
        // OAuth implicit-flow callbacks carry tokens in the fragment (#access_token=...).
        // Names are decoded once: ?%74oken= is token to a server, and a broken % is not trusted.
        return listOfNotNull(uri.rawQuery, uri.rawFragment).any { part ->
            part.split('&').any { parameter ->
                val name = parameterName(parameter.substringBefore('='))
                name == null || name in CREDENTIAL_PARAMETERS
            }
        }
    }

    /** Null when the name is not a safe decode, which the caller treats as sensitive. */
    private fun parameterName(raw: String): String? {
        if (raw.isEmpty()) return ""
        return try {
            URLDecoder.decode(raw, Charsets.UTF_8).lowercase()
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }
}
