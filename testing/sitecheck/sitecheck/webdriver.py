"""A small W3C WebDriver client over urllib, for driving one local servoshell.

It implements only the commands the Servo runner needs (status, new session,
navigate, execute/sync, screenshot, delete session). Every call takes an explicit
socket timeout because the engine on the other end may hang; ``urllib`` applies
it to the connect and to each read, so the runner's wall-clock watchdog, which
kills servoshell, is what bounds a call overall.

Failures are split by what they say about the engine:

- ``WebDriverError``: the server answered with a W3C error (``error`` holds the
  error code, such as ``"timeout"`` or ``"javascript error"``), or with a response
  that is not valid WebDriver JSON.
- ``WebDriverTimeout``: no answer within the socket timeout.
- ``WebDriverConnectionError``: the connection was refused, reset or closed,
  which after startup usually means the engine process died.
"""

from __future__ import annotations

import base64
import binascii
import http.client
import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping, Sequence
from typing import Any

# W3C WebDriver error codes the runner reacts to
# (https://w3c.github.io/webdriver/#errors).
ERROR_TIMEOUT = "timeout"
ERROR_SCRIPT_TIMEOUT = "script timeout"
ERROR_SESSION_NOT_CREATED = "session not created"

# Not a W3C code: the server's response could not be read as WebDriver JSON.
ERROR_MALFORMED_RESPONSE = "malformed response"

# A 412x915 RGBA screenshot is well under 2 MiB of base64; this only stops a
# misbehaving server from exhausting memory.
_MAX_RESPONSE_BYTES = 64 * 1024 * 1024
_READ_CHUNK_BYTES = 64 * 1024
# Error messages can carry page content (a script's exception text); keep enough
# to diagnose, not whole stack traces.
_MAX_ERROR_MESSAGE_CHARS = 1000
_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_JSON_CONTENT_TYPE = "application/json; charset=utf-8"


class WebDriverError(Exception):
    """The server reported a W3C WebDriver error, or sent an unreadable response."""

    def __init__(self, error: str, message: str, *, http_status: int | None = None) -> None:
        super().__init__(f"{error}: {message}")
        self.error = error
        self.message = message
        self.http_status = http_status


class WebDriverTimeout(Exception):
    """The server did not answer within the call's socket timeout."""


class WebDriverConnectionError(Exception):
    """The connection was refused, reset or closed before a complete response."""


