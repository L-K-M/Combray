"""Tests for the Hister client against a fake Hister on 127.0.0.1."""

from __future__ import annotations

import email.message
import io
import json
import socket
import threading
import time
import unittest
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, fields
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from unittest import mock

from sitecheck import hister
from sitecheck.hister import (
    PAGE_SIZE,
    HisterAuthError,
    HisterClient,
    HisterConfigError,
    HisterDoc,
    HisterError,
    HisterStats,
    HisterUnavailable,
    StopReason,
    incremental_date_from,
)

TOKEN = "tok-3f9a8c1e-very-secret-value"

Response = tuple[int, dict[str, str], bytes]


@dataclass
class Recorded:
    path: str
    query: dict[str, list[str]]
    headers: dict[str, str]  # Lowercased names.


class FakeServer:
    """A threaded HTTP server that records requests and answers through ``responder``."""

    def __init__(self, responder: Callable[[Recorded], Response], hold_open: float = 0.0) -> None:
        self.requests: list[Recorded] = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                parts = urllib.parse.urlsplit(self.path)
                recorded = Recorded(
                    path=parts.path,
                    query=urllib.parse.parse_qs(parts.query, keep_blank_values=True),
                    headers={name.lower(): value for name, value in self.headers.items()},
                )
                server.requests.append(recorded)
                status, headers, body = responder(recorded)
                try:
                    self.send_response(status)
                    for name, value in headers.items():
                        self.send_header(name, value)
                    self.end_headers()
                    self.wfile.write(body)
                    if hold_open and status != 200:
                        self.wfile.flush()
                        time.sleep(hold_open)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Tests that refuse a response close the connection early.

            def log_message(self, format: str, *args: Any) -> None:
                pass

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._httpd.daemon_threads = True
        self._thread = threading.Thread(
            target=self._httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True
        )
        self._thread.start()

    @property
    def port(self) -> int:
        return self._httpd.server_address[1]

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def close(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()
        self._thread.join()


def json_response(payload: Any, status: int = 200) -> Response:
    body = json.dumps(payload).encode()
    return status, {"Content-Type": "application/json", "Content-Length": str(len(body))}, body


def make_docs(prefix: str, count: int) -> list[dict[str, Any]]:
    return [
        {
            "url": f"https://site{i}.example.org/{prefix}/{i}",
            "domain": f"site{i}.example.org",
            "title": "A title that must not be kept",
            "html": "<p>page html</p>",
            "text": "page text",
            "added": 1_760_000_000 + i,
            "updated": 1_760_100_000 + i,
            "type": 0,
            "add_count": i + 1,
        }
        for i in range(count)
    ]


def paged(pages: dict[str | None, list[dict[str, Any]] | None]) -> Callable[[Recorded], Response]:
    """Serve ``pages`` keyed by the ``last`` cursor (None for the first page)."""

    def respond(request: Recorded) -> Response:
        cursor = request.query.get("last", [None])[0]
        if cursor not in pages:
            return json_response({"error": "unexpected cursor"}, status=500)
        return json_response({"documents": pages[cursor]})

    return respond


class HisterTestCase(unittest.TestCase):
    def serve(
        self, responder: Callable[[Recorded], Response], hold_open: float = 0.0
    ) -> FakeServer:
        server = FakeServer(responder, hold_open)
        self.addCleanup(server.close)
        return server

    def client(self, server: FakeServer, timeout: float = 5) -> HisterClient:
        return HisterClient(server.base_url, TOKEN, timeout=timeout)

    def assert_token_absent(self, error: BaseException) -> None:
        # Case-insensitive: a server can echo the token in a host name, which is
        # lowercased or uppercased on the way.
        current: BaseException | None = error
        while current is not None:
            self.assertNotIn(TOKEN.lower(), str(current).lower())
            self.assertNotIn(TOKEN.lower(), repr(current).lower())
            current = current.__cause__ or current.__context__

    def fetch_error(self, client: HisterClient) -> HisterError:
        with self.assertRaises(HisterError) as caught:
            list(client.iter_documents())
        self.assert_token_absent(caught.exception)
        return caught.exception


class PagingTests(HisterTestCase):
    def test_sends_auth_headers_and_follows_cursor_until_short_page(self) -> None:
        first, second = make_docs("a", PAGE_SIZE), make_docs("b", PAGE_SIZE)
        third = make_docs("c", 3)
        server = self.serve(
            paged({None: first, first[-1]["url"]: second, second[-1]["url"]: third})
        )
        stats = HisterStats()

        docs = list(self.client(server).iter_documents(stats=stats))

        self.assertEqual(len(docs), 2 * PAGE_SIZE + 3)
        self.assertEqual(stats.pages, 3)
        self.assertEqual(stats.documents, len(docs))
        self.assertEqual(stats.stop_reason, StopReason.END)
        self.assertEqual(stats.stop_reason, StopReason.END)
        self.assertFalse(stats.cap_hit)
        self.assertEqual(len(server.requests), 3)
        self.assertNotIn("last", server.requests[0].query)
        self.assertEqual(server.requests[1].query["last"], [first[-1]["url"]])
        self.assertEqual(server.requests[2].query["last"], [second[-1]["url"]])
        for request in server.requests:
            self.assertEqual(request.path, "/api/history")
            self.assertEqual(request.headers["x-access-token"], TOKEN)
            self.assertEqual(request.headers["origin"], "hister://")
            self.assertEqual(request.headers["accept"], "application/json")
            self.assertTrue(request.headers["user-agent"].startswith("combray-sitecheck/"))
            self.assertNotIn("date_from", request.query)

    def test_documents_keep_only_selection_fields(self) -> None:
        server = self.serve(paged({None: make_docs("a", 1)}))

        (doc,) = self.client(server).iter_documents()

        self.assertEqual(
            {field.name for field in fields(HisterDoc)},
            {"url", "updated", "add_count"},
        )
        self.assertEqual(
            doc,
            HisterDoc(url="https://site0.example.org/a/0", updated=1_760_100_000, add_count=1),
        )

    def test_stops_on_empty_page(self) -> None:
        first = make_docs("a", PAGE_SIZE)
        server = self.serve(paged({None: first, first[-1]["url"]: []}))
        stats = HisterStats()

        docs = list(self.client(server).iter_documents(stats=stats))

        self.assertEqual(len(docs), PAGE_SIZE)
        self.assertEqual(stats.pages, 2)
        self.assertEqual(stats.stop_reason, StopReason.END)

    def test_null_documents_is_an_empty_page(self) -> None:
        server = self.serve(paged({None: None}))
        stats = HisterStats()

        self.assertEqual(list(self.client(server).iter_documents(stats=stats)), [])
        self.assertEqual(stats.pages, 1)
        self.assertEqual(stats.stop_reason, StopReason.END)

    def test_repeated_cursor_stops_paging(self) -> None:
        same_page = make_docs("loop", PAGE_SIZE)
        server = self.serve(lambda request: json_response({"documents": same_page}))
        stats = HisterStats()

        with self.assertLogs("sitecheck.hister", level="WARNING") as logs:
            docs = list(self.client(server).iter_documents(stats=stats))

        self.assertEqual(stats.stop_reason, StopReason.CURSOR_REPEAT)
        self.assertNotEqual(stats.stop_reason, StopReason.END)
        self.assertEqual(stats.pages, 2)
        self.assertEqual(len(docs), 2 * PAGE_SIZE)
        self.assertEqual(len(server.requests), 2)
        # The cursor is a history URL and must not reach the log.
        self.assertNotIn("example.org", "\n".join(logs.output))

    def test_max_pages_cap_is_reported(self) -> None:
        def endless(request: Recorded) -> Response:
            return json_response({"documents": make_docs(f"p{len(server.requests)}", PAGE_SIZE)})

        server = self.serve(endless)
        stats = HisterStats()

        with self.assertLogs("sitecheck.hister", level="WARNING") as logs:
            docs = list(self.client(server).iter_documents(max_pages=3, stats=stats))

        self.assertEqual(len(docs), 3 * PAGE_SIZE)
        self.assertEqual(stats.pages, 3)
        self.assertTrue(stats.cap_hit)
        self.assertEqual(stats.stop_reason, StopReason.CAP)
        self.assertEqual(len(server.requests), 3)
        self.assertIn("cap", "\n".join(logs.output))
        self.assertEqual((stats.documents, stats.skipped), (3 * PAGE_SIZE, 0))

    def test_short_last_page_at_cap_is_complete(self) -> None:
        server = self.serve(paged({None: make_docs("a", 5)}))
        stats = HisterStats()

        list(self.client(server).iter_documents(max_pages=1, stats=stats))

        self.assertFalse(stats.cap_hit)
        self.assertEqual(stats.stop_reason, StopReason.END)

    def test_date_from_is_sent_on_every_page(self) -> None:
        first = make_docs("a", PAGE_SIZE)
        server = self.serve(paged({None: first, first[-1]["url"]: make_docs("b", 1)}))

        list(self.client(server).iter_documents(date_from=1_760_000_000))

        self.assertEqual(len(server.requests), 2)
        for request in server.requests:
            self.assertEqual(request.query["date_from"], ["1760000000"])

    def test_token_is_sent_without_surrounding_whitespace(self) -> None:
        server = self.serve(paged({None: []}))

        list(HisterClient(server.base_url, f" \t{TOKEN} ").iter_documents())

        self.assertEqual(server.requests[0].headers["x-access-token"], TOKEN)

    def test_base_path_prefix_is_kept(self) -> None:
        server = self.serve(paged({None: []}))

        list(HisterClient(server.base_url + "/hister/", TOKEN).iter_documents())

        self.assertEqual(server.requests[0].path, "/hister/api/history")

    def test_filters_non_web_and_malformed_documents(self) -> None:
        documents: list[Any] = [
            {
                "url": "https://kept.example.org/web",
                "type": "web",
                "updated": "2026-10-24T12:00:00Z",
            },
            {"url": "https://kept.example.org/untyped", "added": "1760000000", "add_count": -1},
            {"url": "https://kept.example.org/zero-time", "updated": "0001-01-01T00:00:00Z"},
            {"url": "https://skipped.example.org/file", "type": 1},
            {"url": "file:///home/someone/notes.txt", "type": 0},
            {"url": "javascript:alert(1)"},
            {"domain": "no-url.example.org"},
            "not an object",
        ]
        server = self.serve(paged({None: documents}))
        stats = HisterStats()

        docs = list(self.client(server).iter_documents(stats=stats))

        self.assertEqual(
            [doc.url for doc in docs],
            [
                "https://kept.example.org/web",
                "https://kept.example.org/untyped",
                "https://kept.example.org/zero-time",
            ],
        )
        self.assertEqual(stats.skipped, 5)
        web, untyped, zero_time = docs
        self.assertEqual(web.updated, 1_792_843_200)
        self.assertIsNone(untyped.add_count)
        self.assertIsNone(zero_time.updated)

    def test_full_page_without_cursor_is_an_error(self) -> None:
        documents = make_docs("a", PAGE_SIZE)
        documents[-1] = {"type": "web"}
        server = self.serve(paged({None: documents}))

        error = self.fetch_error(self.client(server))

        self.assertIn("no URL", str(error))

    def test_arguments_are_checked_at_call_time(self) -> None:
        client = HisterClient("http://127.0.0.1:9", TOKEN)
        with self.assertRaises(ValueError):
            client.iter_documents(max_pages=0)
        with self.assertRaises(ValueError):
            client.iter_documents(date_from=-1)
        with self.assertRaises(ValueError):
            HisterClient("http://127.0.0.1:9", TOKEN, timeout=0)


class ErrorTests(HisterTestCase):
    def test_auth_failures(self) -> None:
        for status in (401, 403):
            with self.subTest(status=status):
                server = self.serve(
                    lambda request, status=status: json_response({"error": TOKEN}, status=status)
                )
                error = self.fetch_error(self.client(server))
                self.assertIsInstance(error, HisterAuthError)

    def test_404_means_history_is_unavailable(self) -> None:
        server = self.serve(lambda request: json_response({"error": "not found"}, status=404))

        error = self.fetch_error(self.client(server))

        self.assertIsInstance(error, HisterUnavailable)
        self.assertIn("disabled", str(error))

    def test_server_error(self) -> None:
        server = self.serve(lambda request: json_response({"error": "boom"}, status=500))

        error = self.fetch_error(self.client(server))

        self.assertNotIsInstance(error, (HisterAuthError, HisterUnavailable))
        self.assertIn("500", str(error))

    def test_invalid_payloads(self) -> None:
        cases: dict[str, Response] = {
            "not json": (200, {}, b"<html>login</html>"),
            "not an object": json_response([1, 2]),
            "documents not a list": json_response({"documents": {"url": "x"}}),
            "bad utf-8": (200, {}, b"\xff\xfe\xfa"),
        }
        for name, response in cases.items():
            with self.subTest(name):
                server = self.serve(lambda request, response=response: response)
                error = self.fetch_error(self.client(server))
                self.assertEqual(type(error), HisterError)

    def test_oversized_response_is_refused(self) -> None:
        body = json.dumps({"documents": make_docs("big", 50)}).encode()
        variants = {
            "declared length": {"Content-Length": str(len(body))},
            "undeclared length": {},
        }
        for name, headers in variants.items():
            with self.subTest(name), mock.patch.object(hister, "_MAX_RESPONSE_BYTES", 1024):
                server = self.serve(lambda request, headers=headers: (200, headers, body))
                error = self.fetch_error(self.client(server))
                self.assertIn("exceeds", str(error))

    def test_timeout(self) -> None:
        def slow(request: Recorded) -> Response:
            time.sleep(1.0)
            return json_response({"documents": []})

        server = self.serve(slow)

        error = self.fetch_error(self.client(server, timeout=0.2))

        self.assertIn("did not answer", str(error))

    def test_connection_refused(self) -> None:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]

        error = self.fetch_error(HisterClient(f"http://127.0.0.1:{port}", TOKEN, timeout=2))

        self.assertIn("Could not reach", str(error))

    def test_cross_origin_redirect_is_refused(self) -> None:
        target = self.serve(lambda request: json_response({"documents": []}))
        locations = {
            "other host": f"http://localhost:{target.port}/api/history",
            "other port": f"http://127.0.0.1:{target.port}/api/history",
            "other scheme": "https://127.0.0.1/api/history",
            "unparseable port": "http://127.0.0.1:99999/api/history",
        }
        for name, location in locations.items():
            with self.subTest(name):
                origin = self.serve(
                    lambda request, location=location: (302, {"Location": location}, b"")
                )

                error = self.fetch_error(self.client(origin))

                self.assertIn("another origin", str(error))
                self.assertEqual(len(origin.requests), 1)
        self.assertEqual(target.requests, [])

    def test_same_origin_redirect_keeps_the_token(self) -> None:
        def respond(request: Recorded) -> Response:
            if request.path == "/api/history":
                return 301, {"Location": "/moved/api/history"}, b""
            return json_response({"documents": make_docs("moved", 1)})

        server = self.serve(respond)

        docs = list(self.client(server).iter_documents())

        self.assertEqual(len(docs), 1)
        self.assertEqual([r.path for r in server.requests], ["/api/history", "/moved/api/history"])
        self.assertEqual(server.requests[1].headers["x-access-token"], TOKEN)

    def test_redirect_reflecting_the_token_is_not_echoed(self) -> None:
        # The server has the token, so it can put it in a redirect target; the
        # error must still not carry it into local logs.
        for host in (f"{TOKEN}.example.org", f"{TOKEN.upper()}.example.org"):
            with self.subTest(host):
                server = self.serve(
                    lambda request, host=host: (302, {"Location": f"http://{host}/"}, b"")
                )

                self.fetch_error(self.client(server))

    def test_redirect_to_an_explicit_port_zero_is_another_origin(self) -> None:
        # Port 0 is not the scheme's default port. Checked on the handler directly:
        # the fake server cannot listen on port 80, and nothing is sent.
        request = urllib.request.Request("http://hister.example.org/api/history")
        request.add_header("X-access-token", TOKEN)
        handler = hister._SameOriginRedirectHandler()

        with self.assertRaisesRegex(HisterError, "another origin") as caught:
            handler.redirect_request(
                request,
                io.BytesIO(),
                302,
                "Found",
                email.message.Message(),
                "http://hister.example.org:0/api/history",
            )
        self.assert_token_absent(caught.exception)

    def test_malformed_redirect_is_an_error(self) -> None:
        for status in (301, 302, 303, 307, 308):
            with self.subTest(status=status):
                server = self.serve(
                    lambda request, status=status: (status, {"Location": "http://["}, b"")
                )

                error = self.fetch_error(self.client(server))

                self.assertIn("malformed", str(error))

    def test_redirect_body_is_not_read(self) -> None:
        # A redirect that declares a huge body and never sends it: reading the
        # body would block until the timeout (and, if sent, bypass the size cap).
        def respond(request: Recorded) -> Response:
            if request.path == "/api/history":
                headers = {"Location": "/moved/api/history", "Content-Length": str(1 << 40)}
                return 302, headers, b"partial"
            return json_response({"documents": make_docs("moved", 1)})

        server = self.serve(respond, hold_open=2.0)

        docs = list(self.client(server, timeout=1).iter_documents())

        self.assertEqual(len(docs), 1)

    def test_hostile_payloads_do_not_escape_as_other_exceptions(self) -> None:
        nested = (200, {}, b"[" * 200_000 + b"]" * 200_000)
        server = self.serve(lambda request: nested)
        self.fetch_error(self.client(server))

        documents = [
            {"url": "https://a.example.org/1", "type": []},
            {"url": "https://a.example.org/2", "type": False},
            {
                "url": "https://a.example.org/3",
                "updated": 10**400,
                "added": "9" * 5000,
                "add_count": 10**30,
            },
        ]
        body = json.dumps({"documents": documents}).encode()
        server = self.serve(lambda request: (200, {"Content-Length": "9" * 5000}, body))
        stats = HisterStats()

        docs = list(self.client(server).iter_documents(stats=stats))

        self.assertEqual(stats.skipped, 2)
        self.assertEqual(
            docs,
            [HisterDoc("https://a.example.org/3", None, None)],
        )

    def test_client_repr_hides_token(self) -> None:
        client = HisterClient("https://hister.example.org", TOKEN)
        self.assertNotIn(TOKEN, repr(client))
        self.assertNotIn(TOKEN, str(client))


