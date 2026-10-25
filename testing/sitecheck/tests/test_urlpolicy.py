"""Tests for the URL policy: every exclusion reason, normalization, sites and templates."""

from __future__ import annotations

import unittest

from sitecheck import urlpolicy
from sitecheck.urlpolicy import (
    Reason,
    classify,
    normalize,
    normalize_deny_entry,
    site_of,
    template_of,
)

HISTER_HOST = "hister.example.net"
DENY = ("blocked.example.org", "https://news.example.com/private/")


def _reason(url: str) -> Reason:
    return classify(url, hister_host=HISTER_HOST, deny=DENY).reason


class ReasonTest(unittest.TestCase):
    CASES: tuple[tuple[str, Reason], ...] = (
        # Eligible.
        ("https://example.com/", Reason.OK),
        ("http://example.com/articles/hello-world", Reason.OK),
        ("https://www.bbc.co.uk/news", Reason.OK),
        ("https://8.8.8.8/", Reason.OK),
        ("https://[2606:4700:4700::1111]/", Reason.OK),
        ("https://example.com:443/", Reason.OK),
        ("http://example.com:80/", Reason.OK),
        ("https://example.com:/", Reason.OK),
        # Unparseable.
        ("http://[::1/", Reason.INVALID),
        ("http:///path", Reason.INVALID),
        ("https://example.com:99999/", Reason.INVALID),
        ("https://example.com:http/", Reason.INVALID),
        ("https://exa mple.com/", Reason.INVALID),
        ("https://ex%61mple.com/", Reason.INVALID),
        ("https://a..b.com/", Reason.INVALID),
        ("https://./", Reason.INVALID),
        # Control characters, which browsers refuse in a host.
        ("https://a\x00b.example.com/", Reason.INVALID),
        ("https://a\x01b.example.com/", Reason.INVALID),
        ("https://a\x7fb.example.com/", Reason.INVALID),
        # Scheme.
        ("", Reason.SCHEME),
        ("ftp://example.com/file", Reason.SCHEME),
        ("file:///etc/passwd", Reason.SCHEME),
        ("javascript:alert(1)", Reason.SCHEME),
        ("mailto:someone@example.com", Reason.SCHEME),
        ("about:blank", Reason.SCHEME),
        ("example.com/no-scheme", Reason.SCHEME),
        # Credentials.
        ("https://user@example.com/", Reason.USERINFO),
        ("https://user:secret@example.com/", Reason.USERINFO),
        ("https://:@example.com/", Reason.USERINFO),
        # Ports.
        ("http://example.com:8080/", Reason.PORT),
        ("https://example.com:80/", Reason.PORT),
        ("http://example.com:443/", Reason.PORT),
        # IP literals.
        ("http://127.0.0.1/", Reason.PRIVATE_IP),
        ("http://127.0.0.1./", Reason.PRIVATE_IP),
        ("http://10.1.2.3/", Reason.PRIVATE_IP),
        ("http://172.16.0.1/", Reason.PRIVATE_IP),
        ("http://192.168.1.1/", Reason.PRIVATE_IP),
        ("http://169.254.169.254/latest/meta-data", Reason.PRIVATE_IP),
        ("http://100.64.0.1/", Reason.PRIVATE_IP),
        ("http://0.0.0.0/", Reason.PRIVATE_IP),
        ("http://224.0.0.1/", Reason.PRIVATE_IP),
        ("http://240.0.0.1/", Reason.PRIVATE_IP),
        ("http://255.255.255.255/", Reason.PRIVATE_IP),
        ("http://192.0.2.1/", Reason.PRIVATE_IP),
        ("http://[::1]/", Reason.PRIVATE_IP),
        ("http://[::]/", Reason.PRIVATE_IP),
        ("http://[fe80::1]/", Reason.PRIVATE_IP),
        ("http://[fe80::1%25en0]/", Reason.PRIVATE_IP),
        ("http://[fd00::1]/", Reason.PRIVATE_IP),
        ("http://[ff02::1]/", Reason.PRIVATE_IP),
        ("http://[2001:db8::1]/", Reason.PRIVATE_IP),
        ("http://[::ffff:127.0.0.1]/", Reason.PRIVATE_IP),
        ("http://[::ffff:192.168.0.1]/", Reason.PRIVATE_IP),
        ("http://[2002:c0a8:0101::1]/", Reason.PRIVATE_IP),
        # Shorthand IPv4 forms browsers still resolve as addresses.
        ("http://127.1/", Reason.PRIVATE_IP),
        ("http://0x7f.0.0.1/", Reason.PRIVATE_IP),
        ("http://2130706433/", Reason.PRIVATE_IP),
        ("http://017700000001/", Reason.PRIVATE_IP),
        # A numeric last label makes browsers parse the host as IPv4; names they then
        # cannot parse are excluded too (fail closed).
        ("http://example.123/", Reason.PRIVATE_IP),
        # Private names.
        ("http://localhost/", Reason.PRIVATE_HOST),
        ("http://LOCALHOST./", Reason.PRIVATE_HOST),
        ("http://app.localhost/", Reason.PRIVATE_HOST),
        ("http://nas.local/", Reason.PRIVATE_HOST),
        ("http://router.lan/", Reason.PRIVATE_HOST),
        ("http://printer.home/", Reason.PRIVATE_HOST),
        ("http://wiki.internal/", Reason.PRIVATE_HOST),
        ("http://portal.intranet/", Reason.PRIVATE_HOST),
        ("http://mail.corp/", Reason.PRIVATE_HOST),
        ("http://nas.home.arpa/", Reason.PRIVATE_HOST),
        ("http://home.arpa/", Reason.PRIVATE_HOST),
        ("http://site.test/", Reason.PRIVATE_HOST),
        ("http://web-platform.test/", Reason.PRIVATE_HOST),
        ("http://foo.example/", Reason.PRIVATE_HOST),
        ("http://foo.invalid/", Reason.PRIVATE_HOST),
        ("http://box.localdomain/", Reason.PRIVATE_HOST),
        ("http://intranet-server/", Reason.PRIVATE_HOST),
        ("http://router./", Reason.PRIVATE_HOST),
        # The Hister server and its subdomains, but not its parent domain.
        ("https://hister.example.net/search?q=x", Reason.HISTER_HOST),
        ("https://HISTER.example.net./", Reason.HISTER_HOST),
        ("https://api.hister.example.net/", Reason.HISTER_HOST),
        ("https://example.net/", Reason.OK),
        # Deny list: host suffixes and URL prefixes.
        ("https://blocked.example.org/", Reason.DENIED),
        ("https://www.blocked.example.org/page", Reason.DENIED),
        ("https://notblocked.example.org/", Reason.OK),
        ("https://news.example.com/private/report", Reason.DENIED),
        ("HTTPS://NEWS.example.com:443/private/", Reason.DENIED),
        ("https://news.example.com/public/report", Reason.OK),
        # Paths are compared ignoring case and percent-encoding: a server may treat
        # them as the same page, and when in doubt a URL is skipped.
        ("https://news.example.com/Private/report", Reason.DENIED),
        ("https://news.example.com/%70rivate/report", Reason.DENIED),
        # Actions and secrets in path segments.
        ("https://example.com/logout", Reason.SENSITIVE),
        ("https://example.com/Log-Out", Reason.SENSITIVE),
        ("https://example.com/log_out", Reason.SENSITIVE),
        ("https://example.com/user/signOut", Reason.SENSITIVE),
        ("https://example.com/unsubscribe/abc", Reason.SENSITIVE),
        ("https://example.com/email/confirm/xyz", Reason.SENSITIVE),
        ("https://example.com/verify-email", Reason.SENSITIVE),
        ("https://example.com/password/reset", Reason.SENSITIVE),
        ("https://example.com/resetPassword", Reason.SENSITIVE),
        ("https://example.com/forgotpassword", Reason.SENSITIVE),
        ("https://example.com/myaccount", Reason.SENSITIVE),
        ("https://example.com/accounts/profile", Reason.SENSITIVE),
        ("https://example.com/oauth2callback", Reason.SENSITIVE),
        ("https://example.com/auth/magic", Reason.SENSITIVE),
        ("https://example.com/sso", Reason.SENSITIVE),
        ("https://example.com/saml/acs", Reason.SENSITIVE),
        ("https://example.com/oauth/authorize", Reason.SENSITIVE),
        ("https://example.com/settings/2fa", Reason.SENSITIVE),
        ("https://example.com/enable2FA", Reason.SENSITIVE),
        ("https://example.com/cart", Reason.SENSITIVE),
        ("https://example.com/checkout/step1", Reason.SENSITIVE),
        ("https://example.com/orders/123", Reason.SENSITIVE),
        ("https://example.com/wp-admin/", Reason.SENSITIVE),
        ("https://example.com/invite/team", Reason.SENSITIVE),
        ("https://example.com/items/42/delete", Reason.SENSITIVE),
        ("https://example.com/p;jsessionid=0123ABC", Reason.SENSITIVE),
        ("https://example.com/files/download", Reason.SENSITIVE),
        ("https://example.com/share/abc", Reason.SENSITIVE),
        ("https://example.com/log%2Dout", Reason.SENSITIVE),
        # Accepted false positive: an article whose title uses an action word.
        ("https://blog.example.com/how-to-reset-your-mind", Reason.SENSITIVE),
        # Short keywords inside longer words do not count.
        ("https://example.com/author/jane", Reason.OK),
        ("https://example.com/lessons/one", Reason.OK),
        ("https://example.com/cartoons", Reason.OK),
        ("https://example.com/display", Reason.OK),
        ("https://example.com/hotpot-recipes", Reason.OK),
        # The host is not judged by keywords.
        ("https://payments-news.example.org/", Reason.OK),
        ("https://login.example.org/", Reason.OK),
        # Query keys and values.
        ("https://example.com/page?token=abc", Reason.SENSITIVE),
        ("https://example.com/page?access_token=abc", Reason.SENSITIVE),
        ("https://example.com/page?Code=abc", Reason.SENSITIVE),
        ("https://example.com/page?state=x", Reason.SENSITIVE),
        ("https://example.com/page?e=a%40b.c", Reason.SENSITIVE),
        ("https://example.com/page?u=1&id=2", Reason.SENSITIVE),
        ("https://example.com/page?p=123", Reason.SENSITIVE),
        ("https://example.com/page?api_key=1", Reason.SENSITIVE),
        ("https://example.com/page?sessionId=1", Reason.SENSITIVE),
        ("https://example.com/page?redirect_callback=x", Reason.SENSITIVE),
        ("https://example.com/page?order=desc", Reason.SENSITIVE),
        ("https://example.com/page?action=logout", Reason.SENSITIVE),
        ("https://example.com/page?do=Delete", Reason.SENSITIVE),
        ("https://example.com/page?id=a1b2c3d4e5f6a7b8c9d0e1f2a3", Reason.SENSITIVE),
        ("https://example.com/page?x=QWxhZGRpbjpvcGVuIHNlc2FtZQ%3D%3D9", Reason.SENSITIVE),
        ("https://example.com/page?id=550e8400-e29b-41d4-a716-446655440000", Reason.SENSITIVE),
        # Long values that are words, or short ids, are not secrets.
        ("https://example.com/page?title=a-long-title-without-any-digits", Reason.OK),
        ("https://example.com/page?id=12345", Reason.OK),
        ("https://example.com/page?id=0123456789012345678901234567", Reason.OK),
        ("https://example.com/page?lang=en&page=2", Reason.OK),
        # Tracking parameters are dropped before judging, so their values never count.
        (
            "https://example.com/page?utm_medium=share&utm_content=a1b2c3d4e5f6a7b8c9d0e1f2a3",
            Reason.OK,
        ),
        ("https://example.com/page?fbclid=IwAR0a1b2c3d4e5f6a7b8c9d0e1f2a3b4", Reason.OK),
        # Files.
        ("https://example.com/report.pdf", Reason.FILE),
        ("https://example.com/Report.PDF", Reason.FILE),
        ("https://example.com/archive.tar.gz", Reason.FILE),
        ("https://example.com/feed.xml", Reason.FILE),
        ("https://example.com/image.jpeg?w=200", Reason.FILE),
        ("https://example.com/a%2Epdf", Reason.FILE),
        ("https://example.com/report.pdf/", Reason.OK),
        ("https://example.com/page.html", Reason.OK),
        ("https://example.com/pdf", Reason.OK),
        # Search result pages.
        ("https://www.google.com/search?q=servo", Reason.SEARCH),
        ("https://kagi.com/search?q=servo", Reason.SEARCH),
        ("https://www.bing.com/search?q=servo&form=QBLH", Reason.SEARCH),
        ("https://www.youtube.com/results?search_query=servo", Reason.SEARCH),
        ("https://example.com/en/search/?query=servo", Reason.SEARCH),
        ("https://example.com/Search?Q=servo", Reason.SEARCH),
        # Home-page search boxes submit to the root path.
        ("https://duckduckgo.com/?q=servo", Reason.SEARCH),
        ("https://www.qwant.com/?q=servo", Reason.SEARCH),
        ("https://example.com/?page=2", Reason.OK),
        ("https://example.com/search", Reason.OK),
        ("https://example.com/search?page=2", Reason.OK),
        ("https://example.com/research?q=servo", Reason.OK),
    )

    def test_reasons(self) -> None:
        for url, expected in self.CASES:
            with self.subTest(url=url):
                self.assertEqual(_reason(url), expected)

    def test_every_reason_is_covered(self) -> None:
        covered = {expected for _, expected in self.CASES}
        self.assertEqual(covered, set(Reason))

    def test_checks_run_in_documented_order(self) -> None:
        cases = (
            ("ftp://user@localhost:21/logout", Reason.SCHEME),
            ("https://user@localhost:8443/logout", Reason.USERINFO),
            ("https://localhost:8443/logout", Reason.PORT),
            ("https://127.0.0.1/logout", Reason.PRIVATE_IP),
            ("https://hister.local/logout", Reason.PRIVATE_HOST),
            ("https://hister.example.net/logout", Reason.HISTER_HOST),
            ("https://blocked.example.org/logout.pdf", Reason.DENIED),
            ("https://example.com/logout.pdf", Reason.SENSITIVE),
            ("https://example.com/search.pdf?q=x", Reason.FILE),
        )
        for url, expected in cases:
            with self.subTest(url=url):
                self.assertEqual(_reason(url), expected)

    def test_without_hister_host_or_deny_list(self) -> None:
        self.assertTrue(classify("https://hister.example.net/").eligible)
        self.assertTrue(classify("https://blocked.example.org/").eligible)

    def test_invalid_deny_entry_raises(self) -> None:
        with self.assertRaises(ValueError):
            classify("https://example.com/", deny=("ftp://example.com/",))

    def test_rejected_decision_keeps_host_and_site(self) -> None:
        # The leak check needs the hosts of excluded pages as well.
        decision = classify("https://www.Example.co.uk/logout")
        self.assertFalse(decision.eligible)
        self.assertIsNone(decision.normalized)
        self.assertEqual(decision.host, "www.example.co.uk")
        self.assertEqual(decision.site, "example.co.uk")
        self.assertEqual(decision.template, "")

    def test_unparseable_decision_has_no_host(self) -> None:
        decision = classify("http://[::1/")
        self.assertEqual((decision.host, decision.site), ("", ""))

    def test_reason_formats_as_its_code(self) -> None:
        self.assertEqual(f"{Reason.PRIVATE_IP}", "private-ip")


