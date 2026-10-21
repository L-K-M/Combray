package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import ch.lkmc.combray.core.policy.AddressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingIntentsTest {
    private fun send(text: String?) = IncomingIntents.interpret(Intent.ACTION_SEND, null) { if (it == Intent.EXTRA_TEXT) text else null }

    private fun processText(text: String) =
        IncomingIntents.interpret(Intent.ACTION_PROCESS_TEXT, null) { if (it == Intent.EXTRA_PROCESS_TEXT) text else null }

    @Test
    fun aSharedLinkInASentenceOpensWithoutTheFullStop() {
        assertEquals(Incoming.Open("http://localhost:8765/a.html"), send("see http://localhost:8765/a.html."))
    }

    @Test
    fun theFirstLinkWinsAndAnUppercaseSchemeCounts() {
        assertEquals(Incoming.Open("HTTPS://a.example/x"), send("Two: HTTPS://a.example/x, and https://b.example/"))
    }

    @Test
    fun closingPunctuationAndBracketsAroundALinkAreNotPartOfIt() {
        assertEquals("https://example.org/a", IncomingIntents.firstWebAddress("(https://example.org/a),"))
        assertEquals("https://example.org/?q=1", IncomingIntents.firstWebAddress("\"https://example.org/?q=1\"!"))
        assertEquals("https://example.org/a", IncomingIntents.firstWebAddress("<https://example.org/a>"))
        assertEquals("https://example.org/a", IncomingIntents.firstWebAddress("[link](https://example.org/a)"))
        assertEquals("https://example.org/a", IncomingIntents.firstWebAddress("“https://example.org/a”"))
    }

    @Test
    fun bracketsTheLinkOpensItselfStay() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Swann_(novel)",
            IncomingIntents.firstWebAddress("(see https://en.wikipedia.org/wiki/Swann_(novel)).")
        )
    }

    @Test
    fun aSchemeWithoutAnAddressIsNoLink() {
        assertNull(IncomingIntents.firstWebAddress("https://."))
        assertNull(IncomingIntents.firstWebAddress("no link here"))
    }

    @Test
    fun sharedTextWithoutALinkWaitsInTheEditorOnOneLine() {
        assertEquals(LaunchRequest.EditNewTab("Proust madeleine"), send("  Proust\n\tmadeleine  "))
        assertEquals(LaunchRequest.EditNewTab("example.org"), send("example.org"))
    }

    @Test
    fun otherSchemesAreNeverOpenedFromSharedText() {
        // The editor refuses it and says why; nothing is loaded.
        assertEquals(LaunchRequest.EditNewTab("javascript:alert(1)"), send("javascript:alert(1)"))
    }

    @Test
    fun aRefusedLinkGoesToTheEditorWithoutTheTextAroundIt() {
        assertEquals(LaunchRequest.EditNewTab("https://me:secret@example.org/"), send("Log in at https://me:secret@example.org/ today"))
    }

    @Test
    fun blankOrMissingSharedTextDoesNothing() {
        assertNull(send("  \n "))
        assertNull(send(null))
    }

    @Test
    fun longSharedTextIsCutWithoutSplittingACharacter() {
        val text = "a".repeat(1_999) + "😀" + "b".repeat(100)
        val request = send(text) as LaunchRequest.EditNewTab
        assertEquals("a".repeat(1_999), request.text)
        assertEquals(2_000, (send("c".repeat(3_000)) as LaunchRequest.EditNewTab).text.length)
    }

    @Test
    fun aWebSearchWaitsInTheEditor() {
        val incoming = IncomingIntents.interpret(Intent.ACTION_WEB_SEARCH, null) { if (it == SearchManager.QUERY) "madeleine de Proust" else null }
        assertEquals(LaunchRequest.EditNewTab("madeleine de Proust"), incoming)
    }

    @Test
    fun searchWithKagiSearchesAtOnceOrOpensAnAddress() {
        assertEquals(Incoming.Open(AddressPolicy.kagiSearchUrl("madeleine de Proust")), processText("madeleine de Proust"))
        assertEquals(Incoming.Open("https://example.org/a"), processText("https://example.org/a"))
        assertEquals(LaunchRequest.EditNewTab("mailto:a@example.org"), processText("mailto:a@example.org"))
        assertEquals(LaunchRequest.EditNewTab("javascript:alert(1)"), processText("javascript:alert(1)"))
    }

    @Test
    fun viewOpensOnlyWebAddresses() {
        assertEquals(Incoming.Open("https://example.org/"), IncomingIntents.interpret(Intent.ACTION_VIEW, "https://example.org/") { null })
        assertNull(IncomingIntents.interpret(Intent.ACTION_VIEW, "javascript:alert(1)") { null })
        assertNull(IncomingIntents.interpret(Intent.ACTION_VIEW, null) { null })
    }

    @Test
    fun launcherShortcutsAskForTheirSheets() {
        assertEquals(LaunchRequest.EditNewTab(""), IncomingIntents.interpret(IncomingIntents.ACTION_NEW_TAB, null) { null })
        assertEquals(LaunchRequest.SearchHistory, IncomingIntents.interpret(IncomingIntents.ACTION_SEARCH_HISTORY, null) { null })
    }

    @Test
    fun otherActionsAreIgnored() {
        assertNull(IncomingIntents.interpret(Intent.ACTION_MAIN, null) { "https://example.org/" })
        assertNull(IncomingIntents.interpret(null, "https://example.org/") { null })
    }
}
