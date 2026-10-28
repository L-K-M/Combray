package ch.lkmc.combray.player

import ch.lkmc.combray.core.media.PlaybackRequest
import java.util.UUID

/**
 * Playback requests on their way to [PlayerActivity]. The Intent carries only an id: page media
 * addresses never reach the activity system (Intents are logged and kept in Recents). After
 * process death the id is gone, and the player closes.
 */
class PlaybackRequests {
    private val requests = object : LinkedHashMap<String, PlaybackRequest>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaybackRequest>) = size > MAX_REQUESTS
    }

    /** Keeps [request] and returns its id; the oldest of more than [MAX_REQUESTS] goes. */
    @Synchronized
    fun offer(request: PlaybackRequest): String {
        val id = UUID.randomUUID().toString()
        requests[id] = request
        return id
    }

    @Synchronized
    fun get(id: String): PlaybackRequest? = requests[id]

    /** Called when the player for [id] finishes. */
    @Synchronized
    fun remove(id: String) {
        requests.remove(id)
    }

    private companion object {
        /** One player at a time, plus a few taps the activity system has not delivered yet. */
        const val MAX_REQUESTS = 4
    }
}
