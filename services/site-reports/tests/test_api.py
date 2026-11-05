"""Tests for site_reports.api: a real server on 127.0.0.1:0 with a temporary store."""

from __future__ import annotations

import contextlib
import hashlib
import http.client
import io
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import unittest
from datetime import timedelta
from typing import Any
from unittest import mock

from site_reports.__main__ import main
from site_reports.api import ServerLimits
from site_reports.config import hash_token
from site_reports.store import Limits
from tests import support

REPORTER = support.REPORTER_TOKEN
AGENT = support.AGENT_TOKEN
PNG = support.png(3, 2)
SECURITY_HEADERS = {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
}
JSON_TYPE = "application/json; charset=utf-8"
UNKNOWN_ID = "0f0e0d0c-0b0a-4908-8706-050403020100"
REPORTS = "/v1/reports"


def report_path(report_id: str, suffix: str = "") -> str:
    return f"{REPORTS}/{report_id}{suffix}"


def body(value: dict[str, Any]) -> bytes:
    return support.encode(value)


def head(method: str, path: str, headers: dict[str, str]) -> bytes:
    lines = [f"{method} {path} HTTP/1.1", "Host: 127.0.0.1"]
    lines += [f"{name}: {value}" for name, value in headers.items()]
    return ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1")


def receive_head(sock: socket.socket) -> bytes:
    """Bytes up to the first blank line: an interim response or a response head."""
    received = b""
    while b"\r\n\r\n" not in received:
        block = sock.recv(1)
        if not block:
            break
        received += block
    return received


class SignallingSemaphore(threading.BoundedSemaphore):
    """A semaphore that tells the test when a handler has taken it."""

    def __init__(self, value: int) -> None:
        super().__init__(value)
        self.taken = threading.Event()

    def acquire(self, blocking: bool = True, timeout: float | None = None) -> bool:
        acquired = super().acquire(blocking, timeout)
        if acquired:
            self.taken.set()
        return acquired


class ApiTestCase(unittest.TestCase):
    server_limits: ServerLimits | None = None
    store_limits = Limits()
    config_overrides: dict[str, Any] = {}  # noqa: RUF012 (read only)

    def setUp(self) -> None:
        self.root = support.temporary_directory(self)
        self.data = self.root / "data"
        self.clock = support.FakeClock()
        self.log = support.capture_logs(self)
        self.store = support.open_store(self, self.data, self.clock, self.store_limits)
        self.config = support.config(self.data, **self.config_overrides)
        self.running = support.start_server(
            self, self.config, self.store, self.clock, self.server_limits
        )
        self.client = support.Client(self.running.port)

    def request(self, method: str, path: str, data: bytes | None = None, **kwargs: Any):
        return self.client.request(method, path, data, **kwargs)

    def create(self, value: dict[str, Any] | None = None, token: str = REPORTER):
        return self.request("POST", REPORTS, body(value or support.request()), token=token)

    def created_id(self, value: dict[str, Any] | None = None) -> str:
        value = value or support.request()
        response = self.create(value)
        self.assertEqual(response.status, 201, response.body)
        return value["id"]

    def set_status(self, report_id: str, value: dict[str, Any], token: str = AGENT):
        return self.request("POST", report_path(report_id, "/status"), body(value), token=token)

    def assert_error(
        self, response: support.Response, status: int, code: str, field: str | None = None
    ) -> None:
        self.assertEqual(response.status, status, response.body)
        expected: dict[str, Any] = {"error": code}
        if field is not None:
            expected["field"] = field
        self.assertEqual(response.json(), expected)
        self.assertEqual(response.headers["Content-Type"], JSON_TYPE)

    def hold_upload(self, path: str = REPORTS, token: str = REPORTER):
        """A connection whose handler waits for the rest of its body."""
        sock = self.client.connect(self)
        sock.sendall(
            head(
                "POST",
                path,
                {
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                    "Content-Length": "1000",
                },
            )
            + b"{"
        )
        return sock

    def hold_slot(self, name: str) -> socket.socket:
        """Hold the server's only request or upload slot with an unfinished upload."""
        slots = SignallingSemaphore(1)
        setattr(self.running.server, name, slots)
        held = self.hold_upload()
        self.assertTrue(slots.taken.wait(support.WAIT_SECONDS))
        return held

    def assert_answered_before_the_body(
        self, request: bytes, status: int, code: str
    ) -> support.Response:
        """Send headers only: the answer must come without the body and close the connection."""
        sock = self.client.connect(self)
        sock.sendall(request)
        response = support.read_response(sock, "POST")
        self.assert_error(response, status, code)
        self.assertEqual(response.headers["Connection"], "close")
        self.assertTrue(support.is_closed(sock))
        return response


class HealthTest(ApiTestCase):
    def test_healthz_needs_no_token_and_discloses_only_ok(self) -> None:
        response = self.request("GET", "/healthz")

        self.assertEqual(response.status, 200)
        self.assertEqual(response.json(), {"ok": True})

    def test_healthz_is_503_when_the_store_is_unhealthy(self) -> None:
        with mock.patch.object(self.store, "is_healthy", return_value=False):
            response = self.request("GET", "/healthz")

        self.assertEqual(response.status, 503)
        self.assertEqual(response.json(), {"ok": False})

    def test_whoami_names_the_service_and_the_role(self) -> None:
        for token, role in ((REPORTER, "reporter"), (AGENT, "agent")):
            with self.subTest(role=role):
                response = self.request("GET", "/v1/whoami", token=token)

                self.assertEqual(response.status, 200)
                self.assertEqual(
                    response.json(), {"service": "combray-site-reports", "api": 1, "role": role}
                )


