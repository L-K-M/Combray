"""Which history URLs the site check may load, and how to group them.

Re-visiting a URL from browsing history must not act on the owner's behalf, leak a
secret, or reach a private network. `classify` therefore rejects URLs that point at
local or private hosts, look like actions (logout, unsubscribe, checkout, ...) or carry
one-time tokens, and pages that are files or search results. The keyword rules prefer
false positives: excluding a harmless page costs one test, loading a one-time link
can cost the owner an account action.

The module is pure: no I/O, no network, no DNS. Host names are judged as a browser
maps them (UTS 46 to ASCII, so fullwidth digits and ideographic dots count as what they
become); names that resolve to private addresses without a telling suffix are caught
by the caller, which resolves planned pages (see `is_public_address`).
"""

from __future__ import annotations

import functools
import ipaddress
import posixpath
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from enum import StrEnum
from urllib.parse import unquote, unquote_plus, urlsplit


class Reason(StrEnum):
    """Why `classify` accepted or rejected a URL, in the order the checks run."""

    OK = "ok"
    INVALID = "invalid"  # Not parseable as a URL with a usable host.
    SCHEME = "scheme"  # Not http or https.
    USERINFO = "userinfo"  # Credentials in the URL.
    PORT = "port"  # Port other than the scheme's default.
    PRIVATE_IP = "private-ip"  # IP literal that is not publicly routable.
    PRIVATE_HOST = "private-host"  # localhost, a private-use name, or a single label.
    HISTER_HOST = "hister-host"  # The owner's Hister server itself.
    DENIED = "denied"  # Matches the owner's deny list.
    SENSITIVE = "sensitive"  # Looks like an action or carries a secret.
    FILE = "file"  # A download rather than a page.
    SEARCH = "search"  # A search result page: reveals queries, tests little.


@dataclass(frozen=True)
class Decision:
    eligible: bool
    reason: Reason
    normalized: str | None  # Set only when eligible.
    host: str  # Lowercase, without brackets or trailing dot; "" when unparseable.
    site: str  # See `site_of`; "" when there is no host.
    template: str  # See `template_of`; "" when not eligible.


DEFAULT_PORTS = {"http": 80, "https": 443}

# Private-use and special-purpose names (RFC 6761, RFC 6762, RFC 8375, and common
# home-router and corporate conventions). A host equal to or under any of them is
# private. "localdomain" is not in the design spec; it is a common home-router suffix.
# The second group are names of home routers, NAS boxes and tailnets that resolve
# to the local network (fritz.box, speedport.ip, pi.hole, Tailscale's MagicDNS
# names), and public wildcard names that resolve to any address, loopback included.
PRIVATE_HOST_SUFFIXES = (
    "localhost",
    "local",
    "localdomain",
    "lan",
    "home",
    "internal",
    "intranet",
    "corp",
    "home.arpa",
    "test",
    "example",
    "invalid",
    # Routers, NAS boxes, ad blockers and tailnets.
    "box",
    "ip",
    "hole",
    "ts.net",
    "routerlogin.net",
    "routerlogin.com",
    "router.asus.com",
    "tplinkwifi.net",
    "tplinklogin.net",
    "myfritz.net",
    # Wildcard DNS for local development.
    "nip.io",
    "sslip.io",
    "localtest.me",
    "lvh.me",
)

