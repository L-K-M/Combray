package ch.lkmc.combray

import android.app.SearchManager
import android.content.Intent
import ch.lkmc.combray.core.policy.AddressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aLinkSharedAsTheSubjectOnlyStillOpens() {
        val subjectOnly = IncomingIntents.interpret(Intent.ACTION_SEND, null) {
            if (it == Intent.EXTRA_SUBJECT) "https://example.org/a" else null
        }
        assertEquals(Incoming.Open("https://example.org/a"), subjectOnly)
    }

    @Test
    fun aSubjectLinkWinsOverEmptyOrLinklessText() {
        fun share(text: String?, subject: String?) = IncomingIntents.interpret(Intent.ACTION_SEND, null) {
            when (it) {
                Intent.EXTRA_TEXT -> text
                Intent.EXTRA_SUBJECT -> subject
                else -> null
            }
        }
        assertEquals(Incoming.Open("https://example.org/a"), share("", "https://example.org/a"))
        assertEquals(Incoming.Open("https://example.org/a"), share("look at this", "https://example.org/a"))
        assertEquals(Incoming.Open("https://example.org/t"), share("https://example.org/t", "https://example.org/s"))
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

    // Any app can share without a tap, and this runs on the main thread: a trim that copied and
    // recounted the link once per character took seconds here.
    @Test(timeout = 1_000)
    fun longRunsOfClosingCharactersAreTrimmedInOnePass() {
        assertEquals("https://a", IncomingIntents.firstWebAddress("https://a" + ")".repeat(32_000)))
        assertEquals("https://a", IncomingIntents.firstWebAddress("https://a" + ".".repeat(32_000)))
        assertEquals("https://a/(b)", IncomingIntents.firstWebAddress("https://a/(b)" + "),".repeat(16_000)))
    }

    @Test
    fun aLinkLongerThanTheEngineTakesIsRefusedNotCut() {
        // 32,768 characters, the engine's limit (native MAX_URL_CHARS).
        val longest = "https://a.example/" + "x".repeat(32_768 - 18)
        assertEquals(longest, IncomingIntents.firstWebAddress(longest))
        assertNull(IncomingIntents.firstWebAddress(longest + "x"))
        assertTrue(send("see ${longest}x") !is Incoming.Open)
    }

    @Test
    fun aLinkRunningPastTheSearchedPartOfAHugeShareIsRefusedNotCut() {
        // Only the first 65,536 characters are searched for a link.
        val text = "a ".repeat(32_760) + "https://a.example/" + "x".repeat(100)
        assertNull(IncomingIntents.firstWebAddress(text))
        assertNull(IncomingIntents.firstWebAddress("a ".repeat(40_000) + "https://a.example/"))
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
    fun searchWithKagiNeverSendsAPasswordOrTokenInTheSelection() {
        // Refused by the address policy: the editor says why.
        val login = "Log in at https://me:secret@example.org/ today"
        assertEquals(LaunchRequest.EditNewTab(login), processText(login))
        assertEquals(LaunchRequest.EditNewTab("admin:hunter2@nas is the login"), processText("admin:hunter2@nas is the login"))
        // A link with a token goes to the editor alone, as from a share.
        assertEquals(
            LaunchRequest.EditNewTab("https://accounts.example/reset?token=abc123"),
            processText("Reset here https://accounts.example/reset?token=abc123 thanks"),
        )
        // A link with nothing secret in it is searched with its text.
        assertEquals(Incoming.Open(AddressPolicy.kagiSearchUrl("what is https://example.org/a")), processText("what is https://example.org/a"))
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