class AuthTest(ApiTestCase):
    ROUTES = (
        ("GET", "/v1/whoami", None),
        ("POST", REPORTS, b"{}"),
        ("GET", REPORTS, None),
        ("GET", report_path(UNKNOWN_ID), None),
        ("GET", report_path(UNKNOWN_ID, "/screenshot"), None),
        ("POST", report_path(UNKNOWN_ID, "/status"), b'{"status":"open"}'),
        ("DELETE", report_path(UNKNOWN_ID), None),
    )

    def test_every_route_but_healthz_needs_a_bearer_token(self) -> None:
        cases = {
            "no header": {},
            "unknown token": {"Authorization": "Bearer unknown-token-0001"},
            "basic scheme": {"Authorization": "Basic cmVwb3J0ZXI6eA=="},
            "empty bearer": {"Authorization": "Bearer "},
            "token as scheme": {"Authorization": REPORTER},
            "hash instead of token": {"Authorization": f"Bearer {hash_token(REPORTER)}"},
        }
        for method, path, data in self.ROUTES:
            for name, headers in cases.items():
                with self.subTest(route=f"{method} {path}", case=name):
                    response = self.request(method, path, data, headers=headers)

                    self.assert_error(response, 401, "unauthorized")
                    self.assertEqual(
                        response.headers["WWW-Authenticate"], 'Bearer realm="site-reports"'
                    )

    def test_a_token_in_the_query_is_not_a_credential(self) -> None:
        response = self.request("GET", f"{REPORTS}?access_token={AGENT}")

        self.assert_error(response, 401, "unauthorized")

    def test_two_authorization_headers_are_refused(self) -> None:
        sock = self.client.connect(self)
        sock.sendall(
            b"GET /v1/whoami HTTP/1.1\r\nHost: x\r\n"
            + f"Authorization: Bearer {AGENT}\r\nAuthorization: Bearer {AGENT}\r\n\r\n".encode()
        )

        self.assert_error(support.read_response(sock), 401, "unauthorized")

    def test_the_scheme_is_case_insensitive(self) -> None:
        response = self.request("GET", "/v1/whoami", headers={"Authorization": f"bearer {AGENT}"})

        self.assertEqual(response.json()["role"], "agent")

    def test_each_role_is_refused_on_the_other_roles_routes(self) -> None:
        report_id = self.created_id()
        agent_routes = (
            ("GET", REPORTS, None),
            ("GET", report_path(report_id), None),
            ("GET", report_path(report_id, "/screenshot"), None),
            ("POST", report_path(report_id, "/status"), b'{"status":"claimed"}'),
            ("DELETE", report_path(report_id), None),
        )
        for method, path, data in agent_routes:
            with self.subTest(route=f"{method} {path}"):
                self.assert_error(
                    self.request(method, path, data, token=REPORTER), 403, "forbidden"
                )
        with self.subTest(route="POST /v1/reports"):
            self.assert_error(self.create(token=AGENT), 403, "forbidden")

        row = self.store.get(report_id)
        self.assertEqual((row.status, row.purged), ("open", False))
        self.assertEqual(len(self.store.history(report_id)), 1)


class RoutingTest(ApiTestCase):
    def test_paths_match_segment_by_segment_on_the_raw_path(self) -> None:
        report_id = self.created_id()
        paths = (
            "/",
            "/v1",
            "/v1/",
            "/v1/reports/",
            "/v1/whoami/",
            "//v1/whoami",
            "/v1//whoami",
            "/v1/%77hoami",
            "/v1/reports%2F" + report_id,
            f"/v1/reports/{report_id}/",
            f"/v1/reports/{report_id}%2Fscreenshot",
            f"/v1/reports/{report_id}/../{report_id}",
            f"/v1/reports/../reports/{report_id}",
            f"/v1/reports/{report_id.upper()}",
            "/v1/reports/not-a-uuid",
            "/v1/reports/{id}",
            "/v1/reports/6f1c2b9e-1d2a-1a5b-9c3d-2e1f0a9b8c7d",
            "/v2/whoami",
            "/healthz/",
            "http://127.0.0.1/v1/whoami",
        )
        for path in paths:
            with self.subTest(path=path):
                self.assert_error(self.request("GET", path, token=AGENT), 404, "not_found")

    def test_a_known_path_with_another_method_is_405_with_allow(self) -> None:
        cases = (
            ("PUT", REPORTS, "GET, POST"),
            ("DELETE", REPORTS, "GET, POST"),
            ("POST", "/v1/whoami", "GET"),
            ("PATCH", report_path(UNKNOWN_ID), "DELETE, GET"),
            ("PUT", report_path(UNKNOWN_ID, "/screenshot"), "GET"),
            ("GET", report_path(UNKNOWN_ID, "/status"), "POST"),
            ("OPTIONS", REPORTS, "GET, POST"),
            ("POST", "/healthz", "GET"),
        )
        for method, path, allow in cases:
            with self.subTest(method=method, path=path):
                response = self.request(method, path, token=AGENT)

                self.assert_error(response, 405, "method_not_allowed")
                self.assertEqual(response.headers["Allow"], allow)

    def test_head_is_not_served_and_gets_no_body(self) -> None:
        response = self.request("HEAD", "/v1/whoami", token=AGENT)

        self.assertEqual(response.status, 405)
        self.assertEqual(response.headers["Allow"], "GET")
        self.assertEqual(response.body, b"")

    def test_an_unknown_method_is_501_in_json(self) -> None:
        response = self.request("BREW", "/v1/whoami", token=AGENT)

        self.assert_error(response, 501, "not_implemented")

    def test_a_malformed_request_line_is_400_in_json(self) -> None:
        sock = self.client.connect(self)
        sock.sendall(b"GET /v1/whoami HTTP/1.1 extra\r\n\r\n")

        response = support.read_response(sock)

        self.assert_error(response, 400, "bad_request")
        for name, value in SECURITY_HEADERS.items():
            self.assertEqual(response.headers[name], value)
        self.assertTrue(support.is_closed(sock))

    def test_http_0_9_is_refused_with_a_status_line(self) -> None:
        sock = self.client.connect(self)
        # Python 3.12 reads a header block after an HTTP/0.9 request line; 3.13 does not.
        sock.sendall(b"GET /healthz\r\n\r\n")

        response = support.read_response(sock)

        self.assert_error(response, 505, "http_version_not_supported")
        self.assertTrue(support.is_closed(sock))

    def test_only_the_list_route_takes_a_query(self) -> None:
        report_id = self.created_id()
        for path, token in (
            ("/v1/whoami?x=1", AGENT),
            ("/healthz?x=1", None),
            (report_path(report_id, "?x=1"), AGENT),
            (report_path(report_id, "/screenshot?x=1"), AGENT),
        ):
            with self.subTest(path=path):
                self.assert_error(self.request("GET", path, token=token), 400, "invalid_query")

        self.assertEqual(self.request("GET", "/v1/whoami?", token=AGENT).status, 200)

    def test_a_kept_alive_connection_serves_several_requests(self) -> None:
        sock = self.client.connect(self)
        for _ in range(3):
            sock.sendall(head("GET", "/v1/whoami", {"Authorization": f"Bearer {AGENT}"}))
            self.assertEqual(support.read_response(sock).status, 200)


