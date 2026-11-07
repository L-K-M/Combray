"""Request validation for the site-report service: pure functions, no I/O.

The phone sends one JSON object per report (``POST /v1/reports``) and Paseo sends
small status changes. This module turns request bodies into immutable values or
raises ValidationError. The HTTP layer, the store and the fixture server share it,
so one set of rules decides what a valid report is.

A ValidationError's ``code`` and ``field`` are the wire contract
(``{"error": code, "field": field}``) and its ``status`` is the HTTP status. Its
``detail`` names the failed rule for tests and debugging; it never contains
request text, because reports hold private browsing data.
"""

from __future__ import annotations

import base64
import dataclasses
import hashlib
import json
import math
import re
import struct
import zlib
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from http import HTTPStatus
from typing import Any, NoReturn

PNG_MEDIA_TYPE = "image/png"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class ErrorCode(StrEnum):
    """Validation error codes, as sent in the ``error`` member of a response."""

    INVALID_JSON = "invalid_json"
    DUPLICATE_KEY = "duplicate_key"
    UNKNOWN_FIELD = "unknown_field"
    MISSING_FIELD = "missing_field"
    INVALID_ID = "invalid_id"
    INVALID_TIME = "invalid_time"
    INVALID_PAGE = "invalid_page"
    INVALID_URL = "invalid_url"
    INVALID_TITLE = "invalid_title"
    INVALID_DISPLAY = "invalid_display"
    INVALID_FLAG = "invalid_flag"
    INVALID_COMMENT = "invalid_comment"
    INVALID_CLIENT = "invalid_client"
    INVALID_SCREENSHOT = "invalid_screenshot"
    UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    SCREENSHOT_TOO_LARGE = "screenshot_too_large"
    SCREENSHOT_DIGEST_MISMATCH = "screenshot_digest_mismatch"
    INVALID_PNG = "invalid_png"
    INVALID_STATUS = "invalid_status"
    INVALID_NOTE = "invalid_note"


class Status(StrEnum):
    """A report's status. Agents set all but DELETED, which only DELETE sets."""

    OPEN = "open"
    CLAIMED = "claimed"
    DONE = "done"
    REJECTED = "rejected"
    DELETED = "deleted"


SETTABLE_STATUSES = frozenset({Status.OPEN, Status.CLAIMED, Status.DONE, Status.REJECTED})
RESOLVED_STATUSES = frozenset({Status.DONE, Status.REJECTED})


class ValidationError(Exception):
    """A request that breaks a validation rule."""

    def __init__(
        self,
        code: ErrorCode,
        field: str | None = None,
        *,
        status: HTTPStatus = HTTPStatus.BAD_REQUEST,
        detail: str = "",
    ) -> None:
        location = f" at {field}" if field else ""
        super().__init__(f"{code}{location}: {detail}")
        self.code = code
        self.field = field
        self.status = status
        self.detail = detail


@dataclass(frozen=True, slots=True)
class ParsedReport:
    """A validated report.

    ``metadata_json`` is the request without ``screenshot.data``, serialised
    canonically; ``content_sha256`` is its SHA-256 and decides whether a repeated
    id carries the same report. Private fields stay out of ``repr``.
    """

    id: str
    content_sha256: str
    metadata_json: str = dataclasses.field(repr=False)
    screenshot: bytes | None = dataclasses.field(repr=False)
    screenshot_sha256: str | None

    @property
    def metadata(self) -> dict[str, Any]:
        """A fresh copy of the validated metadata."""
        return json.loads(self.metadata_json)


@dataclass(frozen=True, slots=True)
class StatusChange:
    """A validated ``POST /v1/reports/{id}/status`` body."""

    status: Status
    expect: Status | None = None
    note: str | None = dataclasses.field(default=None, repr=False)


_REPORT_KEYS = ("id", "created_at", "page", "comment", "client", "screenshot", "screenshot_omitted")
_PAGE_KEYS = ("url", "url_edited", "title", "display", "load_failed", "crashed")
_PAGE_FLAGS = ("url_edited", "load_failed", "crashed")
_SCREENSHOT_KEYS = ("media_type", "width", "height", "bytes", "sha256", "scaled", "data")
_STATUS_CHANGE_KEYS = ("status", "expect", "note")
_REQUIRED_CLIENT_KEYS = (
    "app",
    "app_version",
    "engine_version",
    "engine_revision",
    "android_release",
)
_DISPLAYS = frozenset({"page", "reader"})
_SCREENSHOT_OMISSIONS = frozenset(
    {"user", "reader_view", "not_shown", "page_changed", "copy_failed", "too_large"}
)

