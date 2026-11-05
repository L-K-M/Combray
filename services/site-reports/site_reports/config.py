"""Service configuration from the environment, and the bearer tokens' roles.

The server stores no token, only the SHA-256 hex of each (tokens are 256-bit
random values, not passwords, so an unsalted hash is enough). Each role takes a
comma-separated list, so a new token can be added before the old one is removed.

A configuration error names the variable and never its value: a token pasted
into a hash variable must not end up in a terminal or a log.
"""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import os
import re
import secrets
from collections.abc import Collection, Mapping
from dataclasses import dataclass, field
from datetime import timedelta
from enum import StrEnum
from pathlib import Path

from site_reports.store import Limits

REPORTER_TOKENS = "REPORTS_REPORTER_TOKEN_SHA256"
AGENT_TOKENS = "REPORTS_AGENT_TOKEN_SHA256"
DATA_DIR = "REPORTS_DATA_DIR"
LISTEN_HOST = "REPORTS_LISTEN_HOST"
LISTEN_PORT = "REPORTS_LISTEN_PORT"
MAX_REQUEST_MB = "REPORTS_MAX_REQUEST_MB"
MAX_SCREENSHOT_MB = "REPORTS_MAX_SCREENSHOT_MB"
MAX_STORAGE_MB = "REPORTS_MAX_STORAGE_MB"
MAX_REPORTS_PER_HOUR = "REPORTS_MAX_REPORTS_PER_HOUR"
RESOLVED_RETENTION_DAYS = "REPORTS_RESOLVED_RETENTION_DAYS"
TOMBSTONE_RETENTION_DAYS = "REPORTS_TOMBSTONE_RETENTION_DAYS"

MIB = 1024 * 1024
DEFAULT_DATA_DIR = Path("/data")
# Loopback outside the container; the image sets 0.0.0.0, as the container's
# port is only published on the address Compose names.
DEFAULT_LISTEN_HOST = "127.0.0.1"
DEFAULT_LISTEN_PORT = 8787

_TOKEN_BYTES = 32
_SHA256_HEX = re.compile(r"[0-9a-fA-F]{64}")
# RFC 6750 section 2.1: the scheme is case-insensitive, the token is a b64token.
_BEARER = re.compile(r"[Bb][Ee][Aa][Rr][Ee][Rr] +([A-Za-z0-9\-._~+/]+=*) *")
_WHOLE_NUMBER = re.compile(r"[0-9]{1,9}")


class Role(StrEnum):
    REPORTER = "reporter"
    AGENT = "agent"


class ConfigError(Exception):
    """A missing or invalid variable; the message names it but not its value."""

    def __init__(self, message: str, *variables: str) -> None:
        super().__init__(message)
        self.variables = variables


@dataclass(frozen=True, slots=True)
class Credential:
    """An authenticated caller.

    ``key`` is the SHA-256 hex of the presented token: it tells reporter tokens
    apart for the rate limit without keeping the token.
    """

    role: Role
    key: str = field(repr=False)


class Tokens:
    """The configured token hashes of both roles."""

    __slots__ = ("_entries",)

    def __init__(self, reporter: Collection[str], agent: Collection[str]) -> None:
        reporter_digests = _digests(reporter)
        agent_digests = _digests(agent)
        if not reporter_digests or not agent_digests:
            raise ValueError("each role needs at least one token hash")
        if reporter_digests & agent_digests:
            raise ValueError("a token hash belongs to both roles")
        self._entries = tuple(
            [(Role.REPORTER, digest) for digest in sorted(reporter_digests)]
            + [(Role.AGENT, digest) for digest in sorted(agent_digests)]
        )

    def __repr__(self) -> str:
        counts = {role: sum(1 for entry, _ in self._entries if entry is role) for role in Role}
        return f"Tokens(reporter={counts[Role.REPORTER]}, agent={counts[Role.AGENT]})"

    def authenticate(self, authorization: str | None) -> Credential | None:
        """The caller behind an ``Authorization`` header value, or None.

        The presented token is hashed once and compared with every configured
        hash, without stopping at a match, so the time taken does not reveal the
        role or where the hash sits in the list.
        """
        if authorization is None:
            return None
        match = _BEARER.fullmatch(authorization)
        if match is None:
            return None
        digest = hashlib.sha256(match.group(1).encode("ascii")).digest()
        found: Role | None = None
        for role, known in self._entries:
            if hmac.compare_digest(digest, known):
                found = role
        return None if found is None else Credential(found, digest.hex())