class BodyFramingTest(ApiTestCase):
    def post_head(self, path: str = REPORTS, token: str = REPORTER, **headers: str) -> bytes:
        values = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        values.update({name.replace("_", "-"): value for name, value in headers.items()})
        return head("POST", path, values)

    def test_a_body_without_content_length_is_411(self) -> None:
        self.assert_answered_before_the_body(self.post_head(), 411, "length_required")

    def test_transfer_encoding_is_411(self) -> None:
        self.assert_answered_before_the_body(
            self.post_head(Transfer_Encoding="chunked"), 411, "length_required"
        )

    def test_an_oversized_report_is_413_before_its_body_is_read(self) -> None:
        length = str(self.config.max_request_bytes + 1)

        self.assert_answered_before_the_body(
            self.post_head(Content_Length=length), 413, "too_large"
        )

    def test_an_oversized_status_change_is_413_before_its_body_is_read(self) -> None:
        path = report_path(UNKNOWN_ID, "/status")

        self.assert_answered_before_the_body(
            self.post_head(path, AGENT, Content_Length=str(16 * 1024 + 1)), 413, "too_large"
        )

    def test_a_body_that_is_not_json_is_415_before_it_is_read(self) -> None:
        for content_type in (
            "text/plain",
            "application/json; charset=iso-8859-1",
            "application/json; boundary=x",
            "application/jsonx",
            "multipart/form-data; boundary=x",
        ):
            with self.subTest(content_type=content_type):
                self.assert_answered_before_the_body(
                    self.post_head(Content_Type=content_type, Content_Length="10"),
                    415,
                    "unsupported_content_type",
                )

    def test_a_utf8_charset_parameter_is_accepted(self) -> None:
        for content_type in ("application/json; charset=utf-8", 'Application/JSON;charset="UTF-8"'):
            with self.subTest(content_type=content_type):
                response = self.create_with_type(content_type)

                self.assertEqual(response.status, 201, response.body)

    def create_with_type(self, content_type: str) -> support.Response:
        data = body(support.request())
        return self.request("POST", REPORTS, data, token=REPORTER, content_type=content_type)

    def test_an_invalid_content_length_is_400(self) -> None:
        for value in ("abc", "-1", "1e3", "²", "1234567890123456"):
            with self.subTest(value=value):
                self.assert_answered_before_the_body(
                    self.post_head(Content_Length=value), 400, "bad_request"
                )

    def test_two_content_length_headers_are_400(self) -> None:
        request = self.post_head(Content_Length="2")[:-2] + b"Content-Length: 2\r\n\r\n"

        self.assert_answered_before_the_body(request, 400, "bad_request")

    def test_refusing_a_request_with_a_body_closes_the_connection(self) -> None:
        sock = self.client.connect(self)
        data = body(support.request())
        sock.sendall(self.post_head(token="unknown-0001", Content_Length=str(len(data))) + data)

        response = support.read_response(sock, "POST")

        self.assert_error(response, 401, "unauthorized")
        self.assertEqual(response.headers["Connection"], "close")
        self.assertTrue(support.is_closed(sock))

    def test_a_read_body_keeps_the_connection_open(self) -> None:
        sock = self.client.connect(self)
        for status in (201, 200):
            data = body(support.golden())
            sock.sendall(self.post_head(Content_Length=str(len(data))) + data)
            response = support.read_response(sock, "POST")
            self.assertEqual(response.status, status)
            self.assertIsNone(response.headers["Connection"])

    def test_expect_100_continue_is_answered_only_when_the_body_is_wanted(self) -> None:
        data = body(support.request())
        sock = self.client.connect(self)
        sock.sendall(self.post_head(Content_Length=str(len(data)), Expect="100-continue"))

        self.assertEqual(receive_head(sock), b"HTTP/1.1 100 Continue\r\n\r\n")
        sock.sendall(data)
        self.assertEqual(support.read_response(sock, "POST").status, 201)

        refused = self.client.connect(self)
        refused.sendall(
            self.post_head(token="unknown-0001", Content_Length="10", Expect="100-continue")
        )
        self.assertTrue(receive_head(refused).startswith(b"HTTP/1.1 401 Unauthorized\r\n"))

    def test_a_body_on_a_route_without_one_closes_the_connection(self) -> None:
        sock = self.client.connect(self)
        sock.sendall(
            head(
                "GET",
                "/v1/whoami",
                {"Authorization": f"Bearer {AGENT}", "Content-Length": "5"},
            )
            + b"GET /"
        )

        response = support.read_response(sock)

        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers["Connection"], "close")
        self.assertTrue(support.is_closed(sock))