class CapabilityLinkTest(unittest.TestCase):
    """Capability links carry their secret in the path, an odd query form or a word."""

    SENSITIVE_URLS = (
        # Opaque tokens in path segments: JWTs, long hex, long mixed runs.
        "https://example.org/l/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.c2lnbmF0dXJl",
        "https://example.org/m/9f8e7d6c5b4a39281706f5e4d3c2b1a0",
        "https://example.org/u/AbCdEf123456GhIjKl789012MnOp",
        "https://example.com/c/AbCdEf123456789012345678",
        "https://email.mg.example.com/c/eJwlzk1uwyAQBeC7sDYS2GD7rPIBGOXpyvQhQ",
        "https://example.org/notes/Shared-Page-0123456789abcdef0123456789abcdef",
        "https://example.org/t/Ab-12Cd34-Ef56_Gh78Ij",
        # One-click action words.
        "https://example.org/unsub/AbCdEf123456",
        "https://example.org/rsvp/yes/0a1b2c3d",
        "https://www.meetup.example-site.org/x/events/123/rsvp/?response=yes",
        "https://example.org/event/9/accept?id=5",
        "https://example.org/event/9/decline",
        "https://example.org/claim/ABC123",
        "https://example.org/redeem/ABCD-EFGH-1234",
        "https://example.org/poll/12/vote?choice=2",
        "https://example.org/newsletter/subscribe?list=3",
        # Secret-bearing query keys and values.
        "https://example.org/a?pin=1234",
        "https://example.org/a?passcode=1",
        "https://example.org/a?secret=hunter2",
        "https://example.org/a?tok=1",
        "https://example.org/a?ott=1",
        "https://example.org/a?otc=12345678",
        "https://example.org/a?e-mail=x",
        "https://example.org/a?to=bob@mail.com",
        "https://example.org/a?id=user%40mail.com",
        "https://example.org/a?a=1;sig=0123456789abcdef0123456789",
        "https://example.org/a?x=Ab12Cd34Ef56Gh78Ij90Kl",
        "https://example.org/a?x=Ab12Cd34~Ef56Gh78Ij90Kl",
        "https://example.org/a?SAMLResponse=x",
        "https://example.org/a?SAMLRequest=x",
    )
    ELIGIBLE_URLS = (
        # Word slugs with numbers are articles, not tokens.
        "https://example.com/how-to-install-python-3-12-on-macos",
        "https://example.com/2026/10/24/iphone-16-pro-max-review-2026",
        "https://www.bbc.co.uk/news/world-europe-67123456",
        "https://example.com/articles/c4ngd2y5x0xo",
        "https://example.com/watch?v=dQw4w9WgXcQ",
        # Profile pages and pins are not capability links.
        "https://social.example-site.org/@someone",
        "https://www.pinterest.example-site.org/pin/1234567/",
        "https://example.com/a?q=@someone",
    )

    def test_capability_links_are_sensitive(self) -> None:
        for url in self.SENSITIVE_URLS:
            with self.subTest(url=url):
                self.assertEqual(classify(url).reason, Reason.SENSITIVE)

    def test_ordinary_pages_stay_eligible(self) -> None:
        for url in self.ELIGIBLE_URLS:
            with self.subTest(url=url):
                self.assertEqual(classify(url).reason, Reason.OK)


