package ch.lkmc.combray.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        navigates("bücher.de", "https://bücher.de")
        navigates("xn--bcher-kva.de", "https://xn--bcher-kva.de")
    }

    @Test
    fun publicHostsWithPortsOrAddressesKeepHttpsAndSearch() {
        // Next to the private ranges, and names that only look local: still public.
        val public = listOf(
            "example.org:8443", "8.8.8.8", "11.0.0.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "169.255.0.1",
            "[2001:db8::1]:8443/x", "[fe00::1]", "[fec0::1]", "keep.lan.example.org", "local.example.org",
        )
        for (input in public) {
            val interpretation = AddressPolicy.interpret(input)
            assertEquals(input, AddressDecision.Navigate("https://$input"), interpretation.primary)
            assertTrue(input, interpretation.alternative is AddressDecision.Search)
        }
    }

    @Test
    fun aTypedSchemeIsKeptOnHomeAddresses() {
        assertEquals(AddressDecision.Navigate("https://192.168.1.10"), primary("https://192.168.1.10"))
        assertEquals(AddressDecision.Navigate("http://localhost:3000"), primary("http://localhost:3000"))
        assertEquals(AddressDecision.Navigate("https://keep.lan:3000"), primary("https://keep.lan:3000"))
    }

    @Test
    fun mappedAndZonedIpv6HomeAddressesGoOverHttp() {
        assertEquals(AddressDecision.Navigate("http://[::ffff:192.168.1.1]"), primary("[::ffff:192.168.1.1]"))
        assertEquals("https://[::ffff:8.8.8.8]", (primary("[::ffff:8.8.8.8]") as AddressDecision.Navigate).url)
        // A zone names the interface of a link-local address; the address alone decides.
        assertEquals(AddressDecision.Navigate("http://[fe80::1%25wlan0]"), primary("[fe80::1%25wlan0]"))
        // Malformed dotted tails are no home address (and do not throw).
        for (malformed in listOf("[::1.2]", "[::1.2.3]", "[::1.2.3.4.5]")) {
            assertNotEquals(AddressDecision.Navigate("http://$malformed"), primary(malformed))
        }
    }

    @Test
    fun privateAddressesAndLocalhostGoOverHttpWithHttpsInstead() {
        val local = listOf(
            "192.168.1.10", "10.0.0.2:8080", "172.16.0.1", "172.31.255.255/x", "127.0.0.1:3000", "169.254.1.1",
            "localhost", "LOCALHOST:8080", "[::1]:8443/x", "[0:0:0:0:0:0:0:1]", "[fd12:3456::1]", "[fe80::1]:80",
        )
        for (input in local) {
            val interpretation = AddressPolicy.interpret(input)
            assertEquals(input, AddressDecision.Navigate("http://$input"), interpretation.primary)
            assertEquals(input, AddressDecision.Navigate("https://$input"), interpretation.alternative)
        }
    }

    @Test
    fun homeNetworkNamesOfferHttpInsteadOfSearch() {
        val names = listOf(
            "keep.lan:3000", "Keep.LAN", "nas.local/x", "printer.home.arpa", "hister.internal:8080", "box.localdomain", "nas:5000",
        )
        for (input in names) {
            val interpretation = AddressPolicy.interpret(input)
            assertEquals(input, AddressDecision.Navigate("https://$input"), interpretation.primary)
            assertEquals(input, AddressDecision.Navigate("http://$input"), interpretation.alternative)
        }
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
    fun schemelessCredentialsAreRejectedNotSearched() {
        // A search would put the password in Kagi's URL and search history.
        for (input in listOf("admin:hunter2@192.168.1.1", "admin:hunter2@192.168.1.1:8080", "u:p@example.com/x", "admin:p@ss@nas", "admin:pw@nas!", "u:p@1.2.3.4.5")) {
            val interpretation = AddressPolicy.interpret(input)
            assertEquals(input, AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS), interpretation.primary)
            assertNull(input, interpretation.alternative)
        }

        // Without a colon before "@" it is no password; "define:x@example.com" is the accepted cost.
        searches("alice@example.com")
        assertEquals(AddressDecision.Rejected(RejectReason.EMBEDDED_CREDENTIALS), primary("define:x@example.com"))
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