class CreateTest(ApiTestCase):
    def test_a_new_report_is_201_and_a_repeat_is_200_with_the_current_status(self) -> None:
        value = support.request()
        first = self.create(value)
        self.set_status(value["id"], {"status": "claimed"})
        self.clock.advance(timedelta(minutes=5))

        repeat = self.create(value)

        self.assertEqual(first.status, 201)
        self.assertEqual(
            first.json(),
            {"id": value["id"], "status": "open", "received_at": "2026-11-04T09:12:45.000Z"},
        )
        self.assertEqual(repeat.status, 200)
        self.assertEqual(repeat.json(), {**first.json(), "status": "claimed"})

    def test_the_golden_request_is_accepted_as_the_phone_sends_it(self) -> None:
        response = self.request("POST", REPORTS, support.golden_bytes(), token=REPORTER)

        self.assertEqual(response.status, 201)
        self.assertEqual(self.store.get(support.golden()["id"]).report["page"]["display"], "page")

    def test_the_same_id_with_other_content_is_409(self) -> None:
        value = support.request()
        self.create(value)

        response = self.create({**value, "comment": "Something else."})

        self.assert_error(response, 409, "id_conflict")

    def test_validation_errors_carry_their_code_and_field(self) -> None:
        cases = (
            (support.request(id="not-a-uuid"), 400, "invalid_id", "id"),
            (support.request(created_at="yesterday"), 400, "invalid_time", "created_at"),
            (support.request(extra=1), 400, "unknown_field", "extra"),
            (
                support.request(screenshot=None, screenshot_omitted=None),
                400,
                "invalid_screenshot",
                "screenshot",
            ),
        )
        for value, status, code, field in cases:
            with self.subTest(code=code):
                self.assert_error(self.create(value), status, code, field)

    def test_duplicate_keys_and_bad_json_are_400(self) -> None:
        for data, code in (
            (b'{"id":"a","id":"b"}', "duplicate_key"),
            (b"{", "invalid_json"),
            (b"", "invalid_json"),
            (b"\xff", "invalid_json"),
        ):
            with self.subTest(code=code, data=data):
                response = self.request("POST", REPORTS, data, token=REPORTER)

                self.assert_error(response, 400, code)

    def test_a_large_screenshot_is_413(self) -> None:
        large = support.png(64, 64, lambda x, y: (x * 4 % 256, y * 4 % 256, x ^ y, 255))
        self.assertGreater(len(large), 84)
        server = self.restart(max_screenshot_bytes=len(large) - 1)

        response = server.request(
            "POST",
            REPORTS,
            body(support.with_screenshot(support.request(), large, 64, 64)),
            token=REPORTER,
        )

        self.assert_error(response, 413, "screenshot_too_large", "screenshot.bytes")

    def restart(self, **overrides: Any) -> support.Client:
        """A second server over the same store with other configuration."""
        running = support.start_server(
            self, support.config(self.data, **overrides), self.store, self.clock
        )
        return support.Client(running.port)

    def test_a_deleted_report_answers_a_late_retry_with_200_deleted(self) -> None:
        value = support.request()
        self.create(value)
        self.assertEqual(self.request("DELETE", report_path(value["id"]), token=AGENT).status, 204)

        response = self.create(value)

        self.assertEqual(response.status, 200)
        self.assertEqual(response.json()["status"], "deleted")
        self.assertEqual(self.store.get(value["id"]).status, "deleted")
        self.assertFalse((self.data / "screenshots" / f"{value['id']}.png").exists())


class RateLimitTest(ApiTestCase):
    config_overrides = {  # noqa: RUF012 (read only)
        "max_reports_per_hour": 2,
        "tokens": support.tokens(reporter=(REPORTER, "reporter-token-synthetic-0002")),
    }

    def test_new_reports_past_the_hourly_limit_are_429_until_the_next_hour(self) -> None:
        first = support.request()
        self.created_id(first)
        self.created_id()

        refused = self.create()

        self.assert_error(refused, 429, "rate_limited")
        # 09:12:45 to 10:00:00.
        self.assertEqual(refused.headers["Retry-After"], str(47 * 60 + 15))
        self.clock.advance(timedelta(minutes=47, seconds=15))
        self.assertEqual(self.create().status, 201)

    def test_repeats_of_existing_ids_neither_count_nor_get_refused(self) -> None:
        first = support.request()
        self.created_id(first)
        for _ in range(3):
            self.assertEqual(self.create(first).status, 200)
        self.created_id()

        self.assertEqual(self.create(first).status, 200)
        self.assertEqual(self.create().status, 429)

    def test_each_reporter_token_has_its_own_window(self) -> None:
        self.created_id()
        self.created_id()

        response = self.create(token="reporter-token-synthetic-0002")

        self.assertEqual(response.status, 201)

    def test_refused_reports_do_not_count(self) -> None:
        self.assertEqual(self.create(support.request(id="bad")).status, 400)
        self.created_id()
        self.created_id()


class StorageTest(ApiTestCase):
    store_limits = Limits(max_storage_bytes=len(PNG) + 10)

    def test_a_screenshot_past_the_storage_budget_is_507(self) -> None:
        self.created_id()

        response = self.create()

        self.assert_error(response, 507, "storage_full")
        self.assertEqual(self.create(support.without_screenshot(support.request())).status, 201)


