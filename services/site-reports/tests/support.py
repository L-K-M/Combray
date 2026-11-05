"""Shared test helpers: a tiny PNG builder, the golden request, a fake clock and stores."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
import struct
import tempfile
import unittest
import uuid
import zlib
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from site_reports import validation

GOLDEN_PATH = Path(__file__).resolve().parent / "data" / "report-v1.json"
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
START = datetime(2026, 11, 4, 9, 12, 45, tzinfo=UTC)


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
