package ch.lkmc.combray.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressPolicyTest {
    private fun primary(input: String) = AddressPolicy.interpret(input).primary

    private fun navigates(input: String, expected: String) =
        assertEquals(AddressDecision.Navigate(expected), primary(input))

    private fun searches(input: String) {
        val decision = primary(input)
        assertTrue("$input should search, was $decision", decision is AddressDecision.Search)
        assertEquals(input.trim(), (decision as AddressDecision.Search).query)
    }

    @Test
    fun completeWebAddressesNavigateAsTyped() {
        navigates("https://example.org/a?b=c#d", "https://example.org/a?b=c#d")
        navigates("  http://example.org  ", "http://example.org")
        navigates("HTTPS://EXAMPLE.ORG", "HTTPS://EXAMPLE.ORG")
    }

    @Test
    fun schemelessHostsGainHttpsAndOfferSearch() {
        val interpretation = AddressPolicy.interpret("example.org/path")
        assertEquals(AddressDecision.Navigate("https://example.org/path"), interpretation.primary)
        assertTrue(interpretation.alternative is AddressDecision.Search)

        navigates("localhost:8080", "https://localhost:8080")
        navigates("192.168.1.10", "https://192.168.1.10")
        navigates("[::1]:8443/x", "https://[::1]:8443/x")
        navigates("bücher.de", "https://bücher.de")
        navigates("xn--bcher-kva.de", "https://xn--bcher-kva.de")
    }

    @Test
    fun singleWordsSearchButIntranetHostsWithPortOrPathNavigate() {
        val word = AddressPolicy.interpret("router")
        assertTrue(word.primary is AddressDecision.Search)
        assertEquals(AddressDecision.Navigate("https://router"), word.alternative)

        navigates("nas:5000", "https://nas:5000")
        navigates("router/", "https://router/")
    }

    @Test
    fun textQueriesSearchAndAreEncodedOnce() {
        searches("what is servo")
        searches("!w Zürich")
        searches("a&b#c")
        searches("😀")
        searches("10.5")
        searches("user@example.org")
        searches("define:cat")

        val search = primary("C++ & Rust #1 ü 😀") as AddressDecision.Search
        assertEquals("https://kagi.com/search?q=C%2B%2B%20%26%20Rust%20%231%20%C3%BC%20%F0%9F%98%80", search.url)
    }

    @Test
    fun aKagiResultsAddressGivesBackItsQuery() {
        for (query in listOf("servo android build", "Zürich 😀 日本", "a&b=c", "#1 c#", "C++ a+b", "100% %20", "  padded  ")) {
            assertEquals(query, AddressPolicy.kagiQuery(AddressPolicy.kagiSearchUrl(query)))
        }

        // Kagi's own search field submits "+" for spaces; other parameters and the fragment are ignored.
        assertEquals("foo bar", AddressPolicy.kagiQuery("https://kagi.com/search?q=foo+bar"))
        assertEquals("a b", AddressPolicy.kagiQuery("HTTPS://KAGI.com/search?r=ch&q=a%20b&q=c#top"))
    }

    @Test
    fun otherAddressesAndMissingQueriesGiveNoKagiQuery() {
        val others = listOf(
            "http://kagi.com/search?q=x",
            "https://www.kagi.com/search?q=x",
            "https://kagi.com.evil.test/search?q=x",
            "https://evil.test/search?q=x",
            "https://evil.test/?u=https://kagi.com/search?q=x",
            "https://kagi.com:8443/search?q=x",
            "https://user@kagi.com/search?q=x",
            "https://kagi.com/search/?q=x",
            "https://kagi.com/images?q=x",
            "https://kagi.com/?q=x",
            "https://kagi.com/search?q=%zz",
            "not an address",
        )
        val missing = listOf(
            "https://kagi.com/search",
            "https://kagi.com/search?r=ch",
            "https://kagi.com/search?q",
            "https://kagi.com/search?q=",
            "https://kagi.com/search?q=%20",
            "https://kagi.com/search?qq=x",
            "https://kagi.com/search#q=x",
        )
        for (url in others + missing) {
            assertNull(url, AddressPolicy.kagiQuery(url))
        }
    }

    @Test
    fun unsafeSchemesAreRejectedNotSearched() {
        for (input in listOf("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "intent://x#Intent;end", "ftp://example.org")) {
            assertEquals(input, AddressDecision.Rejected(RejectReason.UNSUPPORTED_SCHEME), primary(input))
        }
    }

    @Test
    fun credentialsInAddressesAreRejected() {
        assertEquals(AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS), primary("https://user:secret@example.org/"))
    }

    @Test
    fun mailAndPhoneLinksGoToOtherApps() {
        assertEquals(AddressDecision.External("mailto:a@example.org"), primary("mailto:a@example.org"))
        assertEquals(AddressDecision.External("tel:+41123"), primary("tel:+41123"))
        // Not host "tel" with port 112.
        assertEquals(AddressDecision.External("tel:112"), primary("tel:112"))
    }

    @Test
    fun pastedAddressesWithSpacesStayAddresses() {
        navigates("https://example.org/a b", "https://example.org/a%20b")
        assertEquals(AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS), primary("https://user:secret@example.org/a b"))
        assertEquals(AddressDecision.Rejected(RejectReason.UNSUPPORTED_SCHEME), primary("javascript: alert(1)"))
    }

    @Test
    fun emptyAndMalformedInputIsRejected() {
        assertEquals(AddressDecision.Rejected(RejectReason.EMPTY), primary("   "))
        assertEquals(AddressDecision.Rejected(RejectReason.MALFORMED_ADDRESS), primary("https://"))
        assertNull(AddressPolicy.interpret("https://").alternative)
    }

    @Test
    fun hostMatchingIsByLabelNotBySuffixString() {
        assertTrue(HostNames.matches("a.example.com", "example.com", includeSubdomains = true))
        assertFalse(HostNames.matches("badexample.com", "example.com", includeSubdomains = true))
        assertFalse(HostNames.matches("a.example.com", "example.com", includeSubdomains = false))
        assertEquals("xn--bcher-kva.de", HostNames.of("https://BÜCHER.de./x"))
    }

    @Test
    fun hostsShowInUnicodeOnlyWhenTheyCannotImitateAnotherSite() {
        assertEquals("bücher.de", HostNames.forDisplay("xn--bcher-kva.de"))
        assertEquals("日本語.jp", HostNames.forDisplay("xn--wgv71a119e.jp"))
        assertEquals("example.org", HostNames.forDisplay("example.org"))

        // Cyrillic "аррӏе", Latin with one Cyrillic letter, Latin alpha, Greek: all stay punycode.
        assertEquals("xn--80ak6aa92e.com", HostNames.forDisplay("xn--80ak6aa92e.com"))
        assertEquals("xn--pypal-4ve.com", HostNames.forDisplay("xn--pypal-4ve.com"))
        assertEquals("xn--pple-p5b.com", HostNames.forDisplay("xn--pple-p5b.com"))
        assertEquals("xn--hxakic4aa.gr", HostNames.forDisplay("xn--hxakic4aa.gr"))
    }

    @Test
    fun latinLookAlikesAndStrayKanaMarksStayPunycode() {
        // Dotless i, kra, l with stroke, thorn: Latin letters that pass for i, k, l and p.
        for (spoof in listOf("gıthub.com", "ĸagi.com", "łinkedin.com", "þaypal.com", "tーonline.de", "ー.jp")) {
            val ascii = HostNames.normalize(spoof)!!
            assertTrue(ascii, ascii.startsWith("xn--"))
            assertEquals(spoof, ascii, HostNames.forDisplay(ascii))
        }
    }

    @Test
    fun scriptsWithVowelSignsAndKanaLengthMarksShowInUnicode() {
        for (host in listOf("ソニー.jp", "हिंदी.भारत", "ไทย.th")) {
            assertEquals(host, HostNames.forDisplay(HostNames.normalize(host)!!))
        }
    }
}
