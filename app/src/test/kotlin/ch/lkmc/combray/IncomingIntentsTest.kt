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
        // Only the first mebibyte is searched for a link.
        val text = "a ".repeat(524_280) + "https://a.example/" + "x".repeat(100)
        assertNull(IncomingIntents.firstWebAddress(text))
        assertNull(IncomingIntents.firstWebAddress("a ".repeat(600_000) + "https://a.example/"))
    }

    @Test
    fun aLinkAfterTheOldScanLimitIsStillFound() {
        // 80,000 characters of prose, then the link: well past the old 64 KiB limit.
        assertEquals("https://a.example/", IncomingIntents.firstWebAddress("a ".repeat(40_000) + "https://a.example/"))
    }

    @Test
    fun aLinkEndingInsideTheNewScanLimitIsFound() {
        // The link starts at 1,048,000 and ends at 1,048,018, inside the first mebibyte.
        assertEquals("https://a.example/", IncomingIntents.firstWebAddress("a ".repeat(524_000) + "https://a.example/"))
    }

    @Test
    fun aSharedLinkPastTheOldScanLimitOpens() {
        val text = "a ".repeat(40_000) + "https://a.example/"
        assertEquals(Incoming.Open("https://a.example/"), send(text))
    }

    @Test
    fun aSensitiveLinkPastTheOldScanLimitStillGoesToTheEditor() {
        val address = "https://me:secret@example.org/"
        assertEquals(LaunchRequest.EditNewTab(address), send("a ".repeat(40_000) + address))
    }

    @Test
    fun aMalformedLinkPastTheOldScanLimitIsNoLink() {
        assertNull(IncomingIntents.firstWebAddress("a ".repeat(40_000) + "https://."))
    }

    @Test
    fun aMaliciousLongCandidateIsRefusedNotCut() {
        // A candidate that fills the whole scan window is longer than the engine takes.
        assertNull(IncomingIntents.firstWebAddress("https://a.example/" + "x".repeat(1_048_576)))
        // A candidate with a huge run of closing brackets is refused before the bracket pass.
        assertNull(IncomingIntents.firstWebAddress("https://a" + ")".repeat(1_000_000)))
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
    fun aMultiMegabyteShareStillYieldsTheBoundedLine() {
        val text = "madeleine " + "x".repeat(30_000_000)
        val edit = send(text) as LaunchRequest.EditNewTab
        assertEquals(2_000, edit.text.length)
        assertTrue(edit.text.startsWith("madeleine x"))
    }

    @Test(timeout = 1_000)
    fun megabytesOfWhitespaceAroundShortTextDoNotSwallowIt() {
        val text = " ".repeat(5_000_000) + "madeleine" + " ".repeat(5_000_000)
        assertEquals(LaunchRequest.EditNewTab("madeleine"), send(text))
    }

    @Test(timeout = 1_000)
    fun megabytesOfMultibyteWhitespaceAroundShortTextDoNotSwallowIt() {
        // Kotlin's trim() drops U+3000 at the ends, as it does ASCII spaces.
        val text = "\u3000".repeat(5_000_000) + "madeleine" + "\u3000".repeat(5_000_000)
        assertEquals(LaunchRequest.EditNewTab("madeleine"), send(text))
    }

    @Test
    fun interiorMultibyteWhitespaceIsPreserved() {
        // Java's \s is ASCII-only, so U+3000 inside the line stays as it is.
        assertEquals(LaunchRequest.EditNewTab("madeleine\u3000de Proust"), send("madeleine\u3000de Proust"))
    }

    @Test(timeout = 1_000)
    fun aHugeInteriorMultibyteWhitespaceRunIsBounded() {
        val edit = send("madeleine" + "\u3000".repeat(5_000_000) + "de Proust") as LaunchRequest.EditNewTab
        assertEquals(2_000, edit.text.length)
        assertTrue(edit.text.startsWith("madeleine"))
    }

    @Test
    fun aSupplementaryCharacterAtTheLineCapIsNotSplit() {
        // The emoji starts at 1998, so both halves fit; the next character is cut.
        val kept = "a".repeat(1_998) + "\uD83D\uDE00"
        assertEquals(LaunchRequest.EditNewTab(kept), send(kept + "b".repeat(100)))
        // The emoji starts at 1999, so the line ends before it and no half is kept.
        assertEquals(LaunchRequest.EditNewTab("a".repeat(1_999)), send("a".repeat(1_999) + "\uD83D\uDE00" + "b".repeat(100)))
    }

    @Test
    fun oneLineCollapsesWhitespaceTrimsAndSaysWhenItCut() {
        assertEquals(IncomingIntents.OneLine("Proust madeleine", cut = false), IncomingIntents.oneLine("  Proust\n\tmadeleine  "))
        assertNull(IncomingIntents.oneLine("   \u3000  "))
        assertEquals(IncomingIntents.OneLine("c".repeat(2_000), cut = false), IncomingIntents.oneLine("c".repeat(2_000)))
        val cut = IncomingIntents.oneLine("c".repeat(3_000))
        assertEquals(2_000, cut?.text?.length)
        assertEquals(true, cut?.cut)
    }

    @Test
    fun aHugeShareIsExaminedOnlyAsFarAsTheLineLimit() {
        val huge = CountingText("word ".repeat(300_000))
        val line = IncomingIntents.oneLine(huge)
        assertEquals(2_000, line?.text?.length)
        assertEquals(true, line?.cut)
        // Before the scan was bounded, trim-and-replace copied and read the whole share here.
        assertTrue("examined ${huge.reads} characters", huge.reads < 10_000)
    }

    @Test(timeout = 1_000)
    fun aLongWhitespaceRunBeforeTheTextIsSkipped() {
        assertEquals(LaunchRequest.EditNewTab("madeleine"), send(" ".repeat(300_000) + "madeleine"))
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
    fun searchWithKagiNeverOpensALinkTheLineLimitCut() {
        val link = "https://example.org/" + "a".repeat(3_000)
        assertEquals(Incoming.Open(link), processText(link))
        assertEquals(Incoming.Open(link), processText("  $link.  "))
        // Without a scheme there is no whole link to open: the editor shows what was kept.
        val schemeless = "example.org/" + "a".repeat(3_000)
        assertEquals(LaunchRequest.EditNewTab(schemeless.take(2_000)), processText(schemeless))
        assertEquals(LaunchRequest.EditNewTab(schemeless.take(2_000)), processText("$schemeless https://other.example/"))
        // Spaces around a short address are no cut.
        assertEquals(Incoming.Open("https://example.org"), processText("  example.org  "))
    }

    @Test
    fun searchWithKagiNeverSendsAPasswordOrTokenInTheSelection() {
        // Refused by the address policy: the editor says why.
        val login = "Log in at https://me:secret@example.org/ today"
        assertEquals(LaunchRequest.EditNewTab(login), processText(login))
        assertEquals(LaunchRequest.EditNewTab("admin:hunter2@nas is the login"), processText("admin:hunter2@nas is the login"))
        assertEquals(LaunchRequest.EditNewTab("login admin:hunter2@192.168.1.1: please"), processText("login admin:hunter2@192.168.1.1: please"))
        // A link with a token goes to the editor alone, as from a share.
        assertEquals(
            LaunchRequest.EditNewTab("https://accounts.example/reset?token=abc123"),
            processText("Reset here https://accounts.example/reset?token=abc123 thanks"),
        )
        // Also when a harmless link comes first, as in a reset mail with a help link.
        assertEquals(
            LaunchRequest.EditNewTab("https://accounts.example/reset?token=abc123"),
            processText("See https://example.org/help or reset at https://accounts.example/reset?token=abc123"),
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

    /**
     * The app came to the front for the link: showing nothing would read as a lost tap. The
     * editor says why an address is refused, as it does for a shared one.
     */
    @Test
    fun aViewedLinkThePolicyRefusesWaitsInTheEditor() {
        val withPassword = "https://user:secret@example.org/a"
        assertEquals(LaunchRequest.EditNewTab(withPassword), IncomingIntents.interpret(Intent.ACTION_VIEW, withPassword) { null })
        val shouting = "HTTP://user@example.org/"
        assertEquals(LaunchRequest.EditNewTab(shouting), IncomingIntents.interpret(Intent.ACTION_VIEW, shouting) { null })

        // Only a web address is a link to explain; the manifest offers Combray for no other.
        assertNull(IncomingIntents.interpret(Intent.ACTION_VIEW, "intent://user@example.org/#Intent;end") { null })
        assertNull(IncomingIntents.interpret(Intent.ACTION_VIEW, "https") { null })
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

/** Counts how many characters a scan reads, so a test can prove the scan stops early. */
private class CountingText(private val body: String) : CharSequence {
    var reads = 0

    override val length get() = body.length

    override fun get(index: Int): Char = body[index].also { reads++ }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = body.subSequence(startIndex, endIndex)
}