class ConfigTests(unittest.TestCase):
    def test_base_url_is_normalized(self) -> None:
        cases = {
            "HTTPS://Hister.Example.ORG/": "https://hister.example.org",
            "http://hister.example.org:8080/prefix/": "http://hister.example.org:8080/prefix",
            "  http://[::1]:4433  ": "http://[::1]:4433",
        }
        for given, expected in cases.items():
            with self.subTest(given):
                self.assertEqual(HisterClient(given, TOKEN).base_url, expected)

    def test_invalid_base_urls_are_rejected_without_echoing_them(self) -> None:
        secret = "pw-0d6c2b"
        invalid = [
            "",
            "   ",
            "ftp://hister.example.org",
            "javascript:alert(1)",
            "hister.example.org",
            f"http://user:{secret}@hister.example.org",
            f"http://{secret}@hister.example.org",
            f"http://hister.example.org/?token={secret}",
            "http://hister.example.org/?",
            f"http://hister.example.org/#{secret}",
            "http:///no-host",
            "http://hister.example.org:99999",
            "http://hister.example.org/a path",
        ]
        for base_url in invalid:
            with self.subTest(base_url):
                with self.assertRaises(HisterConfigError) as caught:
                    HisterClient(base_url, TOKEN)
                self.assertNotIn(secret, str(caught.exception))
                self.assertNotIn(TOKEN, str(caught.exception))

    def test_invalid_tokens_are_rejected_without_echoing_them(self) -> None:
        for token in ("", "  ", f"{TOKEN}\r\nX-Injected: 1", f"{TOKEN}\0"):
            with self.subTest(repr(token)):
                with self.assertRaises(HisterConfigError) as caught:
                    HisterClient("https://hister.example.org", token)
                self.assertNotIn(TOKEN, str(caught.exception))

    def test_tokens_that_are_not_printable_ascii_are_rejected_without_echoing_them(self) -> None:
        # http.client sends header values as Latin-1: anything else would fail later
        # with an error that holds the token.
        for token in (
            f"{TOKEN}\u20ac",
            f"\u201c{TOKEN}\u201d",
            f"{TOKEN}\x1fx",
            f"{TOKEN}\x7f",
            f"{TOKEN}\tx",
        ):
            with self.subTest(repr(token)):
                with self.assertRaisesRegex(HisterConfigError, "printable ASCII") as caught:
                    HisterClient("https://hister.example.org", token)
                self.assertNotIn(TOKEN, str(caught.exception))
                self.assertNotIn(TOKEN, repr(caught.exception))

    def test_incremental_date_from_overlaps_by_a_day(self) -> None:
        self.assertIsNone(incremental_date_from(None))
        self.assertEqual(incremental_date_from(1_760_086_400), 1_760_000_000)
        self.assertEqual(incremental_date_from(10), 0)


if __name__ == "__main__":
    unittest.main()
