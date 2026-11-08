"""The report service's HTTP API (design sections 1.4 to 1.6).

``http.server`` is not hardened for hostile networks, so the service is for a
loopback or tailnet bind behind TLS, and this module adds the missing limits.
A header block is read only up to ``ServerLimits.max_header_bytes`` (431 past
it), so the headers of every open connection fit in memory together. Each
request then goes through the same steps, any of which can end it with a JSON
error:

1. route: the raw request target, segment by segment (404, then 405 with Allow);
2. auth: a Bearer token and its role (401 with WWW-Authenticate, then 403);
3. body framing, from the headers alone (411, 413, 415), before a byte is read;
4. the route's handler, which maps validation and store errors to wire codes.

A response sent without reading a declared body closes the connection, so the
unread bytes are never parsed as the next request. A header line that is not a
field line is 400 before routing, since the parser would hide the lines after
it, a Content-Length among them.

Logging writes one line per request: method, route template, status, body bytes
in and out, milliseconds, role and report id. Never a header value, a query, a
body, request path text or an exception's message: reports hold private
browsing data and requests carry tokens.
"""

from __future__ import annotations

import http.client
import json
import logging
import math
import re
import socket
import socketserver
import sys
import threading
import time
import urllib.parse
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, BinaryIO

from site_reports import API_VERSION, SERVICE_NAME
from site_reports.config import Config, Credential, Role
from site_reports.store import (
    Actor,
    Gone,
    IdConflict,
    InvalidTransition,
    NotFound,
    PurgedConflict,
    Row,
    StatusConflict,
    StorageFull,
    Store,
    StoreError,
    SweepResult,
    utc_now,
)
from site_reports.validation import (
    PNG_MEDIA_TYPE,
    SETTABLE_STATUSES,
    Status,
    ValidationError,
    is_report_id,
    parse_report,
    parse_status_change,
)

__all__ = ["Handler", "ReportServer", "ServerLimits", "make_server"]

_LOG = logging.getLogger(__name__)

