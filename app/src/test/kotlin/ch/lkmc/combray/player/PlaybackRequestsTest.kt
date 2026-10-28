package ch.lkmc.combray.player

import ch.lkmc.combray.core.media.MediaPolicy
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.engine.MediaKind
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackRequestsTest {
    private fun request(n: Int) = PlaybackRequest(
        "https://cdn.example.org/$n.mp4".toHttpUrl(),
        MediaKind.VIDEO,
        MediaPolicy.origin("https://example.org/", "https://example.org"),
        "Servo/0.7",
        "$n.mp4",
        "cdn.example.org",
    )

    @Test
    fun eachRequestGetsAnUnguessableIdUntilItsPlayerFinishes() {
        val requests = PlaybackRequests()
        val first = request(1)
        val id = requests.offer(first)
        val again = requests.offer(first)
        assertNotEquals(id, again)
        assertSame(first, requests.get(id))
        requests.remove(id)
        assertNull(requests.get(id))
    }

    @Test
    fun onlyTheNewestFourAreKept() {
        val requests = PlaybackRequests()
        val ids = (1..5).map { requests.offer(request(it)) }
        assertNull(requests.get(ids.first()))
        ids.drop(1).forEach { assertNotEquals(null, requests.get(it)) }
    }
}