# Words that mark an action or a secret when they appear in a path segment, a query
# key or a query value. Text is split into tokens on anything but letters and digits,
# at lowercase-to-uppercase steps and between letters and digits, so "log-out",
# "LogOut" and "log_out" all match "log-out", and "oauth2callback" matches "callback".
# A token matches a keyword of _AFFIX_MATCH_MIN_LENGTH or more letters when it starts
# or ends with it ("myaccount", "resetPassword", "orders"); shorter keywords must equal
# a whole token, because as substrings they occur in common words ("author", "lesson",
# "display", "cartoon"). Several entries beyond the design spec (authorize, jsessionid,
# ...) close gaps the token rule would otherwise leave.
SENSITIVE_WORDS = (
    "logout", "log-out", "signout", "sign-out", "logoff", "unsubscribe", "optout",
    "opt-out", "confirm", "verify", "verification", "activate", "activation", "reset",
    "password", "passwd", "token", "auth", "oauth", "oauth2", "sso", "saml", "callback",
    "login", "signin", "sign-in", "signup", "register", "magic", "invite", "invitation",
    "approve", "reject", "delete", "remove", "cancel", "revoke", "checkout", "payment",
    "pay", "billing", "cart", "order", "account", "settings", "admin", "wp-admin",
    "session", "otp", "2fa", "mfa", "consent", "download", "export", "share",
    # Additions to the spec's list.
    "authorize", "authorization", "authenticate", "authentication", "jsessionid",
    "phpsessid",
    # One-click actions of invitation, mailing-list and voucher links, and secrets.
    "rsvp", "accept", "decline", "claim", "redeem", "unsub", "subscribe", "vote",
    "secret", "passcode",
)  # fmt: skip

# Query keys that carry identities or credentials when they are the whole key
# (compared case-insensitively). Single letters are included because mailing-list
# and tracking links use them for subscriber ids and signed tokens.
SENSITIVE_QUERY_KEYS = frozenset(
    {
        "code", "state", "nonce", "key", "apikey", "api_key", "access_token", "id_token",
        "refresh_token", "sig", "signature", "hmac", "hash", "ticket", "jwt", "auth",
        "session", "sid", "email", "e", "mail", "phone", "user", "uid", "u", "token",
        "t", "k", "p", "pwd",
        # One-time codes and secrets under short or spelled-out names.
        "pin", "passcode", "secret", "tok", "ott", "otc", "e-mail", "e_mail",
    }
)  # fmt: skip

# A query value this long, made only of URL-safe and base64 characters and mixing
# letters with digits, is treated as an opaque secret (signed links, one-time codes).
# 20 characters catch a 128-bit token in base64url (22 characters).
_OPAQUE_MIN_LENGTH = 20
_OPAQUE_VALUE = re.compile(r"[A-Za-z0-9_\-.=+/%~]+")
# Path segments carry tokens too (/c/<token>, /unsub/<token>, a JWT). A segment is
# split into pieces at separators: one piece this long mixing letters and digits is
# a token, and so is a whole segment of _OPAQUE_MIN_LENGTH that is not a word slug
# ("how-to-install-python-3-12" has mostly alphabetic pieces).
_OPAQUE_PIECE_MIN_LENGTH = 16
_SEGMENT_SEPARATORS = re.compile(r"[-_.~+]")
_SLUG_MIN_PIECES = 3
# A query value that holds an e-mail address identifies its owner.
_EMAIL = re.compile(r"[^@\s]+@[^@\s.]+(?:\.[^@\s.]+)+")
# Matrix-style parameters inside one query parameter ("a=1;sig=...").
_SUB_PARAMETER_SEPARATOR = ";"

# Last path segment extensions of downloads and non-page resources.
FILE_EXTENSIONS = frozenset(
    {
        ".pdf", ".zip", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".dmg", ".pkg",
        ".exe", ".msi", ".apk", ".ipa", ".deb", ".rpm", ".iso", ".img", ".bin", ".mp3",
        ".mp4", ".m4a", ".mov", ".avi", ".mkv", ".webm", ".ogg", ".wav", ".flac", ".jpg",
        ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".csv", ".xls", ".xlsx", ".doc",
        ".docx", ".ppt", ".pptx", ".json", ".xml", ".rss", ".atom", ".txt",
    }
)  # fmt: skip