class WebDriverClient:
    """Talks to one WebDriver server and holds at most one session.

    Not thread-safe: the runner drives a session from one thread.
    """

    def __init__(self, base_url: str) -> None:
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme != "http" or not parsed.hostname:
            raise ValueError(f"WebDriver base URL must be http://host:port, got {base_url!r}")
        self._base_url = base_url.rstrip("/")
        # An empty ProxyHandler ignores http_proxy and friends: the server is local,
        # and page URLs sent to it must not pass through a proxy from the environment.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self._session_id: str | None = None

    @property
    def session_id(self) -> str | None:
        return self._session_id

    def status(self, *, timeout: float) -> dict[str, Any]:
        """Return the server's status object, e.g. ``{"ready": true, "message": ...}``."""
        value = self._request("GET", "/status", None, timeout)
        if not isinstance(value, dict):
            raise WebDriverError(ERROR_MALFORMED_RESPONSE, "status value is not an object")
        return value

    def new_session(self, capabilities: Mapping[str, Any], *, timeout: float) -> dict[str, Any]:
        """Open a session with ``capabilities`` as alwaysMatch; return the granted capabilities."""
        if self._session_id is not None:
            raise RuntimeError("a WebDriver session is already open on this client")

        body = {"capabilities": {"alwaysMatch": dict(capabilities)}}
        value = self._request("POST", "/session", body, timeout)
        if not isinstance(value, dict) or not isinstance(value.get("sessionId"), str):
            raise WebDriverError(ERROR_MALFORMED_RESPONSE, "new session response has no sessionId")

        self._session_id = value["sessionId"]
        granted = value.get("capabilities")
        return granted if isinstance(granted, dict) else {}

    def navigate(self, url: str, *, timeout: float) -> None:
        """Load ``url``; returns once the page load strategy is satisfied."""
        self._request("POST", self._session_path("url"), {"url": url}, timeout)

    def execute(self, script: str, args: Sequence[Any] = (), *, timeout: float) -> Any:
        """Run ``script`` as a function body (it must ``return`` its result)."""
        body = {"script": script, "args": list(args)}
        return self._request("POST", self._session_path("execute/sync"), body, timeout)

    def screenshot(self, *, timeout: float) -> bytes:
        """Return a PNG of the current viewport."""
        value = self._request("GET", self._session_path("screenshot"), None, timeout)
        if not isinstance(value, str):
            raise WebDriverError(ERROR_MALFORMED_RESPONSE, "screenshot value is not a string")
        try:
            png = base64.b64decode(value, validate=True)
        except (binascii.Error, ValueError) as error:
            raise WebDriverError(
                ERROR_MALFORMED_RESPONSE, f"screenshot is not base64: {error}"
            ) from error
        if not png.startswith(_PNG_SIGNATURE):
            raise WebDriverError(ERROR_MALFORMED_RESPONSE, "screenshot is not a PNG")
        return png

    def delete_session(self, *, timeout: float) -> None:
        """End the session. The client forgets it even if the server fails to answer."""
        path = self._session_path()
        try:
            self._request("DELETE", path, None, timeout)
        finally:
            self._session_id = None

    def _session_path(self, command: str | None = None) -> str:
        if self._session_id is None:
            raise RuntimeError("no WebDriver session is open on this client")
        path = f"/session/{urllib.parse.quote(self._session_id, safe='')}"
        return f"{path}/{command}" if command else path

    def _request(
        self, method: str, path: str, body: Mapping[str, Any] | None, timeout: float
    ) -> Any:
        """Send one command and return the response's ``value``."""
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(
            self._base_url + path,
            data=data,
            method=method,
            headers={"Content-Type": _JSON_CONTENT_TYPE, "Accept": "application/json"},
        )
        description = f"{method} {path}"

        # HTTPError (a URLError subclass) never escapes _send; it carries a W3C error body.
        try:
            http_status, raw = self._send(request, timeout)
        except urllib.error.URLError as error:
            if isinstance(error.reason, TimeoutError):
                raise WebDriverTimeout(f"{description}: no answer within {timeout:g} s") from error
            raise WebDriverConnectionError(f"{description}: {error.reason}") from error
        except TimeoutError as error:
            raise WebDriverTimeout(f"{description}: no answer within {timeout:g} s") from error
        except (OSError, http.client.HTTPException) as error:
            raise WebDriverConnectionError(f"{description}: {error!r}") from error

        return _decode_value(raw, http_status, description)

    def _send(self, request: urllib.request.Request, timeout: float) -> tuple[int, bytes]:
        try:
            with self._opener.open(request, timeout=timeout) as response:
                return response.status, _read_capped(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, _read_capped(error)


def _read_capped(response: Any) -> bytes:
    chunks: list[bytes] = []
    size = 0
    while chunk := response.read(_READ_CHUNK_BYTES):
        size += len(chunk)
        if size > _MAX_RESPONSE_BYTES:
            raise WebDriverError(
                ERROR_MALFORMED_RESPONSE, f"response larger than {_MAX_RESPONSE_BYTES} bytes"
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _decode_value(raw: bytes, http_status: int, description: str) -> Any:
    """Return the ``value`` of a WebDriver response, raising its W3C error if it has one."""
    try:
        payload = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise WebDriverError(
            ERROR_MALFORMED_RESPONSE,
            f"{description}: HTTP {http_status} with a body that is not JSON",
            http_status=http_status,
        ) from None

    if not isinstance(payload, dict) or "value" not in payload:
        raise WebDriverError(
            ERROR_MALFORMED_RESPONSE,
            f"{description}: HTTP {http_status} without a value",
            http_status=http_status,
        )

    value = payload["value"]
    # W3C errors use 4xx/5xx statuses, but some servers send them with 200;
    # an object with an "error" code is an error either way.
    if isinstance(value, dict) and isinstance(value.get("error"), str):
        message = str(value.get("message", ""))[:_MAX_ERROR_MESSAGE_CHARS]
        raise WebDriverError(value["error"], message, http_status=http_status)
    if http_status >= 400:
        raise WebDriverError(
            ERROR_MALFORMED_RESPONSE,
            f"{description}: HTTP {http_status} without a W3C error",
            http_status=http_status,
        )
    return value
