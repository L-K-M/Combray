package ch.lkmc.combray.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTextTest {
    @Test
    fun aPageWithATitleSharesTitleAndAddress() {
        assertEquals("How to Sail\nhttps://example.org/sailing", shareText("https://example.org/sailing", "How to Sail"))
    }

    @Test
    fun aPageWithoutATitleSharesOnlyTheAddress() {
        assertEquals("https://example.org/untitled", shareText("https://example.org/untitled", null))
        assertEquals("https://example.org/blank-title", shareText("https://example.org/blank-title", "  "))
    }

    @Test
    fun aPaddedTitleIsTrimmedAndItsBreaksCollapseIntoOneLine() {
        assertEquals(
            "How to Sail\nhttps://example.org/sailing",
            shareText("https://example.org/sailing", "  How to\nSail  "),
        )
    }

    @Test
    fun unicodeSeparatorsCollapseToo() {
        assertEquals(
            "How to Sail\nhttps://example.org/sailing",
            shareText("https://example.org/sailing", "How\u2028to\u2029Sail"),
        )
        assertEquals("How to Sail", shareTitle("\u0085How\u0085to Sail"))
    }

    @Test
    fun aLongTitleIsCappedWithoutSplittingACharacter() {
        val words = "word ".repeat(100)
        assertEquals("word ".repeat(40).trimEnd() + "\u2026", shareTitle(words))

        // An emoji straddling the cut is dropped whole rather than leaving half a surrogate pair.
        val emoji = "a".repeat(199) + "\uD83D\uDE00" + "tail"
        assertEquals("a".repeat(199) + "\u2026", shareTitle(emoji))
    }
}