@dataclass(frozen=True, slots=True)
class Config:
    tokens: Tokens
    data_dir: Path = DEFAULT_DATA_DIR
    listen_host: str = DEFAULT_LISTEN_HOST
    listen_port: int = DEFAULT_LISTEN_PORT
    max_request_bytes: int = 12 * MIB
    max_screenshot_bytes: int = 8 * MIB
    max_reports_per_hour: int = 60
    limits: Limits = field(default_factory=Limits)

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> Config:
        """Read the configuration; raises ConfigError for a missing or invalid variable."""
        env = os.environ if environ is None else environ
        reporter = _hash_list(env, REPORTER_TOKENS)
        agent = _hash_list(env, AGENT_TOKENS)
        if set(reporter) & set(agent):
            raise ConfigError(
                f"{REPORTER_TOKENS} and {AGENT_TOKENS} share a hash: give each role its own token",
                REPORTER_TOKENS,
                AGENT_TOKENS,
            )

        host, port = listen_address(env)
        max_request = _whole_number(env, MAX_REQUEST_MB, default=12, maximum=64) * MIB
        max_screenshot = _whole_number(env, MAX_SCREENSHOT_MB, default=8, maximum=47) * MIB
        if _base64_length(max_screenshot) >= max_request:
            raise ConfigError(
                f"{MAX_REQUEST_MB} must exceed the base64 size of {MAX_SCREENSHOT_MB}"
                " (4/3 of it) to leave room for the rest of a report",
                MAX_REQUEST_MB,
                MAX_SCREENSHOT_MB,
            )

        limits = Limits(
            max_storage_bytes=_whole_number(env, MAX_STORAGE_MB, default=1024, maximum=2**20) * MIB,
            resolved_retention=timedelta(
                days=_whole_number(env, RESOLVED_RETENTION_DAYS, default=30, maximum=3650)
            ),
            tombstone_retention=timedelta(
                days=_whole_number(env, TOMBSTONE_RETENTION_DAYS, default=365, maximum=3650)
            ),
        )
        return cls(
            tokens=Tokens(reporter, agent),
            data_dir=_data_dir(env),
            listen_host=host,
            listen_port=port,
            max_request_bytes=max_request,
            max_screenshot_bytes=max_screenshot,
            max_reports_per_hour=_whole_number(
                env, MAX_REPORTS_PER_HOUR, default=60, maximum=10_000
            ),
            limits=limits,
        )


def listen_address(environ: Mapping[str, str]) -> tuple[str, int]:
    """The server's listen host and port; port 0 picks a free one (tests)."""
    host = _value(environ, LISTEN_HOST) or DEFAULT_LISTEN_HOST
    try:
        ipaddress.ip_address(host)
    except ValueError:
        raise ConfigError(f"{LISTEN_HOST} must be an IP address", LISTEN_HOST) from None
    port = _whole_number(
        environ, LISTEN_PORT, default=DEFAULT_LISTEN_PORT, minimum=0, maximum=65535
    )
    return host, port


def hash_token(token: str) -> str:
    """The SHA-256 hex that a hash variable holds for ``token``."""
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def new_token() -> tuple[str, str]:
    """A fresh 256-bit token (URL-safe base64) and its SHA-256 hex."""
    token = secrets.token_urlsafe(_TOKEN_BYTES)
    return token, hash_token(token)


def _digests(hashes: Collection[str]) -> frozenset[bytes]:
    for value in hashes:
        if _SHA256_HEX.fullmatch(value) is None:
            raise ValueError("a token hash is not 64 hexadecimal digits")
    return frozenset(bytes.fromhex(value) for value in hashes)


def _hash_list(environ: Mapping[str, str], variable: str) -> list[str]:
    value = _value(environ, variable)
    if not value:
        raise ConfigError(
            f"{variable} is empty: set it to the SHA-256 of a token (python -m site_reports token)",
            variable,
        )
    entries = [entry.strip() for entry in value.split(",")]
    for position, entry in enumerate(entries, start=1):
        if _SHA256_HEX.fullmatch(entry) is None:
            raise ConfigError(
                f"{variable}: entry {position} is not 64 hexadecimal digits"
                " (the SHA-256 of a token, not the token)",
                variable,
            )
    return [entry.lower() for entry in entries]


def _whole_number(
    environ: Mapping[str, str], variable: str, *, default: int, minimum: int = 1, maximum: int
) -> int:
    value = _value(environ, variable)
    if not value:
        return default
    if _WHOLE_NUMBER.fullmatch(value) is None or not minimum <= int(value) <= maximum:
        raise ConfigError(
            f"{variable} must be a whole number from {minimum} to {maximum}", variable
        )
    return int(value)


def _data_dir(environ: Mapping[str, str]) -> Path:
    value = _value(environ, DATA_DIR)
    if not value:
        return DEFAULT_DATA_DIR
    path = Path(value)
    if not path.is_absolute():
        raise ConfigError(f"{DATA_DIR} must be an absolute path", DATA_DIR)
    return path


def _value(environ: Mapping[str, str], variable: str) -> str:
    """The variable's value without surrounding spaces; empty when unset.

    Compose passes a line such as ``REPORTS_MAX_REQUEST_MB=`` as an empty value,
    which means the default.
    """
    return environ.get(variable, "").strip()


def _base64_length(size: int) -> int:
    return 4 * ((size + 2) // 3)