class ReadTest(ApiTestCase):
    def test_list_returns_open_reports_oldest_first_in_pages(self) -> None:
        ids = [self.created_id() for _ in range(5)]
        self.set_status(ids[1], {"status": "claimed"})
        self.set_status(ids[3], {"status": "done"})

        first = self.request("GET", f"{REPORTS}?limit=2", token=AGENT).json()
        second = self.request("GET", f"{REPORTS}?limit=2&after={first['next']}", token=AGENT)

        self.assertEqual([r["id"] for r in first["reports"]], [ids[0], ids[2]])
        self.assertRegex(first["next"], r"\A[0-9]+\Z")
        self.assertEqual([r["id"] for r in second.json()["reports"]], [ids[4]])
        self.assertIsNone(second.json()["next"])

    def test_list_filters_by_status_and_all_leaves_out_deleted(self) -> None:
        ids = [self.created_id() for _ in range(4)]
        self.set_status(ids[1], {"status": "claimed"})
        self.set_status(ids[2], {"status": "rejected"})
        self.request("DELETE", report_path(ids[3]), token=AGENT)

        def listed(query: str) -> list[str]:
            response = self.request("GET", f"{REPORTS}?{query}", token=AGENT)
            return [r["id"] for r in response.json()["reports"]]

        self.assertEqual(listed("status=open"), [ids[0]])
        self.assertEqual(listed("status=claimed"), [ids[1]])
        self.assertEqual(listed("status=rejected"), [ids[2]])
        self.assertEqual(listed("status=done"), [])
        self.assertEqual(listed("status=all"), ids[:3])
        self.assertEqual(listed("status=all&limit=100&after=0"), ids[:3])

    def test_invalid_list_queries_are_400(self) -> None:
        for query in (
            "status=deleted",
            "status=",
            "status=OPEN",
            "limit=0",
            "limit=101",
            "limit=abc",
            "limit=1.5",
            "limit=%2B1",
            "limit=%C2%B2",
            "after=-1",
            "after=1234567890123456789",
            "page=2",
            "status=open&status=all",
            "status",
            "a=1&b=2&c=3&d=4",
            "limit=1&&after=2",
        ):
            with self.subTest(query=query):
                response = self.request("GET", f"{REPORTS}?{query}", token=AGENT)

                self.assert_error(response, 400, "invalid_query")

    def test_a_report_has_its_metadata_and_history(self) -> None:
        value = support.request()
        report_id = self.created_id(value)
        self.clock.advance(timedelta(seconds=30))
        self.set_status(report_id, {"status": "claimed", "note": "looking"})

        response = self.request("GET", report_path(report_id), token=AGENT)

        self.assertEqual(response.status, 200)
        screenshot = {k: v for k, v in value["screenshot"].items() if k != "data"}
        self.assertEqual(
            response.json(),
            {
                "id": report_id,
                "status": "claimed",
                "status_note": "looking",
                "received_at": "2026-11-04T09:12:45.000Z",
                "updated_at": "2026-11-04T09:13:15.000Z",
                "created_at": value["created_at"],
                "page": value["page"],
                "comment": value["comment"],
                "client": value["client"],
                "screenshot": screenshot,
                "screenshot_omitted": None,
                "purged": False,
                "history": [
                    {
                        "status": "open",
                        "at": "2026-11-04T09:12:45.000Z",
                        "actor": "reporter",
                        "note": None,
                    },
                    {
                        "status": "claimed",
                        "at": "2026-11-04T09:13:15.000Z",
                        "actor": "agent",
                        "note": "looking",
                    },
                ],
            },
        )

    def test_unknown_reports_are_404_and_deleted_ones_410(self) -> None:
        report_id = self.created_id()
        self.request("DELETE", report_path(report_id), token=AGENT)

        self.assert_error(
            self.request("GET", report_path(UNKNOWN_ID), token=AGENT), 404, "not_found"
        )
        self.assert_error(self.request("GET", report_path(report_id), token=AGENT), 410, "deleted")

    def test_a_purged_report_keeps_its_status_and_loses_its_contents(self) -> None:
        report_id = self.created_id()
        self.set_status(report_id, {"status": "done", "note": "fixed"})
        self.clock.advance(timedelta(days=31))
        self.store.sweep()

        view = self.request("GET", report_path(report_id), token=AGENT).json()

        self.assertEqual(
            (view["status"], view["status_note"], view["purged"]), ("done", "fixed", True)
        )
        for key in ("created_at", "page", "comment", "client", "screenshot", "screenshot_omitted"):
            self.assertIsNone(view[key], key)
        self.assertEqual([h["actor"] for h in view["history"]], ["reporter", "agent", "retention"])
        self.assert_error(
            self.request("GET", report_path(report_id, "/screenshot"), token=AGENT),
            404,
            "no_screenshot",
        )

    def test_the_screenshot_is_the_uploaded_png_with_its_headers(self) -> None:
        report_id = self.created_id()

        response = self.request("GET", report_path(report_id, "/screenshot"), token=AGENT)

        self.assertEqual(response.status, 200)
        self.assertEqual(response.body, PNG)
        self.assertEqual(response.headers["Content-Type"], "image/png")
        self.assertEqual(response.headers["Content-Length"], str(len(PNG)))
        self.assertEqual(
            response.headers["Content-Disposition"], f'attachment; filename="{report_id}.png"'
        )
        for name, value in SECURITY_HEADERS.items():
            self.assertEqual(response.headers[name], value)
        listed = self.request("GET", report_path(report_id), token=AGENT).json()
        self.assertEqual(hashlib.sha256(response.body).hexdigest(), listed["screenshot"]["sha256"])

    def test_a_larger_screenshot_streams_in_full(self) -> None:
        large = support.png(200, 120, lambda x, y: (x % 256, y % 256, (x * y) % 256, 255))
        self.assertGreater(len(large), 64 * 1024)
        value = support.with_screenshot(support.request(), large, 200, 120)
        report_id = self.created_id(value)

        response = self.request("GET", report_path(report_id, "/screenshot"), token=AGENT)

        self.assertEqual(response.body, large)

    def test_screenshot_errors(self) -> None:
        without = self.created_id(support.without_screenshot(support.request()))
        deleted = self.created_id()
        self.request("DELETE", report_path(deleted), token=AGENT)

        for report_id, status, code in (
            (without, 404, "no_screenshot"),
            (deleted, 410, "deleted"),
            (UNKNOWN_ID, 404, "not_found"),
        ):
            with self.subTest(code=code):
                response = self.request("GET", report_path(report_id, "/screenshot"), token=AGENT)

                self.assert_error(response, status, code)

    def test_a_screenshot_directory_moved_behind_a_link_is_refused(self) -> None:
        report_id = self.created_id()
        screenshots = self.data / "screenshots"
        screenshots.rename(self.data / "elsewhere")
        screenshots.symlink_to(self.data / "elsewhere")

        response = self.request("GET", report_path(report_id, "/screenshot"), token=AGENT)

        self.assert_error(response, 500, "internal_error")


