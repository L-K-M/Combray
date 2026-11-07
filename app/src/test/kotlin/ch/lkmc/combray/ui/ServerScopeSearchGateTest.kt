package ch.lkmc.combray.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server scope of Recent prefills its field from the local filter but must not send that text:
 * the first request needs a keystroke or the Search key inside the panel.
 */
class ServerScopeSearchGateTest {
    @Test
    fun aPrefilledFilterIsNotSentUntilTheUserTypesInThePanel() {
        assertFalse(serverScopeMaySearch(prefilled = "soup", query = "soup", interacted = false))
    }

    @Test
    fun aKeystrokeOrSearchKeyInThePanelAllowsIt() {
        assertTrue(serverScopeMaySearch(prefilled = "soup", query = "soup", interacted = true))
    }

    @Test
    fun editedTextDiffersFromThePrefillAndIsSent() {
        assertTrue(serverScopeMaySearch(prefilled = "soup", query = "soup recipe", interacted = false))
    }

    @Test
    fun anEmptyPrefillWithEmptyQueryIsNotASearch() {
        // The Karakeep scope lists the newest bookmarks for a blank query; the helper only
        // gates non-blank searches, which the panels check separately.
        assertFalse(serverScopeMaySearch(prefilled = "", query = "", interacted = false))
    }
}
