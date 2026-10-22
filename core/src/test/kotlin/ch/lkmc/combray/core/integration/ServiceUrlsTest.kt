package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServiceUrlsTest {
    private fun root(input: String) = ServiceUrls.normalizeRoot(input, Integrations.KARAKEEP_SUFFIXES)

    private fun addresses(root: String, alternate: String?) = ServiceUrls.normalizeAddresses(root, alternate, Integrations.KARAKEEP_SUFFIXES)

    @Test
    fun keepsPathPrefixesAndDropsPastedApiSuffixes() {
        assertEquals(ServiceRoot.Valid("https://keep.example/sub"), root("https://keep.example/sub/"))
        assertEquals(ServiceRoot.Valid("https://keep.example"), root(" https://Keep.Example/api/v1/ "))
        assertEquals(ServiceRoot.Valid("https://keep.example:8443/k"), root("https://keep.example:8443/k/api"))
        assertEquals("https://keep.example/sub/api/v1/bookmarks", ServiceUrls.endpoint("https://keep.example/sub", "/api/v1/bookmarks"))
    }

    @Test
    fun plainHttpIsAllowedForAnyHostAndOtherSchemesAreNot() {
        assertEquals(ServiceRoot.Valid("http://keep.example"), root("http://keep.example"))
        assertEquals(ServiceRoot.Valid("http://192.168.1.20:3000"), root("HTTP://192.168.1.20:3000/api/v1"))
        assertEquals(true, ServiceUrls.isCleartext("http://keep.example"))
        assertEquals(false, ServiceUrls.isCleartext("https://keep.example"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.UNSUPPORTED_SCHEME), root("ftp://keep.example"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY), root("https://u:p@keep.example"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("keep example"))
    }

    @Test
    fun anAlternateIsCheckedLikeTheMainAddressAndIsNoneWhenBlankOrTheSame() {
        assertEquals(
            ServiceAddresses.Valid("http://keep.lan", "https://keep.tailnet.ts.net"),
            addresses("http://keep.lan/", "https://keep.tailnet.ts.net/api/v1"),
        )
        assertEquals(ServiceAddresses.Valid("http://keep.lan", null), addresses("http://keep.lan", "  "))
        assertEquals(ServiceAddresses.Valid("http://keep.lan", null), addresses("http://keep.lan", "http://Keep.lan/"))
        assertEquals(ServiceAddresses.Invalid(AddressField.MAIN, ServiceRootProblem.NOT_A_URL), addresses("keep lan", "https://k.example"))
        assertEquals(ServiceAddresses.Invalid(AddressField.ALTERNATE, ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY), addresses("http://keep.lan", "https://k.example/?a=1"))
        // The identity is the main address: an alternate does not change it.
        assertEquals(ServiceUrls.fingerprint("http://keep.lan"), addresses("http://keep.lan", "https://k.example").let { (it as ServiceAddresses.Valid).fingerprint })
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

    @Test
    fun encodedCredentialNamesAreStillSensitive() {
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?%74oken=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?access%5Ftoken=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?%41uTh=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?%73essionid=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?ToKeN=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/#%74oken=abc"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/#access%5Ftoken=abc"))
    }

    @Test
    fun ordinaryAndDoubleEncodedParametersAreNotSensitive() {
        assertEquals(false, SensitiveUrls.looksSensitive("https://a.example/article?id=3&%71=feed"))
        assertEquals(false, SensitiveUrls.looksSensitive("https://a.example/article?id=%33"))
        // An encoded credential string inside a VALUE is content, not a credential name.
        assertEquals(false, SensitiveUrls.looksSensitive("https://a.example/?next=%2Flogin%3Ftoken%3Dx"))
        // One decode only: %2574oken decodes to the literal name "%74oken", not "token".
        assertEquals(false, SensitiveUrls.looksSensitive("https://a.example/?%2574oken=abc"))
    }

    @Test
    fun malformedEscapesAreSensitive() {
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?to%zz=x"))
        assertEquals(true, SensitiveUrls.looksSensitive("https://a.example/?to%"))
    }
}