_MAX_URL_BYTES = 8192
_MAX_TITLE_CHARS = 4096
_MAX_COMMENT_CHARS = 8000
_MAX_NOTE_CHARS = 2000
_MAX_CLIENT_ENTRIES = 24
_MAX_CLIENT_TEXT_CHARS = 256
_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1
_MAX_PNG_SIDE = 8192
_MAX_PNG_CHUNKS = 10_000
_MAX_PNG_CHUNK_LENGTH = 2**31 - 1
_PNG_CHUNK_OVERHEAD = 12  # Length, type and CRC.
# Every JSON value after the first needs a comma or an opening bracket, so their
# count bounds the number of values. A valid report has well under 30,000 even
# with every text field full of commas; the bound keeps a 12 MiB body of "[{},{},...]"
# from costing hundreds of MiB as Python objects.
_MAX_JSON_VALUES = 65_536
# A report nests two levels (the report, then page, client or screenshot). Deeper bodies
# are invalid JSON for this service. Checked after parsing: the json module stops deep
# nesting only through Python's recursion limit, and Python 3.15 parses 50,000 levels
# without reaching it.
_MAX_JSON_DEPTH = 32

# Explicit ASCII classes: \d would also accept other scripts' digits.
_REPORT_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
_TIMESTAMP = re.compile(
    r"([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{3}))?Z"
)
_SHA256_HEX = re.compile(r"[0-9a-f]{64}")
_CLIENT_KEY = re.compile(r"[a-z0-9_]{1,32}")
# Keys this short and plain may be echoed in an error's field; others are not.
_ECHOABLE_KEY = re.compile(r"[A-Za-z0-9_]{1,64}")
_PNG_CHUNK_TYPE = re.compile(rb"[A-Za-z]{4}")
# C0, DEL, C1 and lone surrogates (which JSON escapes can produce but UTF-8
# cannot encode).
_CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f\ud800-\udfff]")
_CONTROL_BUT_TAB_AND_NEWLINE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f\ud800-\udfff]")

# Valid bit depths per PNG colour type (PNG specification, IHDR).
_PNG_BIT_DEPTHS: Mapping[int, frozenset[int]] = {
    0: frozenset({1, 2, 4, 8, 16}),
    2: frozenset({8, 16}),
    3: frozenset({1, 2, 4, 8}),
    4: frozenset({8, 16}),
    6: frozenset({8, 16}),
}


def is_report_id(value: object) -> bool:
    """Whether ``value`` is a lowercase UUIDv4, the only form a report id takes."""
    return isinstance(value, str) and _REPORT_ID.fullmatch(value) is not None


def parse_report(body: bytes, *, max_screenshot_bytes: int) -> ParsedReport:
    """Validate a ``POST /v1/reports`` body completely, including the PNG's structure."""
    request = _decode_object(body)
    _check_keys(request, None, _REPORT_KEYS)

    report_id = request["id"]
    if not is_report_id(report_id):
        _fail(ErrorCode.INVALID_ID, "id", "not a lowercase UUIDv4")
    _check_timestamp(request["created_at"], "created_at")
    _check_page(request["page"])
    _check_comment(request["comment"])
    _check_client(request["client"])
    png = _check_screenshot(
        request["screenshot"], request["screenshot_omitted"], max_screenshot_bytes
    )

    metadata = dict(request)
    screenshot_sha256 = None
    if png is not None:
        screenshot = request["screenshot"]
        metadata["screenshot"] = {key: value for key, value in screenshot.items() if key != "data"}
        screenshot_sha256 = screenshot["sha256"]
    canonical = _canonical_json(metadata)
    return ParsedReport(
        id=report_id,
        content_sha256=_sha256_hex(canonical.encode("utf-8")),
        metadata_json=canonical,
        screenshot=png,
        screenshot_sha256=screenshot_sha256,
    )


def content_digest(metadata: Mapping[str, Any]) -> str:
    """SHA-256 hex of the canonical JSON of a report's metadata (no screenshot data)."""
    return _sha256_hex(_canonical_json(metadata).encode("utf-8"))