class StatusTest(ApiTestCase):
    def test_a_claim_with_expect_returns_the_report(self) -> None:
        report_id = self.created_id()

        response = self.set_status(report_id, {"status": "claimed", "expect": "open", "note": "a"})

        self.assertEqual(response.status, 200)
        view = response.json()
        self.assertEqual((view["status"], view["status_note"]), ("claimed", "a"))
        self.assertNotIn("history", view)

    def test_a_stale_expect_is_409_with_the_current_status(self) -> None:
        report_id = self.created_id()
        self.set_status(report_id, {"status": "claimed"})

        response = self.set_status(report_id, {"status": "claimed", "expect": "open"})

        self.assertEqual(response.status, 409)
        self.assertEqual(response.json(), {"error": "status_conflict", "status": "claimed"})

    def test_status_errors(self) -> None:
        resolved = self.created_id()
        self.set_status(resolved, {"status": "done"})
        purged = self.created_id()
        self.set_status(purged, {"status": "rejected"})
        self.clock.advance(timedelta(days=31))
        self.store.sweep()
        deleted = self.created_id()
        self.request("DELETE", report_path(deleted), token=AGENT)

        for report_id, value, status, code in (
            (resolved, {"status": "claimed"}, 400, "invalid_transition"),
            (purged, {"status": "open"}, 409, "purged"),
            (deleted, {"status": "open"}, 410, "deleted"),
            (UNKNOWN_ID, {"status": "open"}, 404, "not_found"),
        ):
            with self.subTest(code=code):
                self.assert_error(self.set_status(report_id, value), status, code)

    def test_invalid_status_bodies_are_400(self) -> None:
        report_id = self.created_id()
        path = report_path(report_id, "/status")
        for data, code, field in (
            (b'{"status":"deleted"}', "invalid_status", "status"),
            (b'{"status":"open","note":7}', "invalid_note", "note"),
            (b'{"status":"open","status":"done"}', "duplicate_key", None),
            (b'{"note":"x"}', "missing_field", "status"),
            (b"[]", "invalid_json", None),
        ):
            with self.subTest(code=code):
                response = self.request("POST", path, data, token=AGENT)

                self.assert_error(response, 400, code, field)
        self.assertEqual(self.store.get(report_id).status, "open")


class DeleteTest(ApiTestCase):
    def test_delete_is_204_without_a_body_and_repeatable(self) -> None:
        report_id = self.created_id()

        first = self.request("DELETE", report_path(report_id), token=AGENT)
        second = self.request("DELETE", report_path(report_id), token=AGENT)

        for response in (first, second):
            self.assertEqual(response.status, 204)
            self.assertEqual(response.body, b"")
            self.assertIsNone(response.headers["Content-Length"])
            self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertTrue(self.store.get(report_id).purged)

    def test_deleting_an_unknown_report_is_404(self) -> None:
        response = self.request("DELETE", report_path(UNKNOWN_ID), token=AGENT)

        self.assert_error(response, 404, "not_found")


class SecurityHeadersTest(ApiTestCase):
    def test_every_response_has_the_security_headers_and_no_cors_or_server(self) -> None:
        report_id = self.created_id()
        responses = {
            "200": self.request("GET", "/healthz"),
            "201": self.create(),
            "204": self.request("DELETE", report_path(report_id), token=AGENT),
            "400": self.request("GET", f"{REPORTS}?limit=0", token=AGENT),
            "401": self.request("GET", REPORTS),
            "403": self.request("GET", REPORTS, token=REPORTER),
            "404": self.request("GET", "/nothing"),
            "405": self.request("PUT", REPORTS),
            "410": self.request("GET", report_path(report_id), token=AGENT),
            "415": self.request("POST", REPORTS, b"x", token=REPORTER, content_type="text/plain"),
            "501": self.request("BREW", "/"),
        }
        for name, response in responses.items():
            with self.subTest(status=name):
                self.assertEqual(str(response.status), name)
                for header, value in SECURITY_HEADERS.items():
                    self.assertEqual(response.headers[header], value)
                self.assertIsNone(response.headers["Server"])
                self.assertFalse(
                    [h for h in response.headers if h.lower().startswith("access-control-")]
                )
                if name != "204":
                    self.assertEqual(response.headers["Content-Type"], JSON_TYPE)

    def test_cors_preflight_gets_no_cors_headers(self) -> None:
        response = self.request(
            "OPTIONS",
            REPORTS,
            headers={"Origin": "https://page.example", "Access-Control-Request-Method": "POST"},
        )

        self.assertEqual(response.status, 405)
        self.assertFalse([h for h in response.headers if h.lower().startswith("access-control-")])