# A page is a search result page when its last path segment is one of these, or its
# path is the root, and it has one of the query keys below (Kagi, Google and Bing use
# /search?q=, YouTube /results?search_query=, DuckDuckGo and Qwant /?q=). Matching
# the last segment rather than the whole path also covers localized paths such as
# /en/search. "p" (Yahoo) is also a sensitive key, which is checked first.
SEARCH_PATH_SEGMENTS = frozenset({"search", "results"})
SEARCH_QUERY_KEYS = frozenset({"q", "query", "search", "p", "search_query"})

# Query parameters that only attribute a visit; they are removed so the same page
# reached from different links is tested once. Compared case-insensitively.
TRACKING_PARAMS = frozenset(
    {
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref",
        "ref_src", "_hsenc", "_hsmi", "yclid",
    }
)  # fmt: skip
TRACKING_PARAM_PREFIXES = ("utm_",)

# Second-level labels under which country-code registries sell names (bbc.co.uk,
# abc.net.au, u-tokyo.ac.jp). See `site_of`.
_COUNTRY_SECOND_LEVEL_LABELS = frozenset({"co", "com", "net", "org", "gov", "edu", "ac", "or"})
_COUNTRY_CODE_LENGTH = 2

_TEMPLATE_PLACEHOLDER = "{}"
_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_HEX_ID = re.compile(r"[0-9a-fA-F]{8,}")
_NUMBER = re.compile(r"[0-9]+")
# Segments at least this long that mix letters and digits are ids or dated slugs
# ("world-europe-67123456"); shorter ones are often meaningful ("covid-19", "web3").
_LONG_SLUG_MIN_LENGTH = 12
# Word-only slugs of this many words or characters are article titles
# ("some-headline-here"), so one article stands for the others of its section.
_TITLE_SLUG_MIN_WORDS = 3
_TITLE_SLUG_MIN_LENGTH = 20
_SLUG_WORD_SEPARATORS = re.compile(r"[-_]+")
# Page extensions kept in templates, so "/news/12345.html" becomes "/news/{}.html".
_PAGE_EXTENSIONS = frozenset({"html", "htm", "shtml", "php", "asp", "aspx", "jsp"})