def check_png(data: bytes, width: int, height: int) -> None:
    """Check a PNG's chunk structure against its declared size without decompressing.

    Reading headers only keeps a decompression bomb harmless. Raises
    ValidationError(INVALID_PNG) on the first broken rule.
    """
    if not data.startswith(PNG_SIGNATURE):
        _png_fail("bad signature")

    view = memoryview(data)
    offset = len(PNG_SIGNATURE)
    chunks = 0
    seen_idat = False
    while True:
        if offset == len(data):
            _png_fail("no IEND chunk")
        if len(data) - offset < _PNG_CHUNK_OVERHEAD:
            _png_fail("truncated chunk")

        (length,) = struct.unpack_from(">I", data, offset)
        kind = data[offset + 4 : offset + 8]
        if length > _MAX_PNG_CHUNK_LENGTH:
            _png_fail("chunk length over 2^31-1")
        if _PNG_CHUNK_TYPE.fullmatch(kind) is None:
            _png_fail("chunk type is not four ASCII letters")
        end = offset + _PNG_CHUNK_OVERHEAD + length
        if end > len(data):
            _png_fail("chunk extends past the end of the data")
        (crc,) = struct.unpack_from(">I", data, end - 4)
        if zlib.crc32(view[offset + 4 : end - 4]) != crc:
            _png_fail("chunk CRC mismatch")

        chunks += 1
        if chunks > _MAX_PNG_CHUNKS:
            _png_fail("too many chunks")
        if chunks == 1:
            _check_ihdr(kind, view[offset + 8 : end - 4], width, height)
        elif kind == b"IHDR":
            _png_fail("second IHDR chunk")
        if kind == b"IDAT":
            seen_idat = True
        if kind == b"IEND":
            if length != 0:
                _png_fail("IEND chunk has data")
            if end != len(data):
                _png_fail("data after IEND")
            if not seen_idat:
                _png_fail("no IDAT chunk")
            return
        offset = end


def parse_status_change(body: bytes) -> StatusChange:
    """Validate a ``POST /v1/reports/{id}/status`` body."""
    request = _decode_object(body)
    _check_keys(request, None, _STATUS_CHANGE_KEYS, required=("status",))

    status = _settable_status(request["status"], "status")
    expect = request.get("expect")
    if expect is not None:
        expect = _settable_status(expect, "expect")
    note = request.get("note")
    if note is not None:
        _check_text(
            note, "note", ErrorCode.INVALID_NOTE, _MAX_NOTE_CHARS, _CONTROL_BUT_TAB_AND_NEWLINE
        )
    return StatusChange(status=status, expect=expect, note=note)


def _decode_object(body: bytes) -> dict[str, Any]:
    if body.count(b",") + body.count(b"[") + body.count(b"{") > _MAX_JSON_VALUES:
        _fail(ErrorCode.INVALID_JSON, detail="too many JSON values")
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        _fail(ErrorCode.INVALID_JSON, detail="body is not UTF-8")
    try:
        value = json.loads(
            text,
            object_pairs_hook=_unique_keys,
            parse_constant=_reject_constant,
            parse_float=_finite_float,
        )
    except (ValueError, RecursionError):
        # ValueError covers syntax errors and integers over Python's digit limit.
        _fail(ErrorCode.INVALID_JSON, detail="not valid JSON")
    if not isinstance(value, dict):
        _fail(ErrorCode.INVALID_JSON, detail="not a JSON object")
    if _depth(value) > _MAX_JSON_DEPTH:
        _fail(ErrorCode.INVALID_JSON, detail="nested too deeply")
    return value


def _depth(value: Any) -> int:
    """Nesting depth of a parsed body, 1 for an object of plain values, counted without
    recursion. Stops early once past _MAX_JSON_DEPTH; _MAX_JSON_VALUES bounds the walk."""
    deepest = 0
    pending: list[tuple[Any, int]] = [(value, 1)]
    while pending:
        item, depth = pending.pop()
        if isinstance(item, dict):
            children = list(item.values())
        elif isinstance(item, list):
            children = item
        else:
            continue
        deepest = max(deepest, depth)
        if deepest > _MAX_JSON_DEPTH:
            break
        pending.extend((child, depth + 1) for child in children)
    return deepest


def _unique_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = dict(pairs)
    if len(result) != len(pairs):
        _fail(ErrorCode.DUPLICATE_KEY, detail="an object repeats a key")
    return result


def _reject_constant(_name: str) -> NoReturn:
    _fail(ErrorCode.INVALID_JSON, detail="NaN or Infinity")


