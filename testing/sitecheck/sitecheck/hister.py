"""Read the owner's browsing history from a Hister server.

Only ``GET {base}/api/history`` is used. It returns the latest indexed documents,
newest first, ``PAGE_SIZE`` per page. Each response carries an opaque ``page_key``
(the last hit's sort values) that the next request sends as ``last``; servers that
send none page by the last document's URL instead. The
client keeps only what selection needs (URL, update time, visit count): titles,
page text and HTML in the response are dropped and never stored.

The access token is a credential for the whole history. It is sent only in the
``X-Access-Token`` header, only to the configured origin (cross-origin redirects are
refused because urllib would copy the header to the new host), never through a proxy,
and never placed in exception messages or logs.
"""

from __future__ import annotations

import http.client
import json
import logging
import math
import re
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any

from sitecheck.urlpolicy import DEFAULT_PORTS

PAGE_SIZE = 100  # Fixed by the server; a shorter page is the last one.
DEFAULT_MAX_PAGES = 1000
DEFAULT_TIMEOUT_SECONDS = 30.0

# Each incremental sync re-reads a day before the previous sync started, so documents
# indexed while that sync was running, or with a lagging timestamp, are not missed.
SYNC_OVERLAP_SECONDS = 86_400
LAST_SYNC_META_KEY = "hister_last_sync"  # Store meta key: unix ts when the last sync began.

_HISTORY_PATH = "/api/history"
_ALLOWED_SCHEMES = ("http", "https")
_REQUEST_HEADERS = {
    "Accept": "application/json",
    # Hister's CSRF middleware accepts this origin as its native/CLI client convention.
    "Origin": "hister://",
    "User-Agent": "combray-sitecheck/1",
}
_TOKEN_HEADER = "X-Access-Token"

# A full page of 100 documents can carry their HTML and text, which the client
# discards; the cap only stops a misbehaving server from exhausting memory.
_MAX_RESPONSE_BYTES = 64 * 1024 * 1024
_READ_CHUNK_BYTES = 1024 * 1024

# Hister's document type for web pages. Older servers omit the field.
_WEB_DOC_TYPES = frozenset({0, "0", "web"})

# Values beyond these are corrupt data, not history. The bounds also keep every
# value inside SQLite's 64-bit INTEGER range for the store.
_MAX_UNIX_SECONDS = 253_402_300_799  # 9999-12-31T23:59:59Z
_MAX_ADD_COUNT = 2**31 - 1

_log = logging.getLogger(__name__)


class HisterError(Exception):
    """Hister could not be read. Messages never contain the access token."""


class HisterConfigError(HisterError):
    """The configured base URL or token cannot be used."""


class HisterAuthError(HisterError):
    """The server rejected the access token (HTTP 401 or 403)."""


class HisterUnavailable(HisterError):
    """The history API does not exist: disabled on the server, or not a Hister URL."""


class StopReason(str, Enum):
    """Why paging ended."""

    END = "end"  # A short or empty page: everything matching the request was read.
    CAP = "cap"  # max_pages was reached while more pages were likely available.
    CURSOR_REPEAT = "cursor-repeat"  # The server returned a cursor it had already sent.


@dataclass(frozen=True)
class HisterDoc:
    """One history document, reduced to what URL selection uses."""

    url: str
    updated: int | None  # Unix seconds.
    add_count: int | None


@dataclass
class HisterStats:
    """Progress of one ``iter_documents`` call, updated while it is consumed."""

    pages: int = 0
    documents: int = 0  # Documents yielded.
    skipped: int = 0  # Non-web documents, non-http(s) URLs and malformed entries.
    stop_reason: StopReason | None = None  # None until paging ends normally.

    @property
    def cap_hit(self) -> bool:
        return self.stop_reason is StopReason.CAP


def incremental_date_from(last_sync: int | None) -> int | None:
    """The ``date_from`` for a sync after one that started at ``last_sync``.

    Returns None (a full sync) when there was no previous sync.
    """
    if last_sync is None:
        return None
    return max(0, last_sync - SYNC_OVERLAP_SECONDS)


