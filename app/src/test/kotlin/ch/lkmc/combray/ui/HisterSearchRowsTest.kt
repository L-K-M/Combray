package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.HisterHit
import org.junit.Assert.assertEquals
import org.junit.Test

class HisterSearchRowsTest {
    private fun hit(url: String, fromHistory: Boolean = false) = HisterHit(url, "Title", null, fromHistory)

    @Test
    fun oneRowPerAddressEvenWhenAPageRepeatsIt() {
        // Hister names one address twice when its index and its history both match. The list
        // keys rows by address and crashes on a duplicate key.
        val rows = mergeHits(
            emptyList(),
            listOf(hit("https://example.org/a"), hit("https://example.org/a", fromHistory = true)),
        )
        assertEquals(listOf("https://example.org/a"), rows.map { it.url })
    }

    @Test
    fun anEarlierPageKeepsItsRowOfARepeatedAddress() {
        val first = hit("https://example.org/a")
        val rows = mergeHits(
            listOf(first),
            listOf(hit("https://example.org/a", fromHistory = true), hit("https://example.org/b")),
        )
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), rows.map { it.url })
        assertEquals(listOf(first), rows.filter { it.url == first.url })
    }

    @Test
    fun rowsKeepArrivalOrder() {
        val rows = mergeHits(
            listOf(hit("https://example.org/a")),
            listOf(hit("https://example.org/b"), hit("https://example.org/c")),
        )
        assertEquals(listOf("https://example.org/a", "https://example.org/b", "https://example.org/c"), rows.map { it.url })
    }
}
