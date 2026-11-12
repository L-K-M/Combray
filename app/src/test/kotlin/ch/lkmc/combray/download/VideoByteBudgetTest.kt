package ch.lkmc.combray.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executors

class VideoByteBudgetTest {
    @Test
    fun shortReadsRefundTheirUnusedReservation() {
        val budget = VideoByteBudget(10)
        assertEquals(8, budget.reserve(8))
        budget.refund(5)
        assertEquals(7, budget.reserve(100))
        assertEquals(VideoDownloadFailure.TOO_LARGE, assertThrows(VideoDownloadException::class.java) { budget.reserve(1) }.failure)
    }

    @Test
    fun concurrentTracksCannotEachSpendTheEntireBudget() {
        val budget = VideoByteBudget(100)
        val threads = Executors.newFixedThreadPool(4)
        try {
            val reads = (0 until 20).map { threads.submit<Int> { try { budget.reserve(13) } catch (_: VideoDownloadException) { 0 } } }
            assertEquals(100, reads.sumOf { it.get() })
            assertThrows(VideoDownloadException::class.java) { budget.reserve(1) }
        } finally {
            threads.shutdownNow()
        }
    }
}