_JSON_CONTENT_TYPE = "application/json; charset=utf-8"
_SECURITY_HEADERS = (
    ("Cache-Control", "no-store"),
    ("X-Content-Type-Options", "nosniff"),
    ("Referrer-Policy", "no-referrer"),
    ("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"),
)
_WWW_AUTHENTICATE = 'Bearer realm="site-reports"'
_BUSY_RETRY_AFTER_SECONDS = 5
_STREAM_CHUNK_BYTES = 64 * 1024
_RATE_WINDOW_SECONDS = 3600
_LIST_DEFAULT_LIMIT = 20
_LIST_MAX_LIMIT = 100
_LIST_STATUS_ALL = "all"
_ID_SEGMENT = "{id}"
_CONTENT_LENGTH = re.compile(r"[0-9]{1,15}")
# An RFC 9110 field name (a token), a colon and a value without CR or LF. A line
# with leading whitespace (obs-fold) is refused, as RFC 9112 allows.
_FIELD_LINE = re.compile(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+:[^\r\n]*\r?\n")
_HEADER_BLOCK_ENDS = frozenset({b"\r\n", b"\n", b""})
_NO_VALUE = "-"
_HTTP_0_9 = "HTTP/0.9"
# Methods named in log lines; any other is logged as OTHER, since the method is
# request text.
_KNOWN_METHODS = frozenset(
    {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"}
)

_REPORTER = frozenset({Role.REPORTER})
_AGENT = frozenset({Role.AGENT})
_ANY_ROLE = frozenset(Role)

_STORE_ERROR_STATUS: Mapping[type[StoreError], HTTPStatus] = {
    NotFound: HTTPStatus.NOT_FOUND,
    Gone: HTTPStatus.GONE,
    IdConflict: HTTPStatus.CONFLICT,
    StatusConflict: HTTPStatus.CONFLICT,
    PurgedConflict: HTTPStatus.CONFLICT,
    InvalidTransition: HTTPStatus.BAD_REQUEST,
    StorageFull: HTTPStatus.INSUFFICIENT_STORAGE,
}


@dataclass(frozen=True, slots=True)
class ServerLimits:
    """Per-process limits (design section 1.5) that are not configuration; tests shrink them.

    ``max_connections`` caps handler threads: ThreadingHTTPServer starts one per
    connection without a bound, and the container's pid limit would otherwise be
    the cap.
    """

    max_connections: int = 32
    max_requests: int = 8
    max_uploads: int = 2
    max_header_bytes: int = 32 * 1024
    status_body_bytes: int = 16 * 1024
    socket_timeout: float = 30.0
    upload_deadline: float = 300.0
    sweep_interval: float = 3600.0


class _HeaderBlockReader:
    """A connection's rfile while one header block is read, with a byte budget.

    http.client.parse_headers buffers up to 100 lines of 64 KiB each before any
    request slot or token check, enough for 32 connections to exhaust the
    container's memory. Past the budget this raises LineTooLong, which
    BaseHTTPRequestHandler answers with 431 and a closed connection.

    ``malformed`` is set by any line other than a field line or the blank line
    that ends the block. The email parser behind parse_headers ends the headers
    at such a line, so a Content-Length or Transfer-Encoding after it would go
    unseen and its body be read as the next request.
    """

    def __init__(self, rfile: BinaryIO, budget: int) -> None:
        self._rfile = rfile
        self._remaining = budget
        self.malformed = False

    def readline(self, limit: int = -1) -> bytes:
        allowed = self._remaining + 1 if limit < 0 else min(limit, self._remaining + 1)
        line = self._rfile.readline(allowed)
        self._remaining -= len(line)
        if self._remaining < 0:
            raise http.client.LineTooLong("header block")
        if line not in _HEADER_BLOCK_ENDS and _FIELD_LINE.fullmatch(line) is None:
            self.malformed = True
        return line


class _Refusal(Exception):
    """Ends a request with an error response."""

    def __init__(
        self,
        status: HTTPStatus,
        code: str,
        *,
        field: str | None = None,
        headers: Iterable[tuple[str, str]] = (),
        extra: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(code)
        self.status = status
        self.body: dict[str, Any] = {"error": code}
        if field is not None:
            self.body["field"] = field
        self.body.update(extra or {})
        self.headers = tuple(headers)


def _busy() -> _Refusal:
    return _Refusal(
        HTTPStatus.SERVICE_UNAVAILABLE,
        "busy",
        headers=[("Retry-After", str(_BUSY_RETRY_AFTER_SECONDS))],
    )


@dataclass(frozen=True, slots=True)
class _Request:
    credential: Credential | None
    report_id: str | None
    query: str


@dataclass(frozen=True, slots=True)
class _Endpoint:
    run: Callable[[Handler, _Request], None]
    roles: frozenset[Role] | None  # None: no token needed.
    takes_query: bool = False


@dataclass(frozen=True, slots=True)
class _Route:
    template: str  # As logged.
    endpoints: Mapping[str, _Endpoint]
    segments: tuple[str, ...] = field(init=False)
    allow: str = field(init=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "segments", tuple(self.template[1:].split("/")))
        object.__setattr__(self, "allow", ", ".join(sorted(self.endpoints)))


class _HourlyLimit:
    """New reports per reporter token, counted in fixed clock hours.

    Only created reports count, and a repeat of an existing id is never refused.
    The check and the count are separate steps, so concurrent uploads can pass the
    limit by at most the number of upload slots minus one.
    """

    def __init__(self, limit: int, clock: Callable[[], datetime]) -> None:
        self._limit = limit
        self._clock = clock
        self._lock = threading.Lock()
        self._counts: dict[str, tuple[int, int]] = {}  # key: (window, count)

    def retry_after(self, key: str) -> int | None:
        """Seconds until the window ends when ``key`` has used it up, else None."""
        now = self._clock().timestamp()
        window = int(now // _RATE_WINDOW_SECONDS)
        with self._lock:
            counted_window, count = self._counts.get(key, (window, 0))
        if counted_window != window or count < self._limit:
            return None
        return max(1, math.ceil((window + 1) * _RATE_WINDOW_SECONDS - now))

    def record(self, key: str) -> None:
        window = int(self._clock().timestamp() // _RATE_WINDOW_SECONDS)
        with self._lock:
            self._prune(window)
            counted_window, count = self._counts.get(key, (window, 0))
            self._counts[key] = (window, count + 1 if counted_window == window else 1)

    def _prune(self, window: int) -> None:
        """Drop entries from earlier hours: the limit is hourly, so an old window is never read."""
        stale = [key for key, (counted_window, _) in self._counts.items() if counted_window < window]
        for key in stale:
            del self._counts[key]


class Handler(BaseHTTPRequestHandler):
    """One connection; ``handle_one_request`` runs once per request on it."""

    server: ReportServer
    protocol_version = "HTTP/1.1"
    # Responses go out as two writes (headers, body); without this, Nagle's
    # algorithm and delayed ACKs can hold the body back on a kept-alive connection.
    disable_nagle_algorithm = True

    def setup(self) -> None:
        self.timeout = self.server.limits.socket_timeout
        super().setup()

    def handle_one_request(self) -> None:
        self._begin()
        try:
            super().handle_one_request()
        finally:
            if self._status is not None:
                self._log_request()

    def parse_request(self) -> bool:
        self._started = time.monotonic()
        rfile = self.rfile
        reader = _HeaderBlockReader(rfile, self.server.limits.max_header_bytes)
        self.rfile = reader  # type: ignore[assignment]
        try:
            parsed = super().parse_request()
        finally:
            self.rfile = rfile
        if not parsed:
            return False
        if self.request_version == _HTTP_0_9:
            # No headers, so no token, and responses without a status line.
            self.send_error(HTTPStatus.HTTP_VERSION_NOT_SUPPORTED)
            return False
        if reader.malformed:
            self.send_error(HTTPStatus.BAD_REQUEST)
            return False
        # self.path has had leading slashes collapsed; routing uses the raw target.
        self._target = self.requestline.split()[1]
        return True

    def handle_expect_100(self) -> bool:
        # 100 Continue goes out only when the body is about to be read
        # (_read_body), so a refused request never invites its body.
        self._expect_continue = True
        return True

    def send_response(self, code: int, message: str | None = None) -> None:
        # The base class adds a Server header naming the Python version and logs.
        self.send_response_only(code, message)
        self.send_header("Date", self.date_time_string())

    def send_error(self, code: int, message: str | None = None, explain: str | None = None) -> None:
        """The base class's answer to a request it cannot parse, as a JSON error."""
        self.close_connection = True
        if self.request_version == _HTTP_0_9:
            # Python 3.12 leaves the version at HTTP/0.9 for a malformed request
            # line, which would suppress the status line and headers.
            self.request_version = ""
        try:
            name = HTTPStatus(code).name.lower()
        except ValueError:
            name = "bad_request"
        self._send_json(code, {"error": name})

    def log_message(self, format: str, *args: Any) -> None:
        """Silenced: the base class logs request lines, paths and queries included."""

    def _dispatch(self) -> None:
        slots = self.server.request_slots
        if not slots.acquire(blocking=False):
            self._refuse(_busy())
            return
        try:
            self._serve()
        except _Refusal as refusal:
            self._refuse(refusal)
        except ValidationError as error:
            self._refuse(_Refusal(error.status, error.code, field=error.field))
        except StoreError as error:
            refusal = _store_refusal(error)
            if refusal is None:
                self._internal_error(error)
            else:
                self._refuse(refusal)
        except Exception as error:
            self._internal_error(error)
        finally:
            slots.release()

    do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = _dispatch
    do_OPTIONS = do_TRACE = do_CONNECT = _dispatch

    def _serve(self) -> None:
        path, _, query = self._target.partition("?")
        matched = _match(path)
        if matched is None:
            raise _Refusal(HTTPStatus.NOT_FOUND, "not_found")
        route, report_id = matched
        self._template = route.template

        endpoint = route.endpoints.get(self.command)
        if endpoint is None:
            raise _Refusal(
                HTTPStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed",
                headers=[("Allow", route.allow)],
            )
        credential = None
        if endpoint.roles is not None:
            credential = self._authenticate()
            if credential.role not in endpoint.roles:
                raise _Refusal(HTTPStatus.FORBIDDEN, "forbidden")
            self._report_id = report_id
        if query and not endpoint.takes_query:
            raise _Refusal(HTTPStatus.BAD_REQUEST, "invalid_query")
        endpoint.run(self, _Request(credential, report_id, query))

    def _authenticate(self) -> Credential:
        values = self.headers.get_all("Authorization") or []
        tokens = self.server.config.tokens
        credential = tokens.authenticate(values[0]) if len(values) == 1 else None
        if credential is None:
            raise _Refusal(
                HTTPStatus.UNAUTHORIZED,
                "unauthorized",
                headers=[("WWW-Authenticate", _WWW_AUTHENTICATE)],
            )
        self._role = credential.role
        return credential

    def _health(self, _request: _Request) -> None:
        healthy = self.server.store.is_healthy()
        status = HTTPStatus.OK if healthy else HTTPStatus.SERVICE_UNAVAILABLE
        self._send_json(status, {"ok": healthy})

    def _whoami(self, request: _Request) -> None:
        assert request.credential is not None
        self._send_json(
            HTTPStatus.OK,
            {"service": SERVICE_NAME, "api": API_VERSION, "role": request.credential.role.value},
        )

    def _create(self, request: _Request) -> None:
        assert request.credential is not None
        server = self.server
        length = self._body_length(server.config.max_request_bytes)
        if not server.upload_slots.acquire(blocking=False):
            raise _busy()
        try:
            parsed = parse_report(
                self._read_body(length), max_screenshot_bytes=server.config.max_screenshot_bytes
            )
            self._report_id = parsed.id
            key = request.credential.key
            if server.store.get(parsed.id) is None:
                retry_after = server.rate_limit.retry_after(key)
                if retry_after is not None:
                    raise _Refusal(
                        HTTPStatus.TOO_MANY_REQUESTS,
                        "rate_limited",
                        headers=[("Retry-After", str(retry_after))],
                    )
            row, created = server.store.add(parsed)
            if created:
                server.rate_limit.record(key)
        finally:
            server.upload_slots.release()
        self._send_json(
            HTTPStatus.CREATED if created else HTTPStatus.OK,
            {"id": row.id, "status": row.status.value, "received_at": row.received_at},
        )

    def _list(self, request: _Request) -> None:
        statuses, limit, after = _list_query(request.query)
        rows, cursor = self.server.store.list_reports(statuses, limit, after)
        self._send_json(
            HTTPStatus.OK,
            {
                "reports": [_report_view(row) for row in rows],
                "next": None if cursor is None else str(cursor),
            },
        )

    def _read(self, request: _Request) -> None:
        row = self._existing(request.report_id)
        view = _report_view(row)
        view["history"] = [
            {"status": e.status.value, "at": e.at, "actor": e.actor.value, "note": e.note}
            for e in self.server.store.history(row.id)
        ]
        self._send_json(HTTPStatus.OK, view)

    def _screenshot(self, request: _Request) -> None:
        row = self._existing(request.report_id)
        opened = self.server.store.open_screenshot(row.id) if row.has_screenshot else None
        if opened is None:
            raise _Refusal(HTTPStatus.NOT_FOUND, "no_screenshot")
        file, size = opened
        with file:
            self._stream(file, size, row.id)

    def _set_status(self, request: _Request) -> None:
        assert request.report_id is not None
        length = self._body_length(self.server.limits.status_body_bytes)
        change = parse_status_change(self._read_body(length))
        row = self.server.store.set_status(request.report_id, change, Actor.AGENT)
        self._send_json(HTTPStatus.OK, _report_view(row))

    def _delete(self, request: _Request) -> None:
        assert request.report_id is not None
        if not self.server.store.delete(request.report_id, Actor.AGENT):
            raise _Refusal(HTTPStatus.NOT_FOUND, "not_found")
        self._start_response(HTTPStatus.NO_CONTENT, None, None)

    def _existing(self, report_id: str | None) -> Row:
        assert report_id is not None
        row = self.server.store.get(report_id)
        if row is None:
            raise _Refusal(HTTPStatus.NOT_FOUND, "not_found")
        if row.status is Status.DELETED:
            raise _Refusal(HTTPStatus.GONE, "deleted")
        return row

    def _body_length(self, limit: int) -> int:
        """The declared body length, checked against ``limit`` and the content type."""
        lengths = self.headers.get_all("Content-Length") or []
        if "Transfer-Encoding" in self.headers or not lengths:
            # Body bytes may follow anyway; they must not be read as a request.
            self.close_connection = True
            raise _Refusal(HTTPStatus.LENGTH_REQUIRED, "length_required")
        if len(lengths) != 1 or _CONTENT_LENGTH.fullmatch(lengths[0].strip()) is None:
            raise _Refusal(HTTPStatus.BAD_REQUEST, "bad_request")
        length = int(lengths[0])
        if length > limit:
            raise _Refusal(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "too_large")
        if not _is_json(self.headers.get_all("Content-Type") or []):
            raise _Refusal(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_content_type")
        return length

    def _read_body(self, length: int) -> bytes:
        """Read exactly ``length`` bytes within the upload deadline.

        Each read returns after a single receive, so a client that trickles bytes
        meets the deadline within one socket timeout of it.
        """
        buffer = bytearray(length)
        view = memoryview(buffer)
        deadline = time.monotonic() + self.server.limits.upload_deadline
        try:
            if self._expect_continue:
                self._expect_continue = False
                self.send_response_only(HTTPStatus.CONTINUE)
                self.end_headers()
            while self._bytes_in < length:
                if time.monotonic() > deadline:
                    raise _Refusal(HTTPStatus.REQUEST_TIMEOUT, "timeout")
                count = self.rfile.readinto1(view[self._bytes_in :])
                if not count:
                    raise _Refusal(HTTPStatus.BAD_REQUEST, "incomplete_body")
                self._bytes_in += count
        except TimeoutError:
            raise _Refusal(HTTPStatus.REQUEST_TIMEOUT, "timeout") from None
        except OSError:
            # The client went away; the response attempt fails quietly.
            raise _Refusal(HTTPStatus.BAD_REQUEST, "incomplete_body") from None
        self._body_read = True
        return bytes(buffer)

    def _stream(self, file: BinaryIO, size: int, report_id: str) -> None:
        disposition = f'attachment; filename="{report_id}.png"'
        if not self._start_response(
            HTTPStatus.OK, PNG_MEDIA_TYPE, size, [("Content-Disposition", disposition)]
        ):
            return
        remaining = size
        while remaining > 0:
            block = file.read(min(_STREAM_CHUNK_BYTES, remaining))
            if not block or not self._write(block):
                break
            remaining -= len(block)
        if remaining:
            self.close_connection = True

    def _refuse(self, refusal: _Refusal) -> None:
        self._send_json(refusal.status, refusal.body, refusal.headers)

    def _internal_error(self, error: Exception) -> None:
        _LOG.error("internal error on %s: %s", self._template, type(error).__name__)
        if self._status is None:
            self._refuse(_Refusal(HTTPStatus.INTERNAL_SERVER_ERROR, "internal_error"))
        else:
            self.close_connection = True

    def _send_json(
        self, status: int, value: Mapping[str, Any], headers: Iterable[tuple[str, str]] = ()
    ) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if self._start_response(status, _JSON_CONTENT_TYPE, len(body), headers):
            self._write(body)

    def _start_response(
        self,
        status: int,
        content_type: str | None,
        length: int | None,
        headers: Iterable[tuple[str, str]] = (),
    ) -> bool:
        """Send the status line and headers; False when the connection failed."""
        self._status = int(status)
        if self._has_unread_body():
            self.close_connection = True
        try:
            self.send_response(status)
            for name, value in _SECURITY_HEADERS:
                self.send_header(name, value)
            if content_type is not None:
                self.send_header("Content-Type", content_type)
            if length is not None:
                self.send_header("Content-Length", str(length))
            for name, value in headers:
                self.send_header(name, value)
            if self.close_connection:
                self.send_header("Connection", "close")
            self.end_headers()
        except OSError:
            self.close_connection = True
            return False
        return True

    def _write(self, data: bytes) -> bool:
        if self.command == "HEAD":
            return True
        try:
            self.wfile.write(data)
        except OSError:
            self.close_connection = True
            return False
        self._bytes_out += len(data)
        return True

    def _has_unread_body(self) -> bool:
        if self._body_read:
            return False
        headers = getattr(self, "headers", None)
        if not isinstance(headers, http.client.HTTPMessage):
            # No header block was parsed (HTTP/0.9 leaves a plain dict).
            return True
        if "Transfer-Encoding" in headers:
            return True
        # Every value counts: a first "0" must not hide a second Content-Length.
        lengths = [value.strip() for value in headers.get_all("Content-Length") or []]
        return lengths not in ([], ["0"])

    def _begin(self) -> None:
        self._started = time.monotonic()
        self._target = ""
        self._template = _NO_VALUE
        self._role: Role | None = None
        self._report_id: str | None = None
        self._status: int | None = None
        self._bytes_in = 0
        self._bytes_out = 0
        self._body_read = False
        self._expect_continue = False

    def _log_request(self) -> None:
        method = self.command if self.command in _KNOWN_METHODS else "OTHER"
        _LOG.info(
            "%s %s %d %d %d %d %s %s",
            method if self.command else _NO_VALUE,
            self._template,
            self._status,
            self._bytes_in,
            self._bytes_out,
            round((time.monotonic() - self._started) * 1000),
            self._role or _NO_VALUE,
            self._report_id or _NO_VALUE,
        )


_ROUTES = (
    _Route("/healthz", {"GET": _Endpoint(Handler._health, None)}),
    _Route("/v1/whoami", {"GET": _Endpoint(Handler._whoami, _ANY_ROLE)}),
    _Route(
        "/v1/reports",
        {
            "GET": _Endpoint(Handler._list, _AGENT, takes_query=True),
            "POST": _Endpoint(Handler._create, _REPORTER),
        },
    ),
    _Route(
        "/v1/reports/{id}",
        {"GET": _Endpoint(Handler._read, _AGENT), "DELETE": _Endpoint(Handler._delete, _AGENT)},
    ),
    _Route("/v1/reports/{id}/screenshot", {"GET": _Endpoint(Handler._screenshot, _AGENT)}),
    _Route("/v1/reports/{id}/status", {"POST": _Endpoint(Handler._set_status, _AGENT)}),
)


class ReportServer(ThreadingHTTPServer):
    """The HTTP server with the shared state its handlers use."""

    daemon_threads = True
    # Idle kept-alive connections would hold server_close for a socket timeout.
    block_on_close = False

    def __init__(
        self,
        config: Config,
        store: Store,
        clock: Callable[[], datetime],
        limits: ServerLimits,
    ) -> None:
        self.config = config
        self.store = store
        self.limits = limits
        self.request_slots = threading.BoundedSemaphore(limits.max_requests)
        self.upload_slots = threading.BoundedSemaphore(limits.max_uploads)
        self.rate_limit = _HourlyLimit(config.max_reports_per_hour, clock)
        self._connection_slots = threading.BoundedSemaphore(limits.max_connections)
        self._stop_sweeping = threading.Event()
        self._sweeper: threading.Thread | None = None
        self.address_family = socket.AF_INET6 if ":" in config.listen_host else socket.AF_INET
        super().__init__((config.listen_host, config.listen_port), Handler)

    def server_bind(self) -> None:
        # HTTPServer.server_bind looks the host name up in DNS, which the
        # service never needs.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._connection_slots.acquire(blocking=False):
            _LOG.warning("connection refused: %d connections open", self.limits.max_connections)
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._connection_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connection_slots.release()

    def handle_error(self, request: Any, client_address: Any) -> None:
        # The default prints a traceback, whose messages may quote request data.
        error = sys.exc_info()[0]
        _LOG.error("connection error: %s", _NO_VALUE if error is None else error.__name__)

    def start_sweeping(self) -> None:
        """Sweep now (which empties tmp/), then every sweep interval until server_close."""
        _log_sweep(self.store.sweep())
        self._sweeper = threading.Thread(
            target=self._sweep_periodically, name="site-reports-sweep", daemon=True
        )
        self._sweeper.start()

    def server_close(self) -> None:
        self._stop_sweeping.set()
        if self._sweeper is not None:
            self._sweeper.join(timeout=self.limits.socket_timeout)
        super().server_close()

    def _sweep_periodically(self) -> None:
        while not self._stop_sweeping.wait(self.limits.sweep_interval):
            try:
                _log_sweep(self.store.sweep())
            except Exception as error:
                _LOG.error("sweep failed: %s", type(error).__name__)


def make_server(
    config: Config,
    store: Store,
    clock: Callable[[], datetime] | None = None,
    limits: ServerLimits | None = None,
) -> ReportServer:
    """A server bound to the configured address; the caller runs serve_forever."""
    return ReportServer(config, store, clock or utc_now, limits or ServerLimits())


def _match(path: str) -> tuple[_Route, str | None] | None:
    """The route for a raw path and its report id.

    Segments are compared undecoded, so ``%2F``, ``..``, empty segments and a
    trailing slash match nothing.
    """
    if not path.startswith("/"):
        return None
    segments = path[1:].split("/")
    for route in _ROUTES:
        if len(route.segments) != len(segments):
            continue
        report_id = None
        for expected, actual in zip(route.segments, segments, strict=True):
            if expected == _ID_SEGMENT:
                if not is_report_id(actual):
                    break
                report_id = actual
            elif expected != actual:
                break
        else:
            return route, report_id
    return None


def _list_query(query: str) -> tuple[frozenset[Status], int, int | None]:
    invalid = _Refusal(HTTPStatus.BAD_REQUEST, "invalid_query")
    try:
        pairs = urllib.parse.parse_qsl(
            query, keep_blank_values=True, strict_parsing=True, max_num_fields=3
        )
    except ValueError:
        raise invalid from None
    values = dict(pairs)
    if len(values) != len(pairs) or not values.keys() <= {"status", "limit", "after"}:
        raise invalid

    status = values.get("status", Status.OPEN.value)
    if status == _LIST_STATUS_ALL:
        statuses = frozenset(SETTABLE_STATUSES)
    elif status in SETTABLE_STATUSES:
        statuses = frozenset({Status(status)})
    else:
        raise invalid
    limit = _query_integer(values.get("limit"), 3, _LIST_DEFAULT_LIMIT)
    after = _query_integer(values.get("after"), 18, None)
    if limit is None or not 1 <= limit <= _LIST_MAX_LIMIT:
        raise invalid
    return statuses, limit, after


def _query_integer(value: str | None, digits: int, default: int | None) -> int | None:
    if value is None:
        return default
    if not (value.isascii() and value.isdigit() and len(value) <= digits):
        raise _Refusal(HTTPStatus.BAD_REQUEST, "invalid_query")
    return int(value)


def _report_view(row: Row) -> dict[str, Any]:
    """A report as the agent routes return it; contents are null once purged."""
    report = row.report or {}
    return {
        "id": row.id,
        "status": row.status.value,
        "status_note": row.status_note,
        "received_at": row.received_at,
        "updated_at": row.updated_at,
        "created_at": report.get("created_at"),
        "page": report.get("page"),
        "comment": report.get("comment"),
        "client": report.get("client"),
        # Advertised only while the file is held, so the screenshot route answers.
        "screenshot": report.get("screenshot") if row.has_screenshot else None,
        "screenshot_omitted": report.get("screenshot_omitted"),
        "purged": row.purged,
    }


def _store_refusal(error: StoreError) -> _Refusal | None:
    status = _STORE_ERROR_STATUS.get(type(error))
    if status is None:
        return None
    extra = {"status": error.current.value} if isinstance(error, StatusConflict) else None
    return _Refusal(status, error.code, extra=extra)


def _is_json(values: list[str]) -> bool:
    """Whether a Content-Type is application/json with at most a UTF-8 charset."""
    if len(values) != 1:
        return False
    media_type, *parameters = values[0].split(";")
    if media_type.strip().lower() != "application/json":
        return False
    for parameter in parameters:
        name, separator, value = parameter.partition("=")
        if not separator or name.strip().lower() != "charset":
            return False
        if value.strip().strip('"').lower() != "utf-8":
            return False
    return True


def _log_sweep(result: SweepResult) -> None:
    if any((result.purged, result.expired, result.orphans, result.staging)):
        _LOG.info(
            "sweep purged=%d expired=%d orphans=%d staging=%d",
            result.purged,
            result.expired,
            result.orphans,
            result.staging,
        )
