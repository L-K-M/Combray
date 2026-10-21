package ch.lkmc.combray.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun pagesWithoutAHostGetAFixedLabelByScheme() {
        assertEquals(HostlessPage.DATA, HostlessPage.of("data:text/html,<!--://accounts.example.com/-->"))
        assertEquals(HostlessPage.DATA, HostlessPage.of("DATA:text/plain,x"))
        assertEquals(HostlessPage.BLANK, HostlessPage.of("about:blank"))
        assertEquals(HostlessPage.BLANK, HostlessPage.of("about:blank#top"))
        assertEquals(HostlessPage.LOCAL_FILE, HostlessPage.of("file:///sdcard/a.html"))
    }

    @Test
    fun hostlessPagesShowOnlyTheirSchemeAndNeverTheirAddress() {
        assertEquals("data:", displayHost("data:text/html,<!--://accounts.example.com/-->"))
        assertEquals("about:blank", displayHost("about:blank#top"))
        assertEquals("file:", displayHost("file:///sdcard/a.html"))
        assertEquals("javascript:", displayHost("javascript:alert(1)"))
        assertEquals("…", displayHost("accounts.example.com:x"))
        assertEquals("example.org", displayHost("https://www.example.org/a"))
    }

    @Test
    fun otherHostlessPagesNeverShowTheirOwnText() {
        // A page picks its scheme and path too: "accounts.example.com:" is a valid scheme.
        assertEquals(HostlessPage.OTHER, HostlessPage.of("accounts.example.com:x"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("about:accounts.example.com"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("blob:null/0f1e"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("https://"))
    }
}