class HisterClient:
    """Pages through ``/api/history`` on one Hister server."""

    def __init__(self, base_url: str, token: str, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        self._base_url = _validated_base_url(base_url)
        self._token = _validated_token(token)
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)):
            raise ValueError("timeout must be a number of seconds")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("timeout must be a positive number of seconds")
        self._timeout = float(timeout)
        self._opener = urllib.request.build_opener(
            # An empty mapping disables proxies from the environment and system
            # settings: over plain http a proxy would see the token header.
            urllib.request.ProxyHandler({}),
            _SameOriginRedirectHandler(),
        )

    @property
    def base_url(self) -> str:
        return self._base_url

    def __repr__(self) -> str:
        return f"HisterClient({self._base_url!r})"

    def iter_documents(
        self,
        date_from: int | None = None,
        max_pages: int = DEFAULT_MAX_PAGES,
        stats: HisterStats | None = None,
    ) -> Iterator[HisterDoc]:
        """Yield web documents, newest first, optionally only those with ``updated >= date_from``.

        Pass ``stats`` to observe progress and why paging stopped. Reaching
        ``max_pages`` or a repeated cursor ends the iteration early; both are logged
        as warnings and recorded in ``stats`` so a partial sync is never mistaken for
        a complete one. Network, HTTP and payload problems raise ``HisterError``.
        """
        # Validated here rather than in the generator, so a bad argument fails at the
        # call instead of at the first iteration.
        if isinstance(max_pages, bool) or not isinstance(max_pages, int) or max_pages < 1:
            raise ValueError("max_pages must be a positive integer")
        if date_from is not None and (
            isinstance(date_from, bool) or not isinstance(date_from, int) or date_from < 0
        ):
            raise ValueError("date_from must be a non-negative unix timestamp or None")
        return self._iterate(date_from, max_pages, stats if stats is not None else HisterStats())

    def _iterate(
        self, date_from: int | None, max_pages: int, stats: HisterStats
    ) -> Iterator[HisterDoc]:
        cursor: str | None = None
        seen_cursors: set[str] = set()
        while True:
            if stats.pages >= max_pages:
                stats.stop_reason = StopReason.CAP
                _log.warning(
                    "Hister paging stopped at the %d page cap; older history was not read",
                    max_pages,
                )
                return

            raw_docs, page_key = self._fetch_page(cursor, date_from)
            stats.pages += 1
            for raw in raw_docs:
                doc = _parse_document(raw)
                if doc is None:
                    stats.skipped += 1
                    continue
                stats.documents += 1
                yield doc

            if len(raw_docs) < PAGE_SIZE:
                stats.stop_reason = StopReason.END
                return

            # A server that cannot parse the cursor ignores it and sends the first page
            # again, which the repeat check below catches.
            next_cursor = page_key if page_key else _cursor_of(raw_docs[-1])
            if next_cursor in seen_cursors:
                stats.stop_reason = StopReason.CURSOR_REPEAT
                # The cursor can be a history URL, so it stays out of the log.
                _log.warning(
                    "Hister repeated a paging cursor after %d pages; stopped to avoid a loop",
                    stats.pages,
                )
                return
            seen_cursors.add(next_cursor)
            cursor = next_cursor

    def _fetch_page(
        self, cursor: str | None, date_from: int | None
    ) -> tuple[list[Any], str | None]:
        """One page of raw documents, and the server's cursor for the next page (None
        when it sends none)."""
        params: dict[str, str] = {}
        if cursor is not None:
            params["last"] = cursor
        if date_from is not None:
            params["date_from"] = str(date_from)
        url = self._base_url + _HISTORY_PATH
        if params:
            url += "?" + urllib.parse.urlencode(params)

        payload = self._get_json(url)
        if not isinstance(payload, dict):
            raise HisterError("Hister history response is not a JSON object")
        page_key = payload.get("page_key")
        if page_key is not None and not isinstance(page_key, str):
            raise HisterError("Hister history response has a non-string 'page_key' field")
        documents = payload.get("documents")
        # Go encodes an empty slice as null.
        if documents is None:
            return [], page_key or None
        if not isinstance(documents, list):
            raise HisterError("Hister history response has a non-list 'documents' field")
        return documents, page_key or None

    def _get_json(self, url: str) -> Any:
        try:
            return self._request_json(url)
        except HisterError as error:
            # Every message passes here: some embed server-influenced text (TLS and
            # socket errors), and the server knows the token.
            raise self._redacted(error) from None

    def _request_json(self, url: str) -> Any:
        request = urllib.request.Request(url, headers=_REQUEST_HEADERS, method="GET")
        request.add_header(_TOKEN_HEADER, self._token)
        # Messages name the server by origin only: the request URL can carry a
        # history URL as the paging cursor.
        server = _origin_text(self._base_url)
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                body = _read_capped(response)
        except urllib.error.HTTPError as exc:
            status = exc.code
            exc.close()
            raise self._http_error(status, server) from None
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, TimeoutError):
                raise self._timeout_error(server) from None
            raise HisterError(
                f"Could not reach Hister at {server}: {_describe(exc.reason)}"
            ) from None
        except TimeoutError:
            raise self._timeout_error(server) from None
        except (OSError, http.client.HTTPException) as exc:
            raise HisterError(
                f"Connection to Hister at {server} failed: {_describe(exc)}"
            ) from None

        try:
            return json.loads(body)
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise HisterError(
                f"Hister at {server} returned a response that is not JSON; "
                "check that HISTER_URL points at the Hister server"
            ) from None

    def _timeout_error(self, server: str) -> HisterError:
        return HisterError(f"Hister at {server} did not answer within {self._timeout:g} s")

    def _http_error(self, status: int, server: str) -> HisterError:
        if status in (401, 403):
            return HisterAuthError(
                f"Hister at {server} rejected the access token (HTTP {status}); check HISTER_TOKEN"
            )
        if status == 404:
            return HisterUnavailable(
                f"Hister at {server} has no history API (HTTP 404): history is disabled "
                "on the server, or HISTER_URL is not a Hister server"
            )
        if 300 <= status < 400:
            return HisterError(
                f"Hister at {server} answered with a redirect (HTTP {status}) that was not followed"
            )
        return HisterError(f"Hister at {server} answered HTTP {status}")

    def _redacted(self, error: HisterError) -> HisterError:
        message = str(error)
        # Case-insensitive: hosts in messages are lowercased.
        redacted = re.sub(re.escape(self._token), "[redacted]", message, flags=re.IGNORECASE)
        if redacted == message:
            return error
        return type(error)(redacted)


