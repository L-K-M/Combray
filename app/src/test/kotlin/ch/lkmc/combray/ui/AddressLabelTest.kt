package ch.lkmc.combray.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressLabelTest {
    private fun split(text: String, url: String) = splitAtHost(text, url)?.let { listOf(it.lead, it.host, it.tail) }

    @Test
    fun aLongHostIsItsOwnPartBetweenTheSchemeAndThePath() {
        // Shortened in the middle, this read "Go to https://accounts.google.com.e…/accounts.google.com/login".
        val url = "https://accounts.google.com.signin-session-verify.evil.example/accounts.google.com/login"
        assertEquals(
            listOf("Go to https://", "accounts.google.com.signin-session-verify.evil.example", "/accounts.google.com/login"),
            split("Go to $url", url),
        )
    }

    @Test
    fun theHostKeepsItsPortAndTheRestFollowsIt() {
        assertEquals(listOf("Instead: Go to http://", "192.168.1.10:8080", ""), split("Instead: Go to http://192.168.1.10:8080", "http://192.168.1.10:8080"))
        assertEquals(listOf("Go to http://", "[::1]:8080", "/a"), split("Go to http://[::1]:8080/a", "http://[::1]:8080/a"))
        assertEquals(listOf("Go to https://", "a.example", "?x=1#y"), split("Go to https://a.example?x=1#y", "https://a.example?x=1#y"))
        // Words after the address in a translation stay after it.
        assertEquals(listOf("« https://", "a.example", "/b » öffnen"), split("« https://a.example/b » öffnen", "https://a.example/b"))
    }

    @Test
    fun aBackslashEndsTheHostAsItDoesForTheEngine() {
        assertEquals(
            listOf("Go to https://", "good.example", "\\.evil.example/"),
            split("Go to https://good.example\\.evil.example/", "https://good.example\\.evil.example/"),
        )
    }

    @Test
    fun anAddressWithoutAnAuthorityIsNotSplit() {
        assertNull(split("Open in another app: mailto:a@b.example", "mailto:a@b.example"))
        // A "://" further in is not an authority.
        val mail = "mailto:a@b.example?body=https://evil.example/"
        assertNull(split("Open in another app: $mail", mail))
        assertNull(split("Go to https://", "https://"))
        assertNull(split("Go somewhere else", "https://a.example/"))
    }
}
