package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServiceUrlsTest {
    private fun root(input: String, cleartext: Set<String> = emptySet()) =
        ServiceUrls.normalizeRoot(input, Integrations.KARAKEEP_SUFFIXES, cleartext)

    @Test
    fun keepsPathPrefixesAndDropsPastedApiSuffixes() {
        assertEquals(ServiceRoot.Valid("https://keep.example/sub"), root("https://keep.example/sub/"))
        assertEquals(ServiceRoot.Valid("https://keep.example"), root(" https://Keep.Example/api/v1/ "))
        assertEquals(ServiceRoot.Valid("https://keep.example:8443/k"), root("https://keep.example:8443/k/api"))
        assertEquals("https://keep.example/sub/api/v1/bookmarks", ServiceUrls.endpoint("https://keep.example/sub", "/api/v1/bookmarks"))
    }

    @Test
    fun plainHttpOnlyForAllowedHosts() {
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.CLEARTEXT_NOT_ALLOWED), root("http://keep.example"))
        assertEquals(ServiceRoot.Valid("http://10.0.2.2:8766/keep"), root("http://10.0.2.2:8766/keep", setOf("10.0.2.2")))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.UNSUPPORTED_SCHEME), root("ftp://keep.example"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY), root("https://u:p@keep.example"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("keep example"))
    }

    @Test
    fun fingerprintIdentifiesTheDestination() {
        assertEquals(ServiceUrls.fingerprint("https://a.example"), ServiceUrls.fingerprint("https://a.example"))
        assertNotEquals(ServiceUrls.fingerprint("https://a.example"), ServiceUrls.fingerprint("https://b.example"))
    }

    @Test
    fun sensitiveAddressesAreRecognizedNarrowly() {
        assertEquals(true, SensitiveUrls.looksSensitive("https://kagi.com/search?token=abc&q=x"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://u:p@example.org/"))
        assertEquals(false, SensitiveUrls.looksSensitive("https://example.org/article?id=3&ref=home"))
    }
}
