package ch.lkmc.combray.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The service scopes send only text typed in their own field, never a prefill. */
class SearchPanelQueryTest {
    @Test
    fun histerSendsOnlyTypedText() {
        assertEquals(null, searchQuery("prefilled", typed = false))
        assertEquals(null, searchQuery("", typed = false))
        assertEquals(null, searchQuery("   ", typed = true))
        assertEquals("prefilledx", searchQuery("  prefilledx  ", typed = true))
    }

    @Test
    fun karakeepShowsTheNewestBookmarksUntilThePrefillIsEdited() {
        assertEquals("", bookmarkQuery("prefilled", typed = false))
        assertEquals("", bookmarkQuery("", typed = false))
        assertEquals("", bookmarkQuery("", typed = true))
        assertEquals("prefilledx", bookmarkQuery("  prefilledx  ", typed = true))
    }
}
