"""Shared test helpers: a tiny PNG builder, the golden request, a fake clock, stores,
and a real server on 127.0.0.1:0 with a client and a log capture."""

from __future__ import annotations

import base64
import copy
import hashlib
import http.client
import json
import logging
import socket
import struct
import tempfile
import threading
import time
import unittest
import uuid
import zlib
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from site_reports import validation
from site_reports.api import ReportServer, ServerLimits, make_server
from site_reports.config import Config, Tokens, hash_token
from site_reports.store import Limits, Store

GOLDEN_PATH = Path(__file__).resolve().parent / "data" / "report-v1.json"
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
START = datetime(2026, 11, 4, 9, 12, 45, tzinfo=UTC)
SERVICE_ROOT = Path(__file__).resolve().parent.parent
# Synthetic tokens; the server only ever sees their hashes.
REPORTER_TOKEN = "reporter-token-synthetic-0001"
AGENT_TOKEN = "agent-token-synthetic-0001"
WAIT_SECONDS = 5.0


def chunk(kind: bytes, data: bytes = b"", *, crc: int | None = None) -> bytes:
    """One PNG chunk; ``crc`` overrides the correct checksum."""
    checksum = zlib.crc32(kind + data) if crc is None else crc
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)


def ihdr(
    width: int,
    height: int,
    *,
    bit_depth: int = 8,
    colour_type: int = 6,
    compression: int = 0,
    filter_method: int = 0,
    interlace: int = 0,
) -> bytes:
    fields = struct.pack(
        ">IIBBBBB", width, height, bit_depth, colour_type, compression, filter_method, interlace
    )
    return chunk(b"IHDR", fields)


def rgba_idat(
    width: int, height: int, pixel: Callable[[int, int], tuple[int, int, int, int]]
) -> bytes:
    """An IDAT chunk holding 8-bit RGBA rows, each with filter type 0."""
    rows = b"".join(
        b"\x00" + b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height)
    )
    return chunk(b"IDAT", zlib.compress(rows))


def gradient(x: int, y: int) -> tuple[int, int, int, int]:
    return (40 * x % 256, 90 * y % 256, 200, 255)


def png(
    width: int = 3,
    height: int = 2,
    pixel: Callable[[int, int], tuple[int, int, int, int]] = gradient,
) -> bytes:
    """A small valid RGBA PNG, the format Android's Bitmap.compress(PNG) writes."""
    return (
        validation.PNG_SIGNATURE
        + ihdr(width, height)
        + rgba_idat(width, height, pixel)
        + chunk(b"IEND")
    )


def chunks(data: bytes) -> list[tuple[bytes, bytes]]:
    """(type, data) of each chunk of a well-formed PNG."""
    found = []
    offset = len(validation.PNG_SIGNATURE)
    while offset < len(data):
        (length,) = struct.unpack_from(">I", data, offset)
        found.append((data[offset + 4 : offset + 8], data[offset + 8 : offset + 8 + length]))
        offset += 12 + length
    return found


def rgba_rows(data: bytes) -> list[bytes]:
    """Decompress an 8-bit RGBA, non-interlaced PNG's pixel rows (filter type 0 only)."""
    parts = chunks(data)
    width, height = struct.unpack(">II", parts[0][1][:8])
    raw = zlib.decompress(b"".join(body for kind, body in parts if kind == b"IDAT"))
    stride = 1 + 4 * width
    if len(raw) != stride * height:
        raise ValueError("pixel data does not match the size")
    rows = [raw[y * stride : (y + 1) * stride] for y in range(height)]
    if any(row[0] != 0 for row in rows):
        raise ValueError("unexpected filter type")
    return [row[1:] for row in rows]


def golden_bytes() -> bytes:
    return GOLDEN_PATH.read_bytes()


def golden() -> dict[str, Any]:
    """A fresh copy of the golden phone request."""
    return json.loads(golden_bytes())


def request(**overrides: Any) -> dict[str, Any]:
    """The golden request with a new id and top-level members replaced."""
    result = golden()
    result["id"] = str(uuid.uuid4())
    result.update(copy.deepcopy(overrides))
    return result


def with_screenshot(base: dict[str, Any], data: bytes, width: int, height: int) -> dict[str, Any]:
    """``base`` carrying ``data`` as its screenshot, with matching metadata."""
    result = copy.deepcopy(base)
    result["screenshot"] = {
        "media_type": validation.PNG_MEDIA_TYPE,
        "width": width,
        "height": height,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "scaled": False,
        "data": base64.b64encode(data).decode("ascii"),
    }
    result["screenshot_omitted"] = None
    return result


def without_screenshot(base: dict[str, Any], reason: str = "user") -> dict[str, Any]:
    result = copy.deepcopy(base)
    result["screenshot"] = None
    result["screenshot_omitted"] = reason
    return result


def encode(value: Any) -> bytes:
    """Compact UTF-8 JSON, as the phone sends it.

    Text with a lone surrogate cannot be UTF-8, so it goes out with ASCII
    escapes (``\\ud800``), the only way a client can send one.
    """
    text = json.dumps(value, separators=(",", ":"), ensure_ascii=False)
    try:
        return text.encode("utf-8")
    except UnicodeEncodeError:
        return json.dumps(value, separators=(",", ":")).encode("ascii")


def parse(value: dict[str, Any]) -> validation.ParsedReport:
    return validation.parse_report(encode(value), max_screenshot_bytes=MAX_SCREENSHOT_BYTES)