class PrivateNetworkNameTest(unittest.TestCase):
    def test_router_nas_and_tailnet_names_are_private(self) -> None:
        for url in (
            "http://fritz.box/",
            "http://speedport.ip/",
            "http://pi.hole/admin/",
            "http://routerlogin.net/",
            "http://router.asus.com/",
            "http://127.0.0.1.nip.io/",
            "http://localtest.me/",
            "http://nas.tail1234.ts.net/",
        ):
            with self.subTest(url=url):
                decision = classify(url, hister_host="hister.tail1234.ts.net")
                self.assertEqual(decision.reason, Reason.PRIVATE_HOST)

    def test_hosts_are_judged_as_browsers_map_them(self) -> None:
        cases = (
            ("http://\uff11\uff12\uff17.\uff10.\uff10.\uff11/", Reason.PRIVATE_IP),
            ("http://192.168.1.1\u3002/", Reason.PRIVATE_IP),
            ("http://foo.localhost\u3002/", Reason.PRIVATE_HOST),
            ("http://nas.local\uff0e/", Reason.PRIVATE_HOST),
            ("http://[::127.0.0.1]/", Reason.PRIVATE_IP),
            ("http://[64:ff9b::c0a8:101]/", Reason.PRIVATE_IP),
            ("http://[64:ff9b::808:808]/", Reason.OK),
            ("https://xn--bcher-kva.de/", Reason.OK),
            ("https://b\u00fccher.de/", Reason.OK),
        )
        for url, expected in cases:
            with self.subTest(url=url):
                self.assertEqual(classify(url).reason, expected)

    def test_is_public_address(self) -> None:
        self.assertTrue(urlpolicy.is_public_address("8.8.8.8"))
        self.assertTrue(urlpolicy.is_public_address("2606:4700:4700::1111"))
        for address in ("127.0.0.1", "192.168.1.1", "::1", "fe80::1%en0", "100.100.1.1", "nope"):
            with self.subTest(address=address):
                self.assertFalse(urlpolicy.is_public_address(address))