class _SameOriginRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follows redirects only within the configured origin.

    urllib copies request headers, including the access token, onto the redirected
    request, so a redirect to another host would hand that host the token.
    """

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> urllib.request.Request | None:
        target = _origin(newurl)
        if target is None or target != _origin(req.full_url):
            fp.close()
            # The target is not named: the server chose it and knows the token.
            raise HisterError(
                f"Hister at {_origin_text(req.full_url)} redirected to another origin; "
                "the token is not sent there. Set HISTER_URL to the server's final address"
            )
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        # urllib then drains the redirect body with an unbounded read(), which would
        # bypass the response cap; on a closed response that read returns nothing.
        fp.close()
        return redirected

    def http_error_302(
        self, req: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any
    ) -> Any:
        try:
            return super().http_error_302(req, fp, code, msg, headers)
        except ValueError:
            # urllib parses the Location header before redirect_request sees it.
            fp.close()
            raise HisterError(
                f"Hister at {_origin_text(req.full_url)} sent a malformed redirect"
            ) from None

    # The base class aliases these to its own http_error_302.
    http_error_301 = http_error_303 = http_error_307 = http_error_308 = http_error_302


def _validated_base_url(base_url: str) -> str:
    # Messages do not echo the URL: a misconfigured one may contain credentials.
    if not isinstance(base_url, str) or not base_url.strip():
        raise HisterConfigError("HISTER_URL is empty")
    base_url = base_url.strip()
    if any(char.isspace() or not char.isprintable() for char in base_url):
        raise HisterConfigError("HISTER_URL contains whitespace or control characters")
    try:
        parts = urllib.parse.urlsplit(base_url)
        _ = parts.port  # Raises ValueError for a non-numeric or out-of-range port.
    except ValueError:
        raise HisterConfigError("HISTER_URL is not a valid URL") from None
    if parts.scheme.lower() not in _ALLOWED_SCHEMES:
        raise HisterConfigError("HISTER_URL must start with http:// or https://")
    if parts.username is not None or parts.password is not None or "@" in parts.netloc:
        raise HisterConfigError(
            "HISTER_URL must not contain a user name or password; put the token in HISTER_TOKEN"
        )
    if not parts.hostname:
        raise HisterConfigError("HISTER_URL has no host")
    if parts.query or parts.fragment or "?" in base_url or "#" in base_url:
        raise HisterConfigError("HISTER_URL must not contain a query or fragment")

    return urllib.parse.urlunsplit(
        (parts.scheme.lower(), parts.netloc.lower(), parts.path.rstrip("/"), "", "")
    )


def _validated_token(token: str) -> str:
    if not isinstance(token, str) or not token.strip():
        raise HisterConfigError("HISTER_TOKEN is empty")
    # A line break would split the header and inject others into the request.
    if any(char in token for char in "\r\n\0"):
        raise HisterConfigError("HISTER_TOKEN contains a line break or NUL character")
    token = token.strip()
    # http.client sends header values as Latin-1 and fails on other characters with
    # an error that holds the token; control characters do not belong in a token.
    if not (token.isascii() and token.isprintable()):
        raise HisterConfigError(
            "HISTER_TOKEN must be printable ASCII; check it for typographic quotes or "
            "other characters added when it was pasted"
        )
    return token


def _origin(url: str) -> tuple[str, str, int | None] | None:
    """(scheme, host, port) with default ports filled in; None if the URL does not parse."""
    try:
        parts = urllib.parse.urlsplit(url)
        port = parts.port
    except ValueError:
        return None
    scheme = parts.scheme.lower()
    if port is None:
        port = DEFAULT_PORTS.get(scheme)  # An explicit ":0" stays 0, another origin.
    return scheme, (parts.hostname or "").lower(), port


def _origin_text(url: str) -> str:
    origin = _origin(url)
    if origin is None:
        return "an unparseable address"
    scheme, host, port = origin
    if ":" in host:
        host = f"[{host}]"
    if port is None or port == DEFAULT_PORTS.get(scheme):
        return f"{scheme}://{host}"
    return f"{scheme}://{host}:{port}"


def _describe(reason: object) -> str:
    """Text for a low-level failure; some exceptions stringify to nothing."""
    return str(reason) or type(reason).__name__


def _read_capped(response: Any) -> bytes:
    declared = _declared_length(response)
    if declared is not None and declared > _MAX_RESPONSE_BYTES:
        raise HisterError(
            f"Hister response of {declared} bytes exceeds the {_MAX_RESPONSE_BYTES} byte limit"
        )

    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = response.read(_READ_CHUNK_BYTES)
        if not chunk:
            return b"".join(chunks)
        total += len(chunk)
        if total > _MAX_RESPONSE_BYTES:
            raise HisterError(f"Hister response exceeds the {_MAX_RESPONSE_BYTES} byte limit")
        chunks.append(chunk)


def _declared_length(response: Any) -> int | None:
    value = response.headers.get("Content-Length")
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None  # Unusable; the chunked read still enforces the cap.


def _cursor_of(raw: Any) -> str:
    url = raw.get("url") if isinstance(raw, dict) else None
    if not isinstance(url, str) or not url:
        raise HisterError("Hister returned a full page whose last document has no URL to page from")
    return url


def _parse_document(raw: Any) -> HisterDoc | None:
    """Reduce one raw document to a HisterDoc, or None if it is not a web page."""
    if not isinstance(raw, dict):
        return None
    if not _is_web_type(raw.get("type")):
        return None
    url = raw.get("url")
    if not isinstance(url, str):
        return None
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return None
    if parts.scheme.lower() not in _ALLOWED_SCHEMES or not parts.hostname:
        return None

    return HisterDoc(
        url=url,
        updated=_unix_seconds(raw.get("updated")),
        add_count=_count(raw.get("add_count")),
    )


def _is_web_type(value: Any) -> bool:
    if value is None:
        return True
    # bool is an int, and False == 0 would pass the set lookup; lists are unhashable.
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        return False
    return value in _WEB_DOC_TYPES


def _unix_seconds(value: Any) -> int | None:
    """Accept unix seconds or an RFC 3339 string; anything else, or Go's zero time, is None."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        seconds = value
    elif isinstance(value, float):
        seconds = int(value) if math.isfinite(value) else 0
    elif isinstance(value, str):
        seconds = _parse_time_text(value.strip())
    else:
        return None
    return seconds if 0 < seconds <= _MAX_UNIX_SECONDS else None


def _parse_time_text(text: str) -> int:
    """Seconds from digits or RFC 3339 text; 0 when unusable."""
    try:
        if text.isascii() and text.isdigit():
            return int(text)
        parsed = datetime.fromisoformat(text)
    except ValueError:  # Also raised for digit strings too long to convert.
        return 0
    if parsed.tzinfo is None:
        return 0  # Ambiguous without a zone; Hister always sends one.
    return int(parsed.timestamp())


def _count(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if 0 <= value <= _MAX_ADD_COUNT else None