def report(**overrides: Any) -> validation.ParsedReport:
    """A parsed report: the golden request with a new id and ``overrides``."""
    return parse(request(**overrides))


class FakeClock:
    """A settable clock; tests move it with ``advance``."""

    def __init__(self, start: datetime = START) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now

    def advance(self, delta: timedelta) -> None:
        self.now += delta


def temporary_directory(test: unittest.TestCase) -> Path:
    directory = tempfile.TemporaryDirectory()
    test.addCleanup(directory.cleanup)
    return Path(directory.name)


def open_store(
    test: unittest.TestCase,
    data_dir: Path,
    clock: Callable[[], datetime],
    limits: Limits | None = None,
) -> Store:
    """A Store over ``data_dir``, closed when the test ends."""
    store = Store(data_dir, clock, limits or Limits())
    test.addCleanup(store.close)
    return store


def tokens(
    reporter: tuple[str, ...] = (REPORTER_TOKEN,), agent: tuple[str, ...] = (AGENT_TOKEN,)
) -> Tokens:
    return Tokens([hash_token(t) for t in reporter], [hash_token(t) for t in agent])


def config(data_dir: Path, **overrides: Any) -> Config:
    """A configuration for a server on 127.0.0.1 and a free port."""
    values: dict[str, Any] = {
        "tokens": tokens(),
        "data_dir": data_dir,
        "listen_host": "127.0.0.1",
        "listen_port": 0,
    }
    values.update(overrides)
    return Config(**values)


class RunningServer:
    """A ReportServer serving from a thread; ``stop`` waits for every handler thread."""

    def __init__(self, server: ReportServer) -> None:
        self.server = server
        self.port: int = server.server_address[1]
        self._thread = threading.Thread(
            target=server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True
        )
        self._thread.start()
        self._stopped = False

    def stop(self) -> None:
        if self._stopped:
            return
        self._stopped = True
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(WAIT_SECONDS)


def start_server(
    test: unittest.TestCase,
    server_config: Config,
    store: Store,
    clock: Callable[[], datetime],
    limits: ServerLimits | None = None,
) -> RunningServer:
    server = make_server(server_config, store, clock, limits)
    # server_close then joins the handler threads, so none outlives its test's store.
    server.block_on_close = True
    running = RunningServer(server)
    test.addCleanup(running.stop)
    return running


@dataclass(frozen=True)
class Response:
    status: int
    headers: http.client.HTTPMessage
    body: bytes

    def json(self) -> Any:
        return json.loads(self.body)


class Client:
    """One HTTP connection per request to the test server."""

    def __init__(self, port: int) -> None:
        self.port = port

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        *,
        token: str | None = None,
        headers: Mapping[str, str] | None = None,
        content_type: str = "application/json",
    ) -> Response:
        sent: dict[str, str] = {}
        if token is not None:
            sent["Authorization"] = f"Bearer {token}"
        if body is not None:
            sent["Content-Type"] = content_type
        sent.update(headers or {})
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=WAIT_SECONDS)
        try:
            connection.request(method, path, body=body, headers=sent)
            response = connection.getresponse()
            return Response(response.status, response.headers, response.read())
        finally:
            connection.close()

    def connect(self, test: unittest.TestCase, timeout: float = WAIT_SECONDS) -> socket.socket:
        """A raw connection, closed when the test ends."""
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=timeout)
        test.addCleanup(sock.close)
        return sock


def read_response(sock: socket.socket, method: str = "GET") -> Response:
    response = http.client.HTTPResponse(sock, method=method)
    try:
        response.begin()
        return Response(response.status, response.headers, response.read())
    finally:
        response.close()


def is_closed(sock: socket.socket) -> bool:
    """Whether the server closed the connection (after any response was read)."""
    try:
        return sock.recv(1) == b""
    except ConnectionResetError:
        return True


class LogCapture(logging.Handler):
    """Records of the service's loggers, formatted with any exception text."""

    def __init__(self) -> None:
        super().__init__(logging.DEBUG)
        self.setFormatter(logging.Formatter("%(levelname)s %(name)s %(message)s"))
        self._lines: list[str] = []
        self._lock = threading.Lock()

    def emit(self, record: logging.LogRecord) -> None:
        line = self.format(record)
        with self._lock:
            self._lines.append(line)

    @property
    def lines(self) -> list[str]:
        with self._lock:
            return list(self._lines)

    def text(self) -> str:
        return "\n".join(self.lines)

    def wait_for(self, predicate: Callable[[list[str]], bool]) -> list[str]:
        """Lines once ``predicate`` holds; handlers log after the response is sent."""
        deadline = time.monotonic() + WAIT_SECONDS
        while not predicate(lines := self.lines):
            if time.monotonic() > deadline:
                raise AssertionError(f"log lines never matched: {lines}")
            time.sleep(0.01)
        return lines


def capture_logs(test: unittest.TestCase) -> LogCapture:
    logger = logging.getLogger("site_reports")
    capture = LogCapture()
    previous = (logger.level, logger.propagate)
    logger.addHandler(capture)
    logger.setLevel(logging.INFO)
    logger.propagate = False

    def restore() -> None:
        logger.removeHandler(capture)
        logger.setLevel(previous[0])
        logger.propagate = previous[1]

    test.addCleanup(restore)
    return capture


def wait_until(condition: Callable[[], bool]) -> None:
    deadline = time.monotonic() + WAIT_SECONDS
    while not condition():
        if time.monotonic() > deadline:
            raise AssertionError("condition never held")
        time.sleep(0.01)
