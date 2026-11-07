package ch.lkmc.combray.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A server scope sends nothing until the user acts, except the panel's own blank default. */
class SearchGatesTest {
    @Test
    fun histerSearchesOnlyAfterTheUserActs() {
        assertFalse(shouldRunSearch("", requested = false, blankShowsAll = false))
        assertFalse(shouldRunSearch("", requested = true, blankShowsAll = false))
        // The seeded local filter is not text typed here.
        assertFalse(shouldRunSearch("madeleine", requested = false, blankShowsAll = false))
        assertTrue(shouldRunSearch("madeleine", requested = true, blankShowsAll = false))
    }

    @Test
    fun karakeepShowsTheNewestBookmarksOnABlankFieldOnly() {
        assertTrue(shouldRunSearch("", requested = false, blankShowsAll = true))
        assertFalse(shouldRunSearch("madeleine", requested = false, blankShowsAll = true))
        assertTrue(shouldRunSearch("madeleine", requested = true, blankShowsAll = true))
    }
}
