"""Command line: ``python -m site_reports serve | token | healthcheck | sweep | backup <path>``.

Exit status: 0 on success, 1 on a failure, 2 for a configuration error (the
message names the variable, never its value). ``healthcheck`` exits 0 or 1 only,
because Docker reserves 2.
"""

from __future__ import annotations

import argparse
import errno
import http.client
import logging
import os
import signal
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path

from site_reports import api
from site_reports.config import (
    DATA_DIR,
    Config,
    ConfigError,
    listen_address,
    new_token,
)
from site_reports.store import IncompatibleDatabase, Store, utc_now

EXIT_OK = 0
EXIT_FAILURE = 1
EXIT_CONFIG = 2

_HEALTHCHECK_TIMEOUT_SECONDS = 4.0  # Inside the image's 5 s HEALTHCHECK timeout.
_HEALTHCHECK_PATH = "/healthz"
_WILDCARD_TO_LOOPBACK = {"0.0.0.0": "127.0.0.1", "::": "::1"}
_LOG_FORMAT = "%(asctime)s %(levelname)s %(message)s"
_LOG = logging.getLogger("site_reports")


def main(argv: Sequence[str] | None = None, environ: Mapping[str, str] | None = None) -> int:
    args = _parser().parse_args(argv)
    env = os.environ if environ is None else environ
    if args.command == "token":
        return _token()
    if args.command == "healthcheck":
        return _healthcheck(env)

    try:
        config = Config.from_env(env)
    except ConfigError as error:
        _fail(f"configuration error: {error}")
        return EXIT_CONFIG
    commands: dict[str, Callable[[], int]] = {
        "serve": lambda: _serve(config),
        "sweep": lambda: _sweep(config),
        "backup": lambda: _backup(config, args.target),
    }
    try:
        return commands[args.command]()
    except IncompatibleDatabase:
        _fail(f"the database in {DATA_DIR} has a schema this version does not know")
        return EXIT_CONFIG
    except OSError as error:
        reason = errno.errorcode.get(error.errno or 0, "")
        _fail(f"{args.command} failed: {type(error).__name__} {reason}".rstrip())
        return EXIT_FAILURE


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m site_reports", description="Combray's site-report service."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("serve", help="run the HTTP service")
    commands.add_parser(
        "token", help="print a new token and the SHA-256 that the configuration holds"
    )
    commands.add_parser("healthcheck", help="exit 0 when the local service answers /healthz")
    commands.add_parser("sweep", help="apply retention and remove stray files now")
    backup = commands.add_parser(
        "backup", help="copy the database and screenshots into a new directory"
    )
    backup.add_argument("target", type=Path, help="directory to create, such as /data/backup-1")
    return parser


def _token() -> int:
    token, digest = new_token()
    print(f"token={token}")
    print(f"sha256={digest}")
    return EXIT_OK


def _healthcheck(environ: Mapping[str, str]) -> int:
    try:
        host, port = listen_address(environ)
    except ConfigError as error:
        _fail(f"configuration error: {error}")
        return EXIT_FAILURE
    connection = http.client.HTTPConnection(
        _WILDCARD_TO_LOOPBACK.get(host, host), port, timeout=_HEALTHCHECK_TIMEOUT_SECONDS
    )
    try:
        connection.request("GET", _HEALTHCHECK_PATH)
        status = connection.getresponse().status
    except (OSError, http.client.HTTPException) as error:
        _fail(f"unhealthy: no answer ({type(error).__name__})")
        return EXIT_FAILURE
    finally:
        connection.close()
    if status != http.client.OK:
        _fail(f"unhealthy: status {status}")
        return EXIT_FAILURE
    return EXIT_OK


def _serve(config: Config) -> int:
    logging.basicConfig(level=logging.INFO, format=_LOG_FORMAT, stream=sys.stderr)
    with Store(config.data_dir, utc_now, config.limits) as store:
        server = api.make_server(config, store, utc_now)
        with server:
            server.start_sweeping()
            host, port = server.server_address[:2]
            _LOG.info("listening on %s:%d", host, port)
            # Docker stops the container with SIGTERM, and PID 1 ignores a signal
            # without a handler; this one ends serve_forever like Ctrl-C.
            signal.signal(signal.SIGTERM, signal.default_int_handler)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                _LOG.info("stopping")
    return EXIT_OK


def _sweep(config: Config) -> int:
    with Store(config.data_dir, utc_now, config.limits) as store:
        result = store.sweep()
    print(
        f"purged={result.purged} expired={result.expired}"
        f" orphans={result.orphans} staging={result.staging}"
    )
    return EXIT_OK


def _backup(config: Config, target: Path) -> int:
    with Store(config.data_dir, utc_now, config.limits) as store:
        try:
            result = store.backup(target)
        except FileExistsError:
            _fail("backup: the target already exists; name a new directory")
            return EXIT_FAILURE
    print(f"reports={result.reports} screenshots={result.screenshots} missing={result.missing}")
    return EXIT_OK


def _fail(message: str) -> None:
    print(f"site_reports: {message}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