class BusyTest(ApiTestCase):
    def test_requests_past_the_limit_are_503_busy(self) -> None:
        held = self.hold_slot("request_slots")

        response = self.request("GET", "/v1/whoami", token=AGENT)

        self.assert_error(response, 503, "busy")
        self.assertEqual(response.headers["Retry-After"], "5")
        held.close()
        support.wait_until(lambda: self.request("GET", "/healthz").status == 200)


class UploadSlotTest(ApiTestCase):
    def test_uploads_past_the_upload_slots_are_503_busy(self) -> None:
        held = self.hold_slot("upload_slots")

        response = self.request("POST", REPORTS, b"{}", token=REPORTER)

        self.assert_error(response, 503, "busy")
        self.assertEqual(response.headers["Retry-After"], "5")
        self.assertEqual(self.request("GET", "/v1/whoami", token=AGENT).status, 200)
        held.close()
        support.wait_until(lambda: self.create().status == 201)


class TimeoutTest(ApiTestCase):
    server_limits = ServerLimits(socket_timeout=0.3)

    def test_a_stalled_body_is_408_and_closes_the_connection(self) -> None:
        sock = self.hold_upload()

        response = support.read_response(sock, "POST")

        self.assert_error(response, 408, "timeout")
        self.assertTrue(support.is_closed(sock))

    def test_an_idle_connection_is_closed_without_a_response(self) -> None:
        sock = self.client.connect(self)

        self.assertTrue(support.is_closed(sock))
        self.assertEqual(self.log.lines, [])


