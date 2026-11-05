"""Smoke test: the golden phone report through a running service.

The Site reports workflow runs it against the container Compose starts; it is
not part of ``unittest discover``. Standard library only, and it imports nothing
from this directory, so it runs with ``python3 -I``:

    SMOKE_REPORTER_TOKEN=<reporter token> SMOKE_AGENT_TOKEN=<agent token> \\
        python3 -I services/site-reports/tests/smoke.py --root http://127.0.0.1:8787

The service must start with an empty data directory, because the golden report's
id has to be new. It posts that report with the reporter token, then reads it,
its screenshot and a status change with the agent token, and checks that a
repeat POST answers 200 without reopening the report. Exit status: 0 when every
check passes, 1 at the first failure, 2 for a usage error. Tokens are never
printed.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import os
import sys
import urllib.parse
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

GOLDEN_PATH = Path(__file__).resolve().parent / "data" / "report-v1.json"
REPORTER_TOKEN = "SMOKE_REPORTER_TOKEN"
AGENT_TOKEN = "SMOKE_AGENT_TOKEN"
DEFAULT_ROOT = "http://127.0.0.1:8787"
SERVICE_NAME = "combray-site-reports"
API_VERSION = 1
TIMEOUT_SECONDS = 10.0

EXIT_OK = 0
EXIT_FAILURE = 1
EXIT_USAGE = 2

_JSON_TYPE = "application/json"
_PNG_TYPE = "image/png"
_UNKNOWN_TOKEN = "smoke-token-never-configured"
_CLAIM_NOTE = "smoke: claimed"
_DONE_NOTE = "smoke: done"


class SmokeFailure(Exception):
    """A check that did not hold; the message says which and why."""


@dataclass(frozen=True, slots=True)
class Response:
    status: int
    headers: http.client.HTTPMessage
    body: bytes

    def json(self) -> Any:
        if not self.headers.get("Content-Type", "").startswith(_JSON_TYPE):
            raise SmokeFailure(f"status {self.status} without a JSON body")
        return json.loads(self.body)

    def error(self) -> str:
        """The body's error code, for failure messages."""
        try:
            return str(self.json().get("error", "-"))
        except (SmokeFailure, ValueError, AttributeError):
            return "-"


