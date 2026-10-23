package ch.lkmc.combray.core.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveUrlsTest {
    @Test
    fun credentialParametersAreRecognizedWhateverTheirSpelling() {
        listOf(
            "https://api.example/feed?auth_token=a",
            "https://app.example/#accessToken=a",
            "https://bucket.s3.example/k?X-Amz-Signature=a&X-Amz-Credential=b",
            "https://bucket.example/k?AWSAccessKeyId=a&Expires=1",
            "https://cdn.example/v.mp4?sig=a",
            "https://cdn.example/v.mp4?Signature=a",
            "https://twitter.example/cb?oauth_token=a&oauth_verifier=b",
            "https://app.example/cb?client_secret=a",
            "https://maps.example/js?key=a",
            "https://sso.example/login?ticket=ST-1",
            "https://shop.example/cart;x?PHPSESSID=a",
            "https://shop.example/cart?jsessionid=a",
            // Session IDs rewritten into the path as a matrix parameter (Java servlets).
            "https://shop.example/cart;jsessionid=abc123",
            "https://shop.example/app;jsessionid=abc/item?id=2",
            "https://app.example/api?x-api-key=a",
            "https://app.example/api?apiKey=a",
            "https://app.example/login?pass=a",
            "https://app.example/login?jwt=a",
            "https://app.example/cb?auth_code=a",
            // A hash-routed app's callback: the parameters follow the route.
            "https://app.example/#/callback?access_token=a",
            // A later "?" in a value does not hide the credential before it.
            "https://app.example/#access_token=abc&state=/inbox?tab=2",
            "https://app.example/#/verify?token=abc&next=/inbox?folder=2",
        ).forEach { assertTrue(it, SensitiveUrls.looksSensitive(it)) }
    }

    @Test
    fun userInfoIsACredentialWhateverTheHostLooksLike() {
        listOf(
            "https://user:pass@host.example/",
            "http://admin@router.lan/",
            // Hosts java.net.URI does not accept as server names (an underscore, a label ending
            // in "-") leave the authority registry-based, with no user info or host, although
            // WHATWG URL parsing and Servo read user:pass@ from them.
            "https://user:pass@my_host.example.com/",
            "https://user:pass@a-.example.com/",
            "http://admin:pw@media_box.lan/",
            "https://:pass@host_x.example/",
        ).forEach { assertTrue(it, SensitiveUrls.looksSensitive(it)) }
    }

    @Test
    fun anAuthorizationCodeCountsWhenItComesWithItsState() {
        assertTrue(SensitiveUrls.looksSensitive("https://app.example/callback?code=a&state=b"))
        assertTrue(SensitiveUrls.looksSensitive("https://app.example/callback#state=b&code=a"))
        assertFalse(SensitiveUrls.looksSensitive("https://shop.example/promo?code=SUMMER"))
        assertFalse(SensitiveUrls.looksSensitive("https://atlas.example/country?code=CH"))
    }

    @Test
    fun ordinaryParametersAreNotCredentials() {
        listOf(
            "https://example.org/article?id=3&ref=home",
            "https://video.example/list?pageToken=a",
            "https://api.example/items?page_token=a&next_page_token=b",
            "https://console.example/list?NextToken=a",
            "https://graph.example/v1.0/users?%24skiptoken=a",
            "https://graph.example/v1.0/users?\$skipToken=a",
            "https://example.org/a;v=2/b",
            "https://books.example/search?author=proust",
            "https://example.org/search?q=token&keyword=key",
            "https://example.org/design?signup=1",
            "https://example.org/oauth-explained",
            // Anchors and routes in a fragment are not parameters.
            "https://docs.example/guide#token-usage",
            "https://example.org/page#section-2",
            "https://app.example/#/settings/api-keys?tab=1",
        ).forEach { assertFalse(it, SensitiveUrls.looksSensitive(it)) }
    }
}
