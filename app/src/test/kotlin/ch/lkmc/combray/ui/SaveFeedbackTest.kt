package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveFeedbackTest {
    private val page = "https://a.test/article"
    private val other = "https://b.test/"

    private fun feedback(previous: SaveStatus, current: SaveStatus, previousUrl: String? = page) =
        saveFeedback(PageSaveStatus(previousUrl, previous), PageSaveStatus(page, current))

    @Test
    fun anAcknowledgedSaveConfirms() {
        assertEquals(SaveFeedback.CONFIRM, feedback(SaveStatus.Pending, SaveStatus.Saved))
    }

    @Test
    fun aSaveThatNeedsAttentionRejects() {
        assertEquals(SaveFeedback.REJECT, feedback(SaveStatus.Pending, SaveStatus.NeedsAttention("auth")))
    }

    @Test
    fun anAlreadySavedPageIsSilent() {
        // Opening a saved page: the first status read for it, or a reload of the same address.
        assertNull(feedback(SaveStatus.Unsaved, SaveStatus.Saved))
        assertNull(feedback(SaveStatus.Saved, SaveStatus.Saved))
        // Setting Karakeep up again finds the page saved there before.
        assertNull(feedback(SaveStatus.NotConfigured, SaveStatus.Saved))
    }

    @Test
    fun anOldFailureIsSilent() {
        assertNull(feedback(SaveStatus.Unsaved, SaveStatus.NeedsAttention(null)))
        assertNull(feedback(SaveStatus.NotConfigured, SaveStatus.NeedsAttention(null)))
        assertNull(feedback(SaveStatus.NeedsAttention("network"), SaveStatus.NeedsAttention("auth")))
    }

    @Test
    fun anotherPagesPendingSaveIsSilent() {
        // The bar still showed the previous page's pending save when this page's status arrived.
        assertNull(feedback(SaveStatus.Pending, SaveStatus.Saved, previousUrl = other))
        assertNull(feedback(SaveStatus.Pending, SaveStatus.NeedsAttention(null), previousUrl = other))
        assertNull(feedback(SaveStatus.Pending, SaveStatus.Saved, previousUrl = null))
    }

    @Test
    fun aSaveStillOnItsWayIsSilent() {
        assertNull(feedback(SaveStatus.Unsaved, SaveStatus.Pending))
        assertNull(feedback(SaveStatus.Pending, SaveStatus.Pending))
        // Discarded before delivery.
        assertNull(feedback(SaveStatus.Pending, SaveStatus.Unsaved))
    }
}
