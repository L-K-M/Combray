package ch.lkmc.combray.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun aUnicodeHostIsStoredUnderItsPunycodeName() {
        // java.net.URI has no host for Unicode (IDN) authorities. Like typed addresses, a
        // service on one is accepted and stored under its ASCII name.
        assertEquals(ServiceRoot.Valid("https://xn--bcher-kva.example"), root("https://bücher.example"))
        assertEquals(ServiceRoot.Valid("https://xn--bcher-kva.example:8443/k"), root("https://bücher.example:8443/k/api"))
        // Credentials and query parts are refused before any host work.
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY), root("https://u:p@bücher.example"))
    }

    @Test
    fun deviationCharactersKeepTheirNontransitionalDestination() {
        assertEquals(ServiceRoot.Valid("https://xn--strae-oqa.example"), root("https://straße.example"))
        assertEquals(ServiceRoot.Valid("https://xn--3xa.example"), root("https://ς.example"))
        assertEquals(ServiceRoot.Valid("https://xn--strae-oqa.example"), root("https://xn--strae-oqa.example"))
    }

    @Test
    fun invalidJoinersAndUnassignedCharactersAreNotSilentlyRemapped() {
        for (host in listOf("a\u200Db.example", "a\u0378b.example")) {
            assertEquals(host, ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://$host"))
        }
    }

    @Test
    fun widthMappingStillKeepsTheIntendedAsciiDestination() {
        assertEquals(ServiceRoot.Valid("https://buecher.example"), root("https://ｂｕｅｃｈｅｒ.example"))
    }

    @Test
    fun bracketedAuthoritiesRequireAValidIpv6Literal() {
        for (host in listOf("[:::1]", "[not-a-v6]", "[]")) {
            assertNull(host, ServiceUrls.parseAuthority(host))
            assertEquals(host, ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://$host/"))
        }
        assertEquals(ServiceRoot.Valid("https://[fe80::1%25eth0]"), root("https://[fe80::1%25eth0]/"))
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
    fun idnHostsArePunycodedAndTheirCredentialsStillRefused() {
        assertEquals(ServiceRoot.Valid("https://xn--bcher-kva.example"), root("https://bücher.example/"))
        assertEquals(ServiceRoot.Valid("https://xn--bcher-kva.example:8443/k"), root("https://bücher.example:8443/k/api"))
        assertEquals(
            ServiceRoot.Invalid(ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY),
            root("https://u:p@bücher.example/"),
        )
    }

    @Test
    fun defaultPortsAndTrailingDotsDoNotChangeTheDestination() {
        assertEquals(ServiceRoot.Valid("https://keep.example"), root("https://keep.example:443/"))
        assertEquals(ServiceRoot.Valid("http://keep.example"), root("http://keep.example:80/"))
        assertEquals(ServiceRoot.Valid("http://keep.example:8080"), root("http://keep.example:8080"))
        assertEquals(ServiceRoot.Valid("https://keep.example"), root("https://keep.example./"))
        assertEquals(ServiceRoot.Valid("https://192.168.1.1:8443"), root("https://192.168.1.1.:8443/"))
        assertEquals(ServiceRoot.Valid("https://[::1]"), root("https://[::1]:443/"))
        val plain = (root("https://keep.example") as ServiceRoot.Valid).root
        for (variant in listOf("https://keep.example:443", "https://keep.example.", "https://Keep.Example")) {
            assertEquals(ServiceUrls.fingerprint(plain), ServiceUrls.fingerprint((root(variant) as ServiceRoot.Valid).root))
        }
    }

    @Test
    fun explicitServicePortsMustBeUsableTcpPorts() {
        for (host in listOf("keep.example", "[::1]")) {
            for (port in listOf("0", "65536", "99999", "-1", "443x", "1:2", "")) {
                val invalid = "https://$host:$port/sub/api/v1"
                assertEquals(invalid, ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root(invalid))
                assertEquals(
                    invalid,
                    ServiceAddresses.Invalid(AddressField.ALTERNATE, ServiceRootProblem.NOT_A_URL),
                    addresses("https://keep.example", invalid),
                )
            }
            for (port in listOf(1, 8443, 65535)) {
                assertEquals(ServiceRoot.Valid("https://$host:$port/sub"), root("https://$host:$port/sub/api/v1"))
            }
        }
        assertEquals(ServiceRoot.Valid("https://keep.example/sub"), root("https://keep.example/sub"))
    }

    @Test
    fun zeroIsNotAUsableServicePort() {
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://keep.example:0/"))
        assertEquals(
            ServiceAddresses.Invalid(AddressField.ALTERNATE, ServiceRootProblem.NOT_A_URL),
            addresses("https://keep.example", "https://keep.example:0/"),
        )
    }

    @Test
    fun malformedAuthoritiesAreRefused() {
        // IPv6: junk after "]" and a missing bracket are not silently dropped.
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://[::1]evil:8080/"))
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://[::1"))
        // A percent-encoded reg-name is not a host the app can resolve.
        assertEquals(ServiceRoot.Invalid(ServiceRootProblem.NOT_A_URL), root("https://%62%C3%BCcher.example/"))
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
