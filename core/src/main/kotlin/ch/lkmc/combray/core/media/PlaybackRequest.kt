package ch.lkmc.combray.core.media

import ch.lkmc.combray.engine.MediaKind
import okhttp3.HttpUrl

/**
 * One page's media, ready for the player: what to fetch ([url]), for which page ([origin]),
 * with Servo's `User-Agent` ([userAgent]). [name] and [site] are what the player shows.
 */
class PlaybackRequest(
    val url: HttpUrl,
    val kind: MediaKind,
    val origin: MediaOrigin,
    val userAgent: String,
    val name: String,
    val site: String,
) {
    /** Page media addresses stay out of logs. */
    override fun toString() = "PlaybackRequest(kind=$kind, origin=$origin)"
}