class DotSegmentTest(unittest.TestCase):
    def test_paths_a_browser_would_rewrite_are_invalid(self) -> None:
        deny = ("https://example.org/private/",)
        for url in (
            "https://example.org/public/../private/page",
            "https://example.org/public/%2e%2e/private/page",
            "https://example.org/public/%2E./private/page",
            "https://example.org/a/./b",
            # Browsers read "\" as "/" in http(s) paths.
            "https://example.org/public\\..\\private/page",
            "https://example.org/private\\page",
        ):
            with self.subTest(url=url):
                self.assertEqual(classify(url, deny=deny).reason, Reason.INVALID)


class SensitiveWordsTest(unittest.TestCase):
    def test_every_word_excludes_a_path_segment(self) -> None:
        for word in urlpolicy.SENSITIVE_WORDS:
            with self.subTest(word=word):
                self.assertEqual(classify(f"https://example.com/{word}").reason, Reason.SENSITIVE)

    def test_every_word_excludes_a_query_key(self) -> None:
        for word in urlpolicy.SENSITIVE_WORDS:
            with self.subTest(word=word):
                self.assertEqual(
                    classify(f"https://example.com/a?{word}=1").reason, Reason.SENSITIVE
                )

    def test_every_exact_key_excludes(self) -> None:
        for key in urlpolicy.SENSITIVE_QUERY_KEYS:
            with self.subTest(key=key):
                self.assertEqual(
                    classify(f"https://example.com/a?{key.upper()}=1").reason, Reason.SENSITIVE
                )

    def test_exact_keys_do_not_match_inside_other_keys(self) -> None:
        for url in (
            "https://example.com/a?page=1",
            "https://example.com/a?type=x",
            "https://example.com/a?keyword=x",
        ):
            with self.subTest(url=url):
                self.assertTrue(classify(url).eligible)

    def test_every_file_extension_excludes(self) -> None:
        for extension in urlpolicy.FILE_EXTENSIONS:
            with self.subTest(extension=extension):
                self.assertEqual(classify(f"https://example.com/a{extension}").reason, Reason.FILE)


