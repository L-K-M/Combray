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
        return listOfNotNull(uri.rawQuery, uri.rawFragment).any { part ->
            part.split('&').any { isCredentialName(it.substringBefore('=')) }
        }
    }

    /**
     * Names are percent-decoded once so `%74oken` cannot hide a credential. Decoding again is
     * not done: `%2574oken` becomes the literal name "%74oken", not a credential. An escape
     * the decoder rejects is treated conservatively, like a malformed URI.
     */
    private fun isCredentialName(name: String): Boolean {
        val decoded = try {
            URLDecoder.decode(name, Charsets.UTF_8.name())
        } catch (malformed: IllegalArgumentException) {
            return true
        }
        return decoded.lowercase() in CREDENTIAL_PARAMETERS
    }
}