def _finite_float(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        _fail(ErrorCode.INVALID_JSON, detail="number out of range")
    return value


def _check_keys(
    obj: Mapping[str, Any],
    path: str | None,
    allowed: tuple[str, ...],
    *,
    required: tuple[str, ...] | None = None,
) -> None:
    for key in obj:
        if key not in allowed:
            location = _child(path, key) if _ECHOABLE_KEY.fullmatch(key) else path
            _fail(ErrorCode.UNKNOWN_FIELD, location, "unknown key")
    for key in allowed if required is None else required:
        if key not in obj:
            _fail(ErrorCode.MISSING_FIELD, _child(path, key), "missing key")


def _check_timestamp(value: Any, path: str) -> None:
    match = _TIMESTAMP.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        _fail(ErrorCode.INVALID_TIME, path, "not YYYY-MM-DDTHH:MM:SS(.fff)Z")
    try:
        datetime(*(int(part) for part in match.groups()[:6]), tzinfo=UTC)
    except ValueError:
        _fail(ErrorCode.INVALID_TIME, path, "not a calendar time")


def _check_page(page: Any) -> None:
    if not isinstance(page, dict):
        _fail(ErrorCode.INVALID_PAGE, "page", "not an object")
    _check_keys(page, "page", _PAGE_KEYS)

    url = page["url"]
    _check_text(url, "page.url", ErrorCode.INVALID_URL, None, _CONTROL)
    if not 1 <= len(url.encode("utf-8")) <= _MAX_URL_BYTES:
        _fail(ErrorCode.INVALID_URL, "page.url", "length out of range")

    title = page["title"]
    if title is not None:
        _check_text(title, "page.title", ErrorCode.INVALID_TITLE, _MAX_TITLE_CHARS, _CONTROL)

    display = page["display"]
    if not isinstance(display, str) or display not in _DISPLAYS:
        _fail(ErrorCode.INVALID_DISPLAY, "page.display", "not page or reader")

    for flag in _PAGE_FLAGS:
        if not isinstance(page[flag], bool):
            _fail(ErrorCode.INVALID_FLAG, f"page.{flag}", "not a boolean")


def _check_comment(comment: Any) -> None:
    if comment is None:
        return
    _check_text(
        comment,
        "comment",
        ErrorCode.INVALID_COMMENT,
        _MAX_COMMENT_CHARS,
        _CONTROL_BUT_TAB_AND_NEWLINE,
    )


def _check_client(client: Any) -> None:
    if not isinstance(client, dict):
        _fail(ErrorCode.INVALID_CLIENT, "client", "not an object")
    if len(client) > _MAX_CLIENT_ENTRIES:
        _fail(ErrorCode.INVALID_CLIENT, "client", "too many entries")

    for key, value in client.items():
        if _CLIENT_KEY.fullmatch(key) is None:
            _fail(ErrorCode.INVALID_CLIENT, "client", "key is not [a-z0-9_]{1,32}")
        _check_client_value(value, f"client.{key}")
    for key in _REQUIRED_CLIENT_KEYS:
        value = client.get(key)
        if not isinstance(value, str) or not value:
            _fail(ErrorCode.INVALID_CLIENT, f"client.{key}", "required text missing")


def _check_client_value(value: Any, path: str) -> None:
    # bool is a subclass of int, so it is tested first; floats are finite already.
    if isinstance(value, (bool, float)):
        return
    if isinstance(value, int):
        if not _INT64_MIN <= value <= _INT64_MAX:
            _fail(ErrorCode.INVALID_CLIENT, path, "integer out of 64-bit range")
        return
    if isinstance(value, str):
        _check_text(value, path, ErrorCode.INVALID_CLIENT, _MAX_CLIENT_TEXT_CHARS, _CONTROL)
        return
    _fail(ErrorCode.INVALID_CLIENT, path, "not text, a number or a boolean")


def _check_screenshot(screenshot: Any, omitted: Any, max_bytes: int) -> bytes | None:
    """Validate the screenshot or the reason for its absence; return the PNG bytes."""
    if (screenshot is None) == (omitted is None):
        _fail(
            ErrorCode.INVALID_SCREENSHOT,
            "screenshot",
            "not exactly one of screenshot and screenshot_omitted",
        )
    if screenshot is None:
        if not isinstance(omitted, str) or omitted not in _SCREENSHOT_OMISSIONS:
            _fail(ErrorCode.INVALID_SCREENSHOT, "screenshot_omitted", "unknown reason")
        return None

    if not isinstance(screenshot, dict):
        _fail(ErrorCode.INVALID_SCREENSHOT, "screenshot", "not an object")
    _check_keys(screenshot, "screenshot", _SCREENSHOT_KEYS)
    if screenshot["media_type"] != PNG_MEDIA_TYPE:
        _fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "screenshot.media_type", "not image/png")
    width = _check_side(screenshot["width"], "screenshot.width")
    height = _check_side(screenshot["height"], "screenshot.height")
    if not isinstance(screenshot["scaled"], bool):
        _fail(ErrorCode.INVALID_SCREENSHOT, "screenshot.scaled", "not a boolean")
    digest = screenshot["sha256"]
    if not isinstance(digest, str) or _SHA256_HEX.fullmatch(digest) is None:
        _fail(ErrorCode.INVALID_SCREENSHOT, "screenshot.sha256", "not 64 lowercase hex digits")

    size = screenshot["bytes"]
    if type(size) is not int or size < 1:
        _fail(ErrorCode.INVALID_SCREENSHOT, "screenshot.bytes", "not a positive integer")
    if size > max_bytes:
        _fail(
            ErrorCode.SCREENSHOT_TOO_LARGE,
            "screenshot.bytes",
            "over the screenshot limit",
            status=HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
        )

    png = _decode_base64(screenshot["data"], size)
    if _sha256_hex(png) != digest:
        _fail(
            ErrorCode.SCREENSHOT_DIGEST_MISMATCH,
            "screenshot.sha256",
            "differs from the data's SHA-256",
        )
    check_png(png, width, height)
    return png