class NormalizeTest(unittest.TestCase):
    CASES = (
        ("HTTPS://Example.COM/Path/Kept", "https://example.com/Path/Kept"),
        ("https://example.com", "https://example.com/"),
        ("https://example.com./a", "https://example.com/a"),
        ("https://example.com:443/a", "https://example.com/a"),
        ("http://example.com:80/a", "http://example.com/a"),
        ("http://example.com:8080/a", "http://example.com:8080/a"),
        ("https://example.com/a#section", "https://example.com/a"),
        ("https://example.com/a?b=2&a=1&c=3", "https://example.com/a?a=1&b=2&c=3"),
        ("https://example.com/a?b=2&a=2&a=1", "https://example.com/a?a=2&a=1&b=2"),
        ("https://example.com/a?q=a%20b&flag", "https://example.com/a?flag&q=a%20b"),
        ("https://example.com/a?&&x=1&", "https://example.com/a?x=1"),
        ("https://example.com/a?", "https://example.com/a"),
        (
            "https://example.com/a?utm_source=x&UTM_Medium=y&fbclid=1&gclid=2&dclid=3&msclkid=4"
            "&mc_cid=5&mc_eid=6&igshid=7&ref=8&ref_src=9&_hsenc=10&_hsmi=11&yclid=12&id=7",
            "https://example.com/a?id=7",
        ),
        ("https://example.com/a?reference=1", "https://example.com/a?reference=1"),
        ("https://Bücher.DE/Straße", "https://bücher.de/Straße"),
        ("https://XN--BCHER-KVA.de/", "https://xn--bcher-kva.de/"),
        ("https://[2606:4700:4700::1111]:443/", "https://[2606:4700:4700::1111]/"),
        ("  https://example.com/a  ", "https://example.com/a"),
    )

    def test_normalize(self) -> None:
        for url, expected in self.CASES:
            with self.subTest(url=url):
                self.assertEqual(normalize(url), expected)

    def test_classify_reports_the_normalized_url(self) -> None:
        decision = classify("HTTPS://Example.COM:443/a?b=2&utm_source=x&a=1#top")
        self.assertEqual(decision.normalized, "https://example.com/a?a=1&b=2")

    def test_normalize_is_idempotent(self) -> None:
        for url, _ in self.CASES:
            with self.subTest(url=url):
                once = normalize(url)
                self.assertEqual(normalize(once), once)

    def test_normalize_rejects_non_http(self) -> None:
        for url in ("ftp://example.com/", "mailto:a@example.com", "http:///a", "http://[::1/"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                normalize(url)


class SiteTest(unittest.TestCase):
    CASES = (
        ("example.com", "example.com"),
        ("www.example.com", "example.com"),
        ("a.b.c.example.com", "example.com"),
        ("WWW.Example.COM.", "example.com"),
        ("bbc.co.uk", "bbc.co.uk"),
        ("www.bbc.co.uk", "bbc.co.uk"),
        ("news.abc.net.au", "abc.net.au"),
        ("www.u-tokyo.ac.jp", "u-tokyo.ac.jp"),
        ("www.example.or.jp", "example.or.jp"),
        ("co.uk", "co.uk"),
        ("www.example.com.br", "example.com.br"),
        # Documented approximation: a hosting suffix is one site.
        ("someone.github.io", "github.io"),
        # Only two-letter top-level domains get the third label.
        ("www.example.co.com", "co.com"),
        ("localhost", "localhost"),
        ("8.8.8.8", "8.8.8.8"),
        ("2606:4700::1111", "2606:4700::1111"),
        ("[2606:4700::1111]", "2606:4700::1111"),
        ("www.bücher.de", "bücher.de"),
        ("www.xn--bcher-kva.de", "xn--bcher-kva.de"),
        ("", ""),
    )

    def test_site_of(self) -> None:
        for host, expected in self.CASES:
            with self.subTest(host=host):
                self.assertEqual(site_of(host), expected)


class TemplateTest(unittest.TestCase):
    CASES = (
        ("https://example.com/", "https://example.com/"),
        ("https://example.com/about", "https://example.com/about"),
        ("https://example.com/news/123", "https://example.com/news/{}"),
        ("https://example.com/2024/10/24/some-title", "https://example.com/{}/{}/{}/some-title"),
        ("https://example.com/item/deadbeef00", "https://example.com/item/{}"),
        ("https://example.com/item/DEADBEEF", "https://example.com/item/{}"),
        ("https://example.com/item/abc123", "https://example.com/item/abc123"),
        (
            "https://example.com/doc/550e8400-e29b-41d4-a716-446655440000/edit",
            "https://example.com/doc/{}/edit",
        ),
        ("https://www.bbc.co.uk/news/world-europe-67123456", "https://www.bbc.co.uk/news/{}"),
        ("https://example.com/review/iphone-16-review", "https://example.com/review/{}"),
        ("https://example.com/tag/covid-19", "https://example.com/tag/covid-19"),
        ("https://example.com/long-slug-without-digits", "https://example.com/{}"),
        ("https://example.com/world/some-headline-here", "https://example.com/world/{}"),
        ("https://example.com/wiki/Web_browser", "https://example.com/wiki/Web_browser"),
        ("https://example.com/how-to", "https://example.com/how-to"),
        ("https://example.com/news/12345.html", "https://example.com/news/{}.html"),
        ("https://example.com/news/index.html", "https://example.com/news/index.html"),
        ("https://example.com/a/", "https://example.com/a/"),
        ("https://example.com/watch?v=dQw4w9WgXcQ&t=10", "https://example.com/watch?t={}&v={}"),
        ("https://example.com/list?flag&page=2", "https://example.com/list?flag&page={}"),
        ("https://example.com/a?utm_source=x", "https://example.com/a"),
        ("HTTPS://Example.com:443/a/1#x", "https://example.com/a/{}"),
    )

    def test_template_of(self) -> None:
        for url, expected in self.CASES:
            with self.subTest(url=url):
                self.assertEqual(template_of(url), expected)

    def test_near_identical_pages_share_a_template(self) -> None:
        first = classify("https://news.example.com/article/1001?page=1")
        second = classify("https://news.example.com/article/2002?page=3")
        self.assertEqual(first.template, second.template)
        self.assertNotEqual(first.normalized, second.normalized)

    def test_template_of_rejects_non_http(self) -> None:
        with self.assertRaises(ValueError):
            template_of("ftp://example.com/1")


class DenyEntryTest(unittest.TestCase):
    CASES = (
        ("example.com", "example.com"),
        (".Example.COM.", "example.com"),
        ("  example.com  ", "example.com"),
        ("bücher.de", "bücher.de"),
        ("https://Example.com", "https://example.com/"),
        ("HTTPS://Example.com:443/Private/", "https://example.com/Private/"),
        ("http://example.com:8080/x", "http://example.com:8080/x"),
        ("https://example.com/a?b=1#frag", "https://example.com/a?b=1"),
        # Queries are canonicalized like candidate URLs: sorted, tracking dropped.
        ("https://example.com/a?b=2&a=1&utm_source=x", "https://example.com/a?a=1&b=2"),
    )

    def test_normalize_deny_entry(self) -> None:
        for entry, expected in self.CASES:
            with self.subTest(entry=entry):
                self.assertEqual(normalize_deny_entry(entry), expected)

    def test_invalid_entries(self) -> None:
        for entry in (
            "",
            "  ",
            "a b",
            "example.com/path",
            "ftp://example.com/",
            "https://",
            "https://u@x.com/",
            "host:80",
            "https://example.com/a/../b",
            "https://example.com/a\\b",
        ):
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                normalize_deny_entry(entry)

    def test_a_prefix_denies_its_pages_however_their_urls_are_spelled(self) -> None:
        cases = (
            # Hosts are compared as a browser maps them, in either direction.
            ("https://xn--bcher-kva.de/konto/x", "https://bücher.de/konto/"),
            ("https://bücher.de/konto/x", "https://xn--bcher-kva.de/konto/"),
            ("https://news.\uff45\uff58\uff41\uff4d\uff50\uff4c\uff45.com/private/x", DENY[1]),
            ("https://news.example.com\u3002/private/x", DENY[1]),
            # Case and percent-encoding of the query.
            ("https://shop.example.org/p?ID=A%2D1", "https://shop.example.org/p?id=a-1"),
            # A query entry denies its page with other parameters in any order.
            (
                "https://video.example.org/watch?app=desktop&v=abc",
                "https://video.example.org/watch?v=abc",
            ),
        )
        for url, entry in cases:
            with self.subTest(url=url, entry=entry):
                self.assertEqual(classify(url, deny=(entry,)).reason, Reason.DENIED)

    def test_a_query_prefix_denies_only_its_own_page(self) -> None:
        deny = ("https://video.example.org/watch?v=abc",)
        for url in (
            "https://video.example.org/watch?v=xyz",
            "https://video.example.org/watch?app=desktop&v=xyz",
            "https://video.example.org/watch/v/abc?v=abc",
            "https://video.example.org/",
        ):
            with self.subTest(url=url):
                self.assertEqual(classify(url, deny=deny).reason, Reason.OK)

    def test_a_host_suffix_denies_the_host_as_a_browser_maps_it(self) -> None:
        decision = classify("https://www.blocked.example.org\u3002/", deny=DENY)
        self.assertEqual(decision.reason, Reason.DENIED)

    def test_an_exact_url_from_the_address_bar_denies_its_page(self) -> None:
        for entry in (
            "https://shop.example.org/p?b=2&a=1",
            "https://shop.example.org/p?b=2&a=1&utm_source=newsletter",
        ):
            with self.subTest(entry=entry):
                decision = classify("https://shop.example.org/p?b=2&a=1", deny=(entry,))
                self.assertEqual(decision.reason, Reason.DENIED)

    def test_prefix_without_path_does_not_match_longer_host(self) -> None:
        deny = ("https://example.com",)
        self.assertEqual(classify("https://example.com/a", deny=deny).reason, Reason.DENIED)
        self.assertTrue(classify("https://example.com.evil.org/a", deny=deny).eligible)

    def test_idn_entry_matches_punycode_host_and_back(self) -> None:
        self.assertEqual(
            classify("https://xn--bcher-kva.de/", deny=("bücher.de",)).reason, Reason.DENIED
        )
        self.assertEqual(
            classify("https://www.bücher.de/", deny=("xn--bcher-kva.de",)).reason, Reason.DENIED
        )

    def test_idn_hister_host(self) -> None:
        decision = classify("https://xn--bcher-kva.de/", hister_host="Bücher.de")
        self.assertEqual(decision.reason, Reason.HISTER_HOST)


if __name__ == "__main__":
    unittest.main()