class Service:
    """One connection per request to the service at ``root``, which may carry a path prefix."""

    def __init__(self, root: str) -> None:
        parts = urllib.parse.urlsplit(root)
        if (
            parts.scheme not in ("http", "https")
            or not parts.hostname
            or parts.username is not None
            or parts.query
            or parts.fragment
        ):
            raise ValueError("--root must be an http or https URL without credentials or query")
        self._https = parts.scheme == "https"
        self._host = parts.hostname
        self._port = parts.port
        self._prefix = parts.path.rstrip("/")

    def request(
        self, method: str, path: str, *, token: str | None = None, body: bytes | None = None
    ) -> Response:
        headers = {"Accept": f"{_JSON_TYPE}, {_PNG_TYPE}"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            headers["Content-Type"] = _JSON_TYPE
        connection_class = (
            http.client.HTTPSConnection if self._https else http.client.HTTPConnection
        )
        connection = connection_class(self._host, self._port, timeout=TIMEOUT_SECONDS)
        try:
            connection.request(method, self._prefix + path, body=body, headers=headers)
            response = connection.getresponse()
            result = Response(response.status, response.headers, response.read())
        except (OSError, http.client.HTTPException) as error:
            raise SmokeFailure(f"{method} {path}: no answer ({type(error).__name__})") from None
        finally:
            connection.close()
        _check_common_headers(result, f"{method} {path}")
        return result


def main(argv: list[str] | None = None, environ: Mapping[str, str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Smoke-test a running site-report service.")
    parser.add_argument(
        "--root", default=DEFAULT_ROOT, help=f"service root (default {DEFAULT_ROOT})"
    )
    args = parser.parse_args(argv)
    env = os.environ if environ is None else environ
    reporter = env.get(REPORTER_TOKEN, "")
    agent = env.get(AGENT_TOKEN, "")
    for variable, value in ((REPORTER_TOKEN, reporter), (AGENT_TOKEN, agent)):
        if not value:
            print(f"smoke: set {variable}", file=sys.stderr)
            return EXIT_USAGE
    try:
        service = Service(args.root)
    except ValueError as error:
        print(f"smoke: {error}", file=sys.stderr)
        return EXIT_USAGE

    golden_bytes = GOLDEN_PATH.read_bytes()
    golden = json.loads(golden_bytes)
    checks: list[tuple[str, Callable[[], None]]] = [
        ("healthz answers without a token", lambda: _health(service)),
        ("whoami names the reporter", lambda: _whoami(service, reporter, "reporter")),
        ("whoami names the agent", lambda: _whoami(service, agent, "agent")),
        ("an unknown token gets 401", lambda: _unknown_token(service)),
        ("the reporter cannot list", lambda: _reporter_cannot_list(service, reporter)),
        ("the golden report is created", lambda: _create(service, reporter, golden_bytes, golden)),
        ("the agent lists it", lambda: _listed(service, agent, golden)),
        ("the agent reads it", lambda: _read(service, agent, golden)),
        ("its screenshot matches", lambda: _screenshot(service, agent, golden)),
        ("the agent claims it", lambda: _claim(service, agent, golden)),
        ("a second claim conflicts", lambda: _claim_conflict(service, agent, golden)),
        ("a repeat POST is 200", lambda: _repeat(service, reporter, golden_bytes, golden)),
        ("the agent resolves it", lambda: _resolve(service, agent, golden)),
    ]
    for name, check in checks:
        try:
            check()
        except SmokeFailure as error:
            print(f"smoke: FAILED {name}: {error}", file=sys.stderr)
            return EXIT_FAILURE
        print(f"ok {name}", flush=True)
    print(f"smoke: all {len(checks)} checks passed")
    return EXIT_OK


def _health(service: Service) -> None:
    response = _expect(service.request("GET", "/healthz"), http.client.OK)
    _equal("body", response.json(), {"ok": True})


def _whoami(service: Service, token: str, role: str) -> None:
    response = _expect(service.request("GET", "/v1/whoami", token=token), http.client.OK)
    _equal("body", response.json(), {"service": SERVICE_NAME, "api": API_VERSION, "role": role})


def _unknown_token(service: Service) -> None:
    response = service.request("GET", "/v1/whoami", token=_UNKNOWN_TOKEN)
    _expect(response, http.client.UNAUTHORIZED)
    if not response.headers.get("WWW-Authenticate", "").startswith("Bearer "):
        raise SmokeFailure("no Bearer WWW-Authenticate header")


def _reporter_cannot_list(service: Service, reporter: str) -> None:
    _expect(service.request("GET", "/v1/reports", token=reporter), http.client.FORBIDDEN)


def _create(service: Service, reporter: str, body: bytes, golden: dict[str, Any]) -> None:
    response = service.request("POST", "/v1/reports", token=reporter, body=body)
    if response.status == http.client.OK:
        raise SmokeFailure("the report already exists: start the service with empty data")
    created = _expect(response, http.client.CREATED).json()
    _equal("id", created.get("id"), golden["id"])
    _equal("status", created.get("status"), "open")


def _listed(service: Service, agent: str, golden: dict[str, Any]) -> None:
    response = service.request("GET", "/v1/reports?status=open&limit=100", token=agent)
    listing = _expect(response, http.client.OK).json()
    ids = [report.get("id") for report in listing.get("reports", [])]
    if golden["id"] not in ids:
        raise SmokeFailure("the report is not in the open list")


def _read(service: Service, agent: str, golden: dict[str, Any]) -> None:
    path = f"/v1/reports/{golden['id']}"
    report = _expect(service.request("GET", path, token=agent), http.client.OK).json()
    expected_screenshot = {k: v for k, v in golden["screenshot"].items() if k != "data"}
    for key in ("created_at", "page", "comment", "client", "screenshot_omitted"):
        _equal(key, report.get(key), golden[key])
    _equal("screenshot", report.get("screenshot"), expected_screenshot)
    _equal("status", report.get("status"), "open")
    _equal("purged", report.get("purged"), False)
    history = [(entry.get("status"), entry.get("actor")) for entry in report.get("history", [])]
    _equal("history", history, [("open", "reporter")])


def _screenshot(service: Service, agent: str, golden: dict[str, Any]) -> None:
    path = f"/v1/reports/{golden['id']}/screenshot"
    response = _expect(service.request("GET", path, token=agent), http.client.OK)
    _equal("Content-Type", response.headers.get("Content-Type"), _PNG_TYPE)
    expected = base64.b64decode(golden["screenshot"]["data"], validate=True)
    _equal("Content-Length", response.headers.get("Content-Length"), str(len(expected)))
    _equal("sha256", hashlib.sha256(response.body).hexdigest(), golden["screenshot"]["sha256"])
    if response.body != expected:
        raise SmokeFailure("the screenshot differs from the one sent")


def _claim(service: Service, agent: str, golden: dict[str, Any]) -> None:
    report = _set_status(service, agent, golden, http.client.OK, "claimed", "open", _CLAIM_NOTE)
    _equal("status", report.get("status"), "claimed")
    _equal("status_note", report.get("status_note"), _CLAIM_NOTE)


def _claim_conflict(service: Service, agent: str, golden: dict[str, Any]) -> None:
    body = _set_status(service, agent, golden, http.client.CONFLICT, "claimed", "open", None)
    _equal("body", body, {"error": "status_conflict", "status": "claimed"})


def _repeat(service: Service, reporter: str, body: bytes, golden: dict[str, Any]) -> None:
    response = service.request("POST", "/v1/reports", token=reporter, body=body)
    repeated = _expect(response, http.client.OK).json()
    _equal("id", repeated.get("id"), golden["id"])
    # A phone retry never reopens a report an agent has claimed.
    _equal("status", repeated.get("status"), "claimed")


def _resolve(service: Service, agent: str, golden: dict[str, Any]) -> None:
    report = _set_status(service, agent, golden, http.client.OK, "done", None, _DONE_NOTE)
    _equal("status", report.get("status"), "done")


def _set_status(
    service: Service,
    agent: str,
    golden: dict[str, Any],
    expected_status: int,
    status: str,
    expect: str | None,
    note: str | None,
) -> Any:
    change: dict[str, str] = {"status": status}
    if expect is not None:
        change["expect"] = expect
    if note is not None:
        change["note"] = note
    path = f"/v1/reports/{golden['id']}/status"
    body = json.dumps(change).encode("utf-8")
    return _expect(service.request("POST", path, token=agent, body=body), expected_status).json()


def _expect(response: Response, status: int) -> Response:
    if response.status != status:
        raise SmokeFailure(f"status {response.status} ({response.error()}), expected {status}")
    return response


def _equal(name: str, actual: Any, expected: Any) -> None:
    if actual != expected:
        raise SmokeFailure(f"{name} is {actual!r}, expected {expected!r}")


def _check_common_headers(response: Response, request: str) -> None:
    """Headers every response carries, which a TLS proxy in front keeps."""
    for header, value in (("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")):
        if response.headers.get(header) != value:
            raise SmokeFailure(f"{request}: {header} is not {value}")


if __name__ == "__main__":
    sys.exit(main())
