package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SiteReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportAddressTest {
    @Test
    fun trimmingKeepsSchemeHostPortAndPath() {
        assertEquals("https://example.org:8443/a/b;c", ReportAddress.trimQueryAndFragment("https://example.org:8443/a/b;c?x=1&y=2#part"))
        assertEquals("https://example.org/a", ReportAddress.trimQueryAndFragment("https://example.org/a?x=1"))
        assertEquals("https://example.org/a", ReportAddress.trimQueryAndFragment("https://example.org/a#part?not=query"))
        assertEquals("http://[2001:db8::1]:8080/p", ReportAddress.trimQueryAndFragment("http://[2001:db8::1]:8080/p?q"))
    }

    @Test
    fun anAddressWithoutQueryOrFragmentIsUnchanged() {
        assertEquals("https://example.org/a/b", ReportAddress.trimQueryAndFragment("https://example.org/a/b"))
        // A percent-encoded ? or # belongs to the path.
        assertEquals("https://example.org/a%3Fb%23c", ReportAddress.trimQueryAndFragment("https://example.org/a%3Fb%23c"))
    }

    @Test
    fun unparseableTextIsCutByString() {
        assertEquals("not a url ", ReportAddress.trimQueryAndFragment("not a url ?x"))
        assertEquals("", ReportAddress.trimQueryAndFragment("#only"))
    }

    /** Proposed default for open question 1. */
    @Test
    fun aSensitiveAddressStartsTrimmedAndAnyOtherInFull() {
        assertEquals("https://example.org/callback", ReportAddress.starting("https://example.org/callback#access_token=abc&state=1"))
        assertEquals("https://example.org/login", ReportAddress.starting("https://example.org/login?session=abc"))
        assertEquals("https://example.org/article?page=2#notes", ReportAddress.starting("https://example.org/article?page=2#notes"))
    }

    @Test
    fun onlyAnAddressTheServiceAcceptsCanBeSent() {
        assertNull(ReportAddress.problem("https://example.org/ünïcode?q=1"))
        assertEquals(ReportAddressProblem.BLANK, ReportAddress.problem(""))
        assertEquals(ReportAddressProblem.BLANK, ReportAddress.problem("  "))
        assertEquals(ReportAddressProblem.CONTROL_CHARACTERS, ReportAddress.problem("https://example.org/a\nb"))
        assertEquals(ReportAddressProblem.CONTROL_CHARACTERS, ReportAddress.problem("https://example.org/a\u0085b"))
        val path = "https://example.org/"
        assertNull(ReportAddress.problem(path + "a".repeat(SiteReports.MAX_URL_BYTES - path.length)))
        assertEquals(ReportAddressProblem.TOO_LONG, ReportAddress.problem(path + "a".repeat(SiteReports.MAX_URL_BYTES - path.length + 1)))
        // Counted in UTF-8 bytes, as the service does: "é" takes two.
        assertEquals(ReportAddressProblem.TOO_LONG, ReportAddress.problem(path + "é".repeat(SiteReports.MAX_URL_BYTES / 2)))
    }
}
