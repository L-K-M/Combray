"""Tests for sitecheck.webdriver against a local fake WebDriver server (no network)."""

from __future__ import annotations

import base64
import json
import os
import socket
import threading
import time
import unittest
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from unittest import mock

from sitecheck import webdriver
from sitecheck.webdriver import (
    ERROR_MALFORMED_RESPONSE,
    WebDriverClient,
    WebDriverConnectionError,
    WebDriverError,
    WebDriverTimeout,
)

PNG = b"\x89PNG\r\n\x1a\n" + b"rest of a png"
SESSION = "session-1"
TIMEOUT = 5.0


@dataclass(frozen=True)
class Reply:
    status: int = 200
    body: bytes = b'{"value": null}'
    delay: float = 0.0
    drop_connection: bool = False  # Close the socket without any response.


def value(data: Any, status: int = 200) -> Reply:
    return Reply(status=status, body=json.dumps({"value": data}).encode())


@dataclass(frozen=True)
class Request:
    method: str
    path: str
    body: Any
    content_type: str | None


class _Handler(BaseHTTPRequestHandler):
    server: _FakeServer

    def log_message(self, format: str, *args: object) -> None:
        pass

    def _handle(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        request = Request(
            self.command,
            self.path,
            json.loads(raw) if raw else None,
            self.headers.get("Content-Type"),
        )
        self.server.requests.append(request)

        reply = self.server.responder(request)
        time.sleep(reply.delay)
        if reply.drop_connection:
            self.close_connection = True
            return
        try:
            self.send_response(reply.status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(reply.body)))
            self.end_headers()
            self.wfile.write(reply.body)
        except ConnectionError:
            pass  # The client gave up (it timed out, say); nobody is left to answer.

    do_GET = do_POST = do_DELETE = _handle


class _FakeServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.requests: list[Request] = []
        self.responder: Callable[[Request], Reply] = lambda request: Reply()


class WebDriverClientTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = _FakeServer()
        # A short poll interval keeps shutdown() quick between tests.
        thread = threading.Thread(target=self.server.serve_forever, args=(0.05,), daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.client = WebDriverClient(f"http://127.0.0.1:{self.server.server_port}")

    def respond(self, responder: Callable[[Request], Reply]) -> None:
        self.server.responder = responder

    def open_session(self) -> None:
        self.respond(
            lambda request: value({"sessionId": SESSION, "capabilities": {"browserName": "servo"}})
        )
        self.client.new_session({}, timeout=TIMEOUT)

    def test_status(self) -> None:
        self.respond(lambda request: value({"ready": True, "message": "Ready for a new session"}))

        status = self.client.status(timeout=TIMEOUT)

        self.assertEqual(status, {"ready": True, "message": "Ready for a new session"})
        self.assertEqual(
            self.server.requests,
            [Request("GET", "/status", None, "application/json; charset=utf-8")],
        )

    def test_session_commands(self) -> None:
        self.respond(
            lambda request: value({"sessionId": SESSION, "capabilities": {"browserName": "servo"}})
        )
        granted = self.client.new_session({"pageLoadStrategy": "normal"}, timeout=TIMEOUT)
        self.assertEqual(granted, {"browserName": "servo"})
        self.assertEqual(self.client.session_id, SESSION)

        self.respond(
            lambda request: value(
                {"answer": 42} if request.path.endswith("/execute/sync") else None
            )
        )
        self.client.navigate("https://example.org/", timeout=TIMEOUT)
        result = self.client.execute("return arguments[0];", [1], timeout=TIMEOUT)
        self.client.delete_session(timeout=TIMEOUT)

        self.assertEqual(result, {"answer": 42})
        self.assertIsNone(self.client.session_id)
        self.assertEqual(
            [(request.method, request.path, request.body) for request in self.server.requests],
            [
                (
                    "POST",
                    "/session",
                    {"capabilities": {"alwaysMatch": {"pageLoadStrategy": "normal"}}},
                ),
                ("POST", f"/session/{SESSION}/url", {"url": "https://example.org/"}),
                (
                    "POST",
                    f"/session/{SESSION}/execute/sync",
                    {"script": "return arguments[0];", "args": [1]},
                ),
                ("DELETE", f"/session/{SESSION}", None),
            ],
        )

    def test_session_commands_need_exactly_one_session(self) -> None:
        with self.assertRaises(RuntimeError):
            self.client.navigate("https://example.org/", timeout=TIMEOUT)

        self.open_session()
        with self.assertRaises(RuntimeError):
            self.client.new_session({}, timeout=TIMEOUT)

    def test_new_session_without_id_is_malformed(self) -> None:
        self.respond(lambda request: value({"capabilities": {}}))

        with self.assertRaises(WebDriverError) as raised:
            self.client.new_session({}, timeout=TIMEOUT)

        self.assertEqual(raised.exception.error, ERROR_MALFORMED_RESPONSE)
        self.assertIsNone(self.client.session_id)

    def test_screenshot_is_decoded(self) -> None:
        self.open_session()
        self.respond(lambda request: value(base64.b64encode(PNG).decode()))

        self.assertEqual(self.client.screenshot(timeout=TIMEOUT), PNG)
        self.assertEqual(self.server.requests[-1].path, f"/session/{SESSION}/screenshot")

    def test_screenshot_must_be_base64_png(self) -> None:
        self.open_session()
        for data in ("not base64!", base64.b64encode(b"GIF89a...").decode(), None):
            with self.subTest(data=data):
                self.respond(lambda request, data=data: value(data))
                with self.assertRaises(WebDriverError) as raised:
                    self.client.screenshot(timeout=TIMEOUT)
                self.assertEqual(raised.exception.error, ERROR_MALFORMED_RESPONSE)

    def test_w3c_error_status(self) -> None:
        self.open_session()
        error = {"error": "javascript error", "message": "boom", "stacktrace": "@:2:23"}
        self.respond(lambda request: value(error, status=500))

        with self.assertRaises(WebDriverError) as raised:
            self.client.execute("throw new Error('boom')", timeout=TIMEOUT)

        self.assertEqual(raised.exception.error, "javascript error")
        self.assertEqual(raised.exception.message, "boom")
        self.assertEqual(raised.exception.http_status, 500)

    def test_w3c_error_in_a_200_response(self) -> None:
        self.open_session()
        self.respond(lambda request: value({"error": "timeout", "message": "page load"}))

        with self.assertRaises(WebDriverError) as raised:
            self.client.navigate("https://example.org/", timeout=TIMEOUT)

        self.assertEqual(raised.exception.error, "timeout")

    def test_error_messages_are_truncated(self) -> None:
        self.respond(
            lambda request: value({"error": "unknown error", "message": "x" * 5000}, status=500)
        )

        with self.assertRaises(WebDriverError) as raised:
            self.client.status(timeout=TIMEOUT)

        self.assertEqual(len(raised.exception.message), 1000)

    def test_unreadable_responses_are_malformed(self) -> None:
        cases = {
            "not json": Reply(body=b"<html>hi</html>"),
            "no value": Reply(body=b'{"status": 0}'),
            "error status without w3c error": Reply(status=502, body=b'{"value": "bad gateway"}'),
            "not utf-8": Reply(body=b"\xff\xfe"),
        }
        for name, reply in cases.items():
            with self.subTest(name):
                self.respond(lambda request, reply=reply: reply)
                with self.assertRaises(WebDriverError) as raised:
                    self.client.status(timeout=TIMEOUT)
                self.assertEqual(raised.exception.error, ERROR_MALFORMED_RESPONSE)

    def test_oversized_response_is_rejected(self) -> None:
        self.respond(lambda request: value("x" * 1000))

        with (
            mock.patch.object(webdriver, "_MAX_RESPONSE_BYTES", 100),
            self.assertRaises(WebDriverError) as raised,
        ):
            self.client.status(timeout=TIMEOUT)

        self.assertEqual(raised.exception.error, ERROR_MALFORMED_RESPONSE)

    def test_slow_answer_times_out(self) -> None:
        self.respond(lambda request: Reply(delay=1.0, body=b'{"value": {}}'))

        with self.assertRaises(WebDriverTimeout):
            self.client.status(timeout=0.2)

    def test_dropped_connection(self) -> None:
        self.respond(lambda request: Reply(drop_connection=True))

        with self.assertRaises(WebDriverConnectionError):
            self.client.status(timeout=TIMEOUT)

    def test_refused_connection(self) -> None:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            closed_port = probe.getsockname()[1]
        client = WebDriverClient(f"http://127.0.0.1:{closed_port}")

        with self.assertRaises(WebDriverConnectionError):
            client.status(timeout=TIMEOUT)

    def test_proxy_environment_is_ignored(self) -> None:
        proxy = "http://127.0.0.1:9"  # Nothing listens there; using it would fail.
        environment = {"http_proxy": proxy, "HTTP_PROXY": proxy, "all_proxy": proxy, "no_proxy": ""}
        self.respond(lambda request: value({"ready": True}))

        with mock.patch.dict(os.environ, environment):
            client = WebDriverClient(f"http://127.0.0.1:{self.server.server_port}")
            self.assertEqual(client.status(timeout=TIMEOUT), {"ready": True})

    def test_delete_session_forgets_the_session_even_on_error(self) -> None:
        self.open_session()
        self.respond(
            lambda request: value({"error": "invalid session id", "message": "gone"}, status=404)
        )

        with self.assertRaises(WebDriverError):
            self.client.delete_session(timeout=TIMEOUT)

        self.assertIsNone(self.client.session_id)

    def test_base_url_must_be_http(self) -> None:
        for base_url in ("https://127.0.0.1:4444", "127.0.0.1:4444", "http://"):
            with self.subTest(base_url=base_url), self.assertRaises(ValueError):
                WebDriverClient(base_url)


if __name__ == "__main__":
    unittest.main()