_AFFIX_MATCH_MIN_LENGTH = 5
# Word boundaries inside identifiers: "logOut", and an acronym before a word
# ("SAMLResponse" is "SAML Response").
_CAMEL_CASE_STEP = re.compile(r"(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_WORD_OR_NUMBER = re.compile(r"[^\W\d_]+|\d+")
# A last label like this makes browsers parse the host as an IPv4 address, including
# shorthand forms such as 127.1, 0x7f.1 or 2130706433 (WHATWG URL "ends in a number").
_NUMERIC_LABEL = re.compile(r"0[xX][0-9a-fA-F]*|[0-9]+")
_FORBIDDEN_HOST_CHARACTERS = frozenset('%<>"{}|\\^`[]/?#@:')
_URL_SCHEME_SEPARATOR = "://"
# Path segments a browser resolves away ("/a/../b" loads "/b"), in any encoding.
_DOT_SEGMENTS = frozenset({".", ".."})
# Browsers read it as "/" in http(s) paths (WHATWG URL); urlsplit keeps it in a segment.
_BACKSLASH = "\\"
# More URL-prefix entries than a hand-written deny list holds.
_DENY_PREFIX_CACHE_SIZE = 1024
# IPv6 ranges that embed an IPv4 address in their last 32 bits: IPv4-compatible
# (::a.b.c.d, deprecated but still parsed) and NAT64 (RFC 6052).
_IPV4_EMBEDDING_NETWORKS = (
    ipaddress.IPv6Network("::/96"),
    ipaddress.IPv6Network("64:ff9b::/96"),
)
_IPV4_BITS = 0xFFFFFFFF


@dataclass(frozen=True)
class _Param:
    """One query parameter, kept as written so normalizing never re-encodes it."""

    raw: str
    key: str  # Decoded.
    raw_value: str | None  # None for a bare key without "=".

    @property
    def value(self) -> str:
        return unquote_plus(self.raw_value or "")


@dataclass(frozen=True)
class _Url:
    scheme: str
    host: str
    port: int | None  # None when absent or the scheme's default.
    path: str
    params: tuple[_Param, ...]  # Tracking parameters removed, sorted by key.


@dataclass(frozen=True)
class _DenyPrefix:
    """A URL-prefix deny entry in the form `_deny_matches` compares loosely."""

    origin: tuple[str, str, int | None]  # Scheme, host as a browser maps it, port.
    path: str  # See `_folded`.
    params: frozenset[str]  # See `_folded_param`; empty when the entry has no query.


def classify(url: str, hister_host: str | None = None, deny: Iterable[str] = ()) -> Decision:
    """Decide whether the site check may load `url`; the checks run in `Reason` order.

    `hister_host` is the host name of the owner's Hister server: it and its subdomains
    are excluded. `deny` holds deny-list entries (see `normalize_deny_entry`); an entry
    that is not valid raises ValueError.
    """
    try:
        parts = urlsplit(url.strip())
        host = _clean_host(parts.hostname)
    except ValueError:
        return _rejected(Reason.INVALID, "")

    if parts.scheme not in DEFAULT_PORTS:
        return _rejected(Reason.SCHEME, host)
    if "@" in parts.netloc:
        return _rejected(Reason.USERINFO, host)
    try:
        port = parts.port
    except ValueError:
        return _rejected(Reason.INVALID, host)
    if port is not None and port != DEFAULT_PORTS[parts.scheme]:
        return _rejected(Reason.PORT, host)

    # Judged as the browser will resolve it, not as written.
    mapped_host = _browser_host(host)
    if mapped_host is None:
        return _rejected(Reason.INVALID, host)
    host_problem = _host_problem(mapped_host)
    if host_problem is not None:
        return _rejected(host_problem, host)
    if hister_host and _within(mapped_host, _clean_host(hister_host)):
        return _rejected(Reason.HISTER_HOST, host)
    if _browser_rewrites_path(parts.path):
        # The browser would load another path than the one the deny list and the
        # keyword rules see; history from a browser never contains such paths.
        return _rejected(Reason.INVALID, host)

    parsed = _Url(
        scheme=parts.scheme,
        host=host,
        port=None,
        path=parts.path or "/",
        params=_kept_params(parts.query),
    )
    normalized = _unsplit(parsed)
    if any(_deny_matches(normalize_deny_entry(entry), parsed, mapped_host) for entry in deny):
        return _rejected(Reason.DENIED, host)
    if _is_sensitive(parsed):
        return _rejected(Reason.SENSITIVE, host)
    if _is_file(parsed.path):
        return _rejected(Reason.FILE, host)
    if _is_search_page(parsed):
        return _rejected(Reason.SEARCH, host)

    return Decision(
        eligible=True,
        reason=Reason.OK,
        normalized=normalized,
        host=host,
        site=site_of(host),
        template=_template(parsed),
    )


def normalize(url: str) -> str:
    """The canonical form of an http(s) URL, whether or not it is eligible.

    Lowercases the scheme and host, drops a trailing dot, the default port, the
    fragment and tracking parameters, sorts the remaining parameters by key (keeping
    the order of repeated keys) and keeps the path and encoding as written. Raises
    ValueError for anything that is not an http(s) URL with a host.
    """
    return _unsplit(_parse(url))


def template_of(url: str) -> str:
    """`url` normalized, with id-like path segments and all query values as "{}".

    Pages sharing a template are usually the same page type of one site (articles,
    products), so testing one of them is enough. A path segment becomes "{}" when it
    is a number, a UUID, at least 8 hex digits, or at least _LONG_SLUG_MIN_LENGTH
    characters mixing letters and digits; a page extension such as ".html" is kept.
    """
    return _template(_parse(url))


def site_of(host: str) -> str:
    """Approximate the registrable domain of `host` without the Public Suffix List.

    Keeps the last two labels, or three when the last is a two-letter country code
    and the second-to-last a common registry label (bbc.co.uk, abc.net.au). Hosting
    suffixes such as github.io are therefore one site, and ccTLDs with other
    second-level labels (city.kyoto.jp) are split too coarsely. IP literals and
    single-label hosts are their own site. Internationalized names are kept as given,
    so "bücher.de" and "xn--bcher-kva.de" count as different sites.
    """
    cleaned = _clean_host(host)
    if not cleaned or _ip_address(cleaned) is not None:
        return cleaned

    labels = cleaned.split(".")
    if len(labels) <= 2:
        return cleaned
    top, second = labels[-1], labels[-2]
    is_country_code = len(top) == _COUNTRY_CODE_LENGTH and top.isascii() and top.isalpha()
    if is_country_code and second in _COUNTRY_SECOND_LEVEL_LABELS:
        return ".".join(labels[-3:])
    return ".".join(labels[-2:])


def normalize_deny_entry(entry: str) -> str:
    """Canonical form of a deny-list entry; raises ValueError when it is neither form.

    A host suffix ("example.com", ".example.com") denies that host and its subdomains.
    An http(s) URL prefix ("https://example.com/private/") denies normalized URLs that
    start with it; its scheme and host are normalized like URLs, and a prefix without
    a path gets "/" so it cannot match a longer host name. Matching errs towards
    denying: hosts are compared as a browser maps them, paths and queries ignoring
    case and percent-encoding, and a prefix with a query denies its path whenever the
    URL has at least those parameters, in any order.
    """
    text = entry.strip()
    if not text or any(character.isspace() for character in text):
        raise ValueError("a deny entry must be one word")

    if _URL_SCHEME_SEPARATOR not in text:
        host = _clean_host(text.strip("."))
        if not host or any(character in _FORBIDDEN_HOST_CHARACTERS for character in host):
            raise ValueError("not a host suffix")
        return host

    parts = urlsplit(text)
    if parts.scheme not in DEFAULT_PORTS:
        raise ValueError("a URL prefix must be http or https")
    if "@" in parts.netloc:
        raise ValueError("a URL prefix must not contain credentials")
    host = _clean_host(parts.hostname)
    if not host:
        raise ValueError("a URL prefix needs a host")
    port = parts.port
    if port == DEFAULT_PORTS[parts.scheme]:
        port = None
    if _browser_rewrites_path(parts.path):
        raise ValueError("a URL prefix must not contain . or .. path segments or backslashes")
    prefix = f"{parts.scheme}://{_netloc(host, port)}{parts.path or '/'}"
    # Canonicalized like the URLs it is matched against (sorted, tracking dropped),
    # so a URL pasted from the address bar denies that page.
    params = _kept_params(parts.query)
    if params:
        prefix += "?" + "&".join(param.raw for param in params)
    return prefix


def _rejected(reason: Reason, host: str) -> Decision:
    return Decision(
        eligible=False,
        reason=reason,
        normalized=None,
        host=host,
        site=site_of(host) if host else "",
        template="",
    )


def _parse(url: str) -> _Url:
    parts = urlsplit(url.strip())
    if parts.scheme not in DEFAULT_PORTS:
        raise ValueError("not an http or https URL")
    host = _clean_host(parts.hostname)
    if not host:
        raise ValueError("URL has no host")
    port = parts.port
    if port == DEFAULT_PORTS[parts.scheme]:
        port = None
    return _Url(
        scheme=parts.scheme,
        host=host,
        port=port,
        path=parts.path or "/",
        params=_kept_params(parts.query),
    )


def _unsplit(url: _Url) -> str:
    text = f"{url.scheme}://{_netloc(url.host, url.port)}{url.path}"
    if url.params:
        text += "?" + "&".join(param.raw for param in url.params)
    return text


def _netloc(host: str, port: int | None) -> str:
    netloc = f"[{host}]" if ":" in host else host
    if port is not None:
        netloc += f":{port}"
    return netloc


def _clean_host(host: str | None) -> str:
    """Lowercase without brackets or trailing dots; "example.com." is "example.com"."""
    if host is None:
        return ""
    return host.strip("[]").rstrip(".").lower()


def _browser_host(host: str) -> str | None:
    """`host` mapped to ASCII the way a browser's URL parser does, or None if it cannot be.

    Python's IDNA codec applies the same Unicode mapping as UTS 46 for these cases:
    fullwidth digits become ASCII digits and the ideographic full stops become dots,
    so "\uff11\uff12\uff17.\uff10.\uff10.\uff11" is judged as 127.0.0.1.
    """
    if _ip_address(host) is not None:
        return host
    try:
        mapped = host.encode("idna").decode("ascii")
    except UnicodeError:
        return None
    return _clean_host(mapped)


def _browser_rewrites_path(path: str) -> bool:
    """Whether a browser loads another path than `path`: it reads "\\" as "/" in
    http(s) URLs and resolves dot segments in any encoding."""
    if _BACKSLASH in path:
        return True
    return any(unquote(segment) in _DOT_SEGMENTS for segment in path.split("/"))


def _kept_params(query: str) -> tuple[_Param, ...]:
    params = []
    for raw in query.split("&"):
        if not raw:
            continue
        raw_key, separator, raw_value = raw.partition("=")
        key = unquote_plus(raw_key)
        if _is_tracking(key):
            continue
        params.append(_Param(raw=raw, key=key, raw_value=raw_value if separator else None))
    # A stable sort keeps repeated keys in their original order, which servers may rely on.
    params.sort(key=lambda param: param.key)
    return tuple(params)


def _is_tracking(key: str) -> bool:
    folded = key.casefold()
    return folded in TRACKING_PARAMS or folded.startswith(TRACKING_PARAM_PREFIXES)


def _host_problem(host: str) -> Reason | None:
    if not host:
        return Reason.INVALID

    address = _ip_address(host)
    if address is not None:
        return None if _is_public(address) else Reason.PRIVATE_IP
    # Browsers refuse control characters in a host; the IDNA codec lets them through.
    if any(
        not character.isprintable()
        or character.isspace()
        or character in _FORBIDDEN_HOST_CHARACTERS
        for character in host
    ):
        return Reason.INVALID

    labels = host.split(".")
    if "" in labels:
        return Reason.INVALID
    if _NUMERIC_LABEL.fullmatch(labels[-1]):
        # Not a canonical IPv4 address (that parsed above), but a browser would still
        # read it as one, and it may well point at a private network.
        return Reason.PRIVATE_IP
    if len(labels) == 1:
        return Reason.PRIVATE_HOST
    if any(_within(host, suffix) for suffix in PRIVATE_HOST_SUFFIXES):
        return Reason.PRIVATE_HOST
    return None


def _ip_address(host: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        return None


def is_public_address(text: str) -> bool:
    """Whether `text` is an IP address on the public internet (as `classify` judges it).

    For callers that resolve host names: a name that resolves to any non-public
    address must not be loaded. Anything that is not an address is not public.
    """
    address = _ip_address(text.split("%", 1)[0])  # Drop an IPv6 zone ("fe80::1%en0").
    return address is not None and _is_public(address)


def _is_public(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    # is_global alone accepts IPv4 multicast.
    if address.is_multicast or not address.is_global:
        return False
    if isinstance(address, ipaddress.IPv6Address):
        # Transition addresses lead to the IPv4 address they embed.
        embedded = [address.ipv4_mapped, address.sixtofour]
        if any(address in network for network in _IPV4_EMBEDDING_NETWORKS):
            embedded.append(ipaddress.IPv4Address(int(address) & _IPV4_BITS))
        return all(inner is None or _is_public(inner) for inner in embedded)
    return True


def _within(host: str, domain: str) -> bool:
    """Whether `host` is `domain` or one of its subdomains, comparing IDNs as ASCII."""
    if not domain:
        return False
    host, domain = _ascii_host(host), _ascii_host(domain)
    return host == domain or host.endswith("." + domain)


def _ascii_host(host: str) -> str:
    try:
        return host.encode("idna").decode("ascii").lower()
    except UnicodeError:
        # Not a valid IDN (for example an over-long label): compare it as written.
        return host


def _deny_matches(entry: str, url: _Url, mapped_host: str) -> bool:
    """Whether a normalized deny entry covers `url`, whose host a browser maps to `mapped_host`."""
    if _URL_SCHEME_SEPARATOR not in entry:
        return _within(mapped_host, entry)
    if _unsplit(url).startswith(entry):
        return True

    # Looser comparisons that only add matches: a server may serve the denied page
    # under another spelling of its URL, and when in doubt a URL is skipped.
    prefix = _deny_prefix(entry)
    if (url.scheme, mapped_host, url.port) != prefix.origin:
        return False
    path = _folded(url.path)
    if not prefix.params:
        return path.startswith(prefix.path)
    return path == prefix.path and prefix.params <= {_folded_param(param) for param in url.params}


# Classifying a history checks every URL against every entry; parse each entry once.
@functools.lru_cache(maxsize=_DENY_PREFIX_CACHE_SIZE)
def _deny_prefix(entry: str) -> _DenyPrefix:
    denied = _parse(entry)
    return _DenyPrefix(
        origin=(denied.scheme, _browser_host(denied.host) or denied.host, denied.port),
        path=_folded(denied.path),
        params=frozenset(_folded_param(param) for param in denied.params),
    )


def _folded(path: str) -> str:
    """A path decoded and without case, as a lenient server may read it."""
    return unquote(path).casefold()


def _folded_param(param: _Param) -> str:
    """A query parameter as "key=value", decoded and without case; a bare key has "="."""
    return f"{param.key}={param.value}".casefold()


def _is_sensitive(url: _Url) -> bool:
    segments = [unquote(segment) for segment in url.path.split("/") if segment]
    if any(_is_token_segment(segment) for segment in segments):
        return True
    texts = list(segments)
    for param in url.params:
        for key, raw_value in _sub_parameters(param):
            if key.casefold() in SENSITIVE_QUERY_KEYS:
                return True
            if raw_value is None:
                continue
            if _looks_opaque(raw_value) or _EMAIL.search(unquote_plus(raw_value)):
                return True
        texts.append(param.key)
        texts.append(param.value)
    return any(_mentions_sensitive_word(text) for text in texts)


def _sub_parameters(param: _Param) -> list[tuple[str, str | None]]:
    """The parameter, plus any "key=value" parts after ";" in its value ("a=1;sig=x")."""
    parts = [(param.key, param.raw_value)]
    if param.raw_value is None or _SUB_PARAMETER_SEPARATOR not in param.raw_value:
        return parts
    first, *rest = param.raw_value.split(_SUB_PARAMETER_SEPARATOR)
    parts = [(param.key, first)]
    for piece in rest:
        raw_key, separator, raw_value = piece.partition("=")
        parts.append((unquote_plus(raw_key), raw_value if separator else None))
    return parts


def _looks_opaque(raw_value: str) -> bool:
    if len(raw_value) < _OPAQUE_MIN_LENGTH or not _OPAQUE_VALUE.fullmatch(raw_value):
        return False
    return _mixes_letters_and_digits(raw_value)


def _is_token_segment(segment: str) -> bool:
    """Whether a decoded path segment looks like a secret rather than a name or slug."""
    if not _OPAQUE_VALUE.fullmatch(segment):
        return False  # Words in other scripts, "@user", "Special:Random", ...
    pieces = [piece for piece in _SEGMENT_SEPARATORS.split(segment) if piece]
    if any(
        len(piece) >= _OPAQUE_PIECE_MIN_LENGTH and _mixes_letters_and_digits(piece)
        for piece in pieces
    ):
        return True
    if len(segment) < _OPAQUE_MIN_LENGTH or not _mixes_letters_and_digits(segment):
        return False
    alphabetic = sum(1 for piece in pieces if piece.isalpha())
    is_slug = len(pieces) >= _SLUG_MIN_PIECES and alphabetic * 2 >= len(pieces)
    return not is_slug


def _mixes_letters_and_digits(text: str) -> bool:
    has_letter = any(character.isalpha() for character in text)
    has_digit = any(character.isdigit() for character in text)
    return has_letter and has_digit


def _tokens(text: str) -> list[str]:
    return _WORD_OR_NUMBER.findall(_CAMEL_CASE_STEP.sub(" ", text).casefold())


_SENSITIVE_PHRASES = tuple(tuple(_tokens(word)) for word in SENSITIVE_WORDS)


def _mentions_sensitive_word(text: str) -> bool:
    tokens = _tokens(text)
    return any(_contains_phrase(tokens, phrase) for phrase in _SENSITIVE_PHRASES)


def _contains_phrase(tokens: Sequence[str], phrase: Sequence[str]) -> bool:
    if len(phrase) == 1:
        (word,) = phrase
        if len(word) < _AFFIX_MATCH_MIN_LENGTH:
            return word in tokens
        return any(token.startswith(word) or token.endswith(word) for token in tokens)

    width = len(phrase)
    return any(
        tuple(tokens[i : i + width]) == tuple(phrase) for i in range(len(tokens) - width + 1)
    )


def _is_file(path: str) -> bool:
    last_segment = unquote(path.rsplit("/", 1)[-1])
    extension = posixpath.splitext(last_segment)[1].casefold()
    return extension in FILE_EXTENSIONS


def _is_search_page(url: _Url) -> bool:
    segments = [segment for segment in url.path.split("/") if segment]
    if segments and unquote(segments[-1]).casefold() not in SEARCH_PATH_SEGMENTS:
        return False
    return any(param.key.casefold() in SEARCH_QUERY_KEYS for param in url.params)


def _template(url: _Url) -> str:
    path = "/".join(_template_segment(segment) for segment in url.path.split("/"))
    text = f"{url.scheme}://{_netloc(url.host, url.port)}{path}"
    if not url.params:
        return text
    query = "&".join(
        param.raw
        if param.raw_value is None
        else f"{param.raw.partition('=')[0]}={_TEMPLATE_PLACEHOLDER}"
        for param in url.params
    )
    return f"{text}?{query}"


def _template_segment(segment: str) -> str:
    stem, dot, extension = segment.rpartition(".")
    if dot and extension.casefold() in _PAGE_EXTENSIONS:
        return f"{_TEMPLATE_PLACEHOLDER}.{extension}" if _is_variable(unquote(stem)) else segment
    return _TEMPLATE_PLACEHOLDER if _is_variable(unquote(segment)) else segment


def _is_variable(text: str) -> bool:
    if not text:
        return False
    if _NUMBER.fullmatch(text) or _UUID.fullmatch(text) or _HEX_ID.fullmatch(text):
        return True
    if _is_title_slug(text):
        return True
    if len(text) < _LONG_SLUG_MIN_LENGTH:
        return False
    return _mixes_letters_and_digits(text)


def _is_title_slug(text: str) -> bool:
    """An article title written as a slug: "some-headline-here", not "about" or "Web_browser"."""
    words = [word for word in _SLUG_WORD_SEPARATORS.split(text) if word]
    if not words or not all(word.isalnum() for word in words):
        return False
    return len(words) >= _TITLE_SLUG_MIN_WORDS or len(text) >= _TITLE_SLUG_MIN_LENGTH