class UploadDeadlineTest(ApiTestCase):
    server_limits = ServerLimits(upload_deadline=0.3)

    def test_a_trickled_body_is_408_at_the_deadline(self) -> None:
        sock = self.hold_upload()
        stop = threading.Event()

        def trickle() -> None:
            while not stop.wait(0.05):
                try:
                    sock.sendall(b" ")
                except OSError:
                    return

        thread = threading.Thread(target=trickle, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(stop.set)
        started = time.monotonic()

        response = support.read_response(sock, "POST")

        self.assert_error(response, 408, "timeout")
        self.assertLess(time.monotonic() - started, 3)


class ConnectionLimitTest(ApiTestCase):
    server_limits = ServerLimits(max_connections=2)

    def answered(self) -> bool:
        sock = self.client.connect(self)
        try:
            sock.sendall(head("GET", "/healthz", {}))
            return support.read_response(sock).status == 200
        except (http.client.RemoteDisconnected, ConnectionResetError, BrokenPipeError):
            return False
        finally:
            sock.close()

    def test_connections_past_the_limit_are_closed_at_once(self) -> None:
        idle = [self.client.connect(self) for _ in range(2)]

        support.wait_until(lambda: not self.answered())

        self.assertIn(
            "WARNING site_reports.api connection refused: 2 connections open", self.log.lines
        )
        idle[0].close()
        support.wait_until(self.answered)


class LoggingTest(ApiTestCase):
    def test_a_request_logs_one_line_of_listed_fields(self) -> None:
        value = support.request()
        data = body(value)
        self.request("POST", REPORTS, data, token=REPORTER)

        lines = self.log.wait_for(lambda lines: len(lines) == 1)

        match = re.fullmatch(
            r"INFO site_reports\.api POST /v1/reports 201 (\d+) (\d+) (\d+) reporter (\S+)",
            lines[0],
        )
        self.assertIsNotNone(match, lines[0])
        self.assertEqual(int(match[1]), len(data))
        self.assertEqual(match[4], value["id"])

    def test_unmatched_requests_log_no_path_text(self) -> None:
        self.request("GET", "/private-path-q7?session=abc123")
        self.request("BREW", "/private-path-q7")

        lines = self.log.wait_for(lambda lines: len(lines) == 2)

        self.assertEqual(
            [line.split()[2:5] for line in lines], [["GET", "-", "404"], ["OTHER", "-", "501"]]
        )
        self.assertNotIn("private-path", self.log.text())

    def test_a_full_flow_logs_no_token_url_title_comment_or_note(self) -> None:
        reporter = "reporter-SECRETTOKEN-7f3a"
        agent = "agent-SECRETTOKEN-9b1c"
        client = support.Client(
            support.start_server(
                self,
                support.config(self.data, tokens=support.tokens((reporter,), (agent,))),
                self.store,
                self.clock,
            ).port
        )
        secrets = {
            "reporter token": reporter,
            "agent token": agent,
            "reporter hash": hash_token(reporter),
            "agent hash": hash_token(agent),
            "host": "private-host-q7",
            "path": "SECRETPATH",
            "query": "SECRETQUERY",
            "title": "SECRETTITLE",
            "comment": "SECRETCOMMENT",
            "note": "SECRETNOTE",
            "list query": "limit=17",
        }
        value = support.request(comment="The SECRETCOMMENT overlaps.")
        value["page"].update(
            url="https://private-host-q7.example/zz-SECRETPATH?session=SECRETQUERY#frag",
            title="SECRETTITLE inbox",
        )
        report_id = value["id"]
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            steps = [
                client.request("POST", REPORTS, body(value), token=reporter),
                client.request("POST", REPORTS, body(value), token=reporter),
                client.request("GET", f"{REPORTS}?status=open&limit=17", token=agent),
                client.request("GET", report_path(report_id), token=agent),
                client.request("GET", report_path(report_id, "/screenshot"), token=agent),
                client.request(
                    "POST",
                    report_path(report_id, "/status"),
                    body({"status": "claimed", "expect": "open", "note": "SECRETNOTE claimed"}),
                    token=agent,
                ),
                client.request(
                    "POST",
                    report_path(report_id, "/status"),
                    body({"status": "done", "note": "SECRETNOTE fixed"}),
                    token=agent,
                ),
                client.request("GET", f"/v1/whoami?token={agent}"),
                client.request("GET", "/SECRETPATH/x", token=agent),
            ]
            self.doCleanups()  # Stops the servers, so every line is logged.

        self.assertEqual([r.status for r in steps], [201, 200, 200, 200, 200, 200, 200, 401, 404])
        logged = self.log.text() + stderr.getvalue()
        for name, secret in secrets.items():
            with self.subTest(secret=name):
                self.assertNotIn(secret, logged)
        self.assertEqual(stderr.getvalue(), "")
        self.assertIn("POST /v1/reports/{id}/status 200 ", logged)
        self.assertIn(f" agent {report_id}", logged)

    def test_an_internal_error_is_500_and_logged_by_class_name(self) -> None:
        failure = RuntimeError("SECRETDETAIL from the database")
        with mock.patch.object(self.store, "list_reports", side_effect=failure):
            response = self.request("GET", REPORTS, token=AGENT)

        self.assert_error(response, 500, "internal_error")
        lines = self.log.wait_for(lambda lines: len(lines) == 2)
        self.assertIn("ERROR site_reports.api internal error on /v1/reports: RuntimeError", lines)
        self.assertNotIn("SECRETDETAIL", self.log.text())


class SweepTest(ApiTestCase):
    server_limits = ServerLimits(sweep_interval=0.05)

    def test_start_sweeping_empties_staging_at_once_then_sweeps_periodically(self) -> None:
        leftover = self.data / "tmp" / "upload.partial"
        leftover.write_bytes(b"x")
        report_id = self.created_id()
        self.set_status(report_id, {"status": "done"})

        self.running.server.start_sweeping()

        self.assertFalse(leftover.exists())
        self.assertFalse(self.store.get(report_id).purged)
        self.clock.advance(timedelta(days=31))
        support.wait_until(lambda: self.store.get(report_id).purged)
        expected = "INFO site_reports.api sweep purged=1 expired=0 orphans=0 staging=0"
        self.log.wait_for(lambda lines: expected in lines)

    def test_a_failed_sweep_is_logged_by_class_name_and_retried(self) -> None:
        calls = []

        def sweep():
            calls.append(1)
            if len(calls) == 2:
                raise OSError("SECRETDETAIL")
            return self.store.__class__.sweep(self.store)

        with mock.patch.object(self.store, "sweep", side_effect=sweep):
            self.running.server.start_sweeping()
            support.wait_until(lambda: len(calls) >= 3)

        self.assertIn("ERROR site_reports.api sweep failed: OSError", self.log.lines)
        self.assertNotIn("SECRETDETAIL", self.log.text())


class HealthcheckCommandTest(ApiTestCase):
    def healthcheck(self, port: int) -> int:
        environ = {"REPORTS_LISTEN_HOST": "0.0.0.0", "REPORTS_LISTEN_PORT": str(port)}
        with contextlib.redirect_stderr(io.StringIO()):
            return main(["healthcheck"], environ)

    def test_healthcheck_exits_0_when_healthy_and_1_otherwise(self) -> None:
        self.assertEqual(self.healthcheck(self.running.port), 0)
        with mock.patch.object(self.store, "is_healthy", return_value=False):
            self.assertEqual(self.healthcheck(self.running.port), 1)
        self.running.stop()
        self.assertEqual(self.healthcheck(self.running.port), 1)


class ServeCommandTest(unittest.TestCase):
    """``python -m site_reports serve`` as the container runs it, stopped with SIGTERM."""

    def test_serve_answers_and_stops_on_sigterm(self) -> None:
        data = support.temporary_directory(self) / "data"
        environ = {
            "PATH": os.environ.get("PATH", ""),
            "REPORTS_REPORTER_TOKEN_SHA256": hash_token(REPORTER),
            "REPORTS_AGENT_TOKEN_SHA256": hash_token(AGENT),
            "REPORTS_DATA_DIR": str(data),
            "REPORTS_LISTEN_PORT": "0",
        }
        process = subprocess.Popen(
            [sys.executable, "-E", "-s", "-B", "-m", "site_reports", "serve"],
            cwd=support.SERVICE_ROOT,
            env=environ,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.addCleanup(process.stderr.close)
        self.addCleanup(process.kill)
        lines: list[str] = []
        listening = threading.Event()

        def read() -> None:
            for line in process.stderr:
                lines.append(line)
                if "listening on" in line:
                    listening.set()

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        self.assertTrue(listening.wait(support.WAIT_SECONDS), lines)
        port = int(re.search(r"listening on 127\.0\.0\.1:(\d+)", "".join(lines))[1])
        client = support.Client(port)

        whoami = client.request("GET", "/v1/whoami", token=REPORTER)
        created = client.request("POST", REPORTS, support.golden_bytes(), token=REPORTER)
        process.send_signal(signal.SIGTERM)

        self.assertEqual(process.wait(support.WAIT_SECONDS), 0)
        reader.join(support.WAIT_SECONDS)
        self.assertEqual((whoami.status, created.status), (200, 201))
        output = "".join(lines)
        self.assertIn("POST /v1/reports 201 ", output)
        self.assertNotIn(REPORTER, output)
        self.assertTrue((data / "screenshots" / f"{support.golden()['id']}.png").exists())


if __name__ == "__main__":
    unittest.main()