def _check_side(value: Any, path: str) -> int:
    if type(value) is not int or not 1 <= value <= _MAX_PNG_SIDE:
        _fail(ErrorCode.INVALID_SCREENSHOT, path, f"not an integer from 1 to {_MAX_PNG_SIDE}")
    return value


def _decode_base64(data: Any, size: int) -> bytes:
    """Decode standard, padded base64 without line breaks into exactly ``size`` bytes."""
    path = "screenshot.data"
    if not isinstance(data, str):
        _fail(ErrorCode.INVALID_SCREENSHOT, path, "not text")
    # Checked before decoding, so a mismatched body is refused without the work.
    if len(data) != 4 * ((size + 2) // 3):
        _fail(ErrorCode.INVALID_SCREENSHOT, path, "length does not match bytes")
    try:
        decoded = base64.b64decode(data, validate=True)
    except ValueError:  # binascii.Error, or non-ASCII text.
        _fail(ErrorCode.INVALID_SCREENSHOT, path, "not standard padded base64")
    if len(decoded) != size:
        _fail(ErrorCode.INVALID_SCREENSHOT, path, "decoded length does not match bytes")
    return decoded


def _check_ihdr(kind: bytes, body: memoryview, width: int, height: int) -> None:
    if kind != b"IHDR":
        _png_fail("first chunk is not IHDR")
    if len(body) != 13:
        _png_fail("IHDR length is not 13")
    png_width, png_height, depth, colour, compression, filter_method, interlace = struct.unpack(
        ">IIBBBBB", body
    )
    if not (1 <= png_width <= _MAX_PNG_SIDE and 1 <= png_height <= _MAX_PNG_SIDE):
        _png_fail(f"side out of 1 to {_MAX_PNG_SIDE}")
    if (png_width, png_height) != (width, height):
        _png_fail("size differs from the declared size")
    if depth not in _PNG_BIT_DEPTHS.get(colour, frozenset()):
        _png_fail("invalid bit depth and colour type")
    if compression != 0:
        _png_fail("unknown compression method")
    if filter_method != 0:
        _png_fail("unknown filter method")
    if interlace not in (0, 1):
        _png_fail("unknown interlace method")


def _settable_status(value: Any, path: str) -> Status:
    if not isinstance(value, str) or value not in SETTABLE_STATUSES:
        _fail(ErrorCode.INVALID_STATUS, path, "not open, claimed, done or rejected")
    return Status(value)


def _check_text(
    value: Any, path: str, code: ErrorCode, max_chars: int | None, forbidden: re.Pattern[str]
) -> None:
    if not isinstance(value, str):
        _fail(code, path, "not text")
    if max_chars is not None and len(value) > max_chars:
        _fail(code, path, f"longer than {max_chars} characters")
    if forbidden.search(value) is not None:
        _fail(code, path, "control character")


def _canonical_json(metadata: Mapping[str, Any]) -> str:
    return json.dumps(
        metadata, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    )


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _child(path: str | None, key: str) -> str:
    return key if path is None else f"{path}.{key}"


def _png_fail(detail: str) -> NoReturn:
    _fail(ErrorCode.INVALID_PNG, "screenshot.data", detail)


def _fail(
    code: ErrorCode,
    field: str | None = None,
    detail: str = "",
    *,
    status: HTTPStatus = HTTPStatus.BAD_REQUEST,
) -> NoReturn:
    raise ValidationError(code, field, status=status, detail=detail)
