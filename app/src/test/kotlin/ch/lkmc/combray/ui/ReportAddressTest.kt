package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.integration.SiteReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReportAddressTest {
    @Test
    fun trimmingKeepsSchemeHostPortAndPath() {
        assertEquals("https://example.org:8443/a/b", ReportAddress.siteAndPath("https://example.org:8443/a/b?x=1&y=2#part"))
        assertEquals("https://example.org/a", ReportAddress.siteAndPath("https://example.org/a?x=1"))
        assertEquals("https://example.org/a", ReportAddress.siteAndPath("https://example.org/a#part?not=query"))
        assertEquals("http://[2001:db8::1]:8080/p", ReportAddress.siteAndPath("http://[2001:db8::1]:8080/p?q"))
        assertEquals("file:///sdcard/page.html", ReportAddress.siteAndPath("file:///sdcard/page.html#top"))
    }

    /** Outside the query and fragment, a code can sit in the user information or a path parameter. */
    @Test
    fun trimmingRemovesUserInformationAndPathParameters() {
        assertEquals("https://example.org/a/cart", ReportAddress.siteAndPath("https://example.org/a;v=1/cart;jsessionid=8F3A?item=2"))
        assertEquals("https://example.org/", ReportAddress.siteAndPath("https://user:pass@example.org/"))
        assertEquals("https://example.org:8443", ReportAddress.siteAndPath("https://user@example.org:8443"))
    }

    @Test
    fun anAddressWithoutQueryOrFragmentIsUnchanged() {
        assertEquals("https://example.org/a/b", ReportAddress.siteAndPath("https://example.org/a/b"))
        // A percent-encoded ?, # or ; belongs to the path.
        assertEquals("https://example.org/a%3Fb%23c%3Bd", ReportAddress.siteAndPath("https://example.org/a%3Fb%23c%3Bd"))
    }

    @Test
    fun unparseableTextIsCutByString() {
        assertEquals("not a url ", ReportAddress.siteAndPath("not a url ?x"))
        assertEquals("", ReportAddress.siteAndPath("#only"))
        // Without "://" there is no authority or path to read: only the query and fragment go.
        assertEquals("data:text/html;charset=utf-8,a@b", ReportAddress.siteAndPath("data:text/html;charset=utf-8,a@b#c"))
    }

    /** Proposed default for open question 1. */
    @Test
    fun aSensitiveAddressStartsTrimmedAndAnyOtherInFull() {
        assertEquals("https://example.org/callback", ReportAddress.starting("https://example.org/callback#access_token=abc&state=1"))
        assertEquals("https://example.org/login", ReportAddress.starting("https://example.org/login?session=abc"))
        assertEquals("https://example.org/article?page=2#notes", ReportAddress.starting("https://example.org/article?page=2#notes"))
    }

    /** What made the address look sensitive is gone from the address it starts with. */
    @Test
    fun aSensitiveAddressStartsWithoutItsCode() {
        for (url in listOf("https://shop.example/cart;jsessionid=8F3A?item=2", "https://shop.example/cart;jsessionid=8F3A", "https://user:pass@shop.example/cart")) {
            val starting = ReportAddress.starting(url)
            assertEquals(url, "https://shop.example/cart", starting)
            assertFalse(url, SensitiveUrls.looksSensitive(starting))
        }
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
