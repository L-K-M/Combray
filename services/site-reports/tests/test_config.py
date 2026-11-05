"""Tests for site_reports.config and the command line's configuration handling."""

from __future__ import annotations

import contextlib
import hashlib
import io
import re
import sqlite3
import subprocess
import sys
import unittest
from datetime import timedelta
from pathlib import Path
from unittest import mock

from site_reports import config as config_module
from site_reports.__main__ import main
from site_reports.config import Config, ConfigError, Credential, Role, Tokens, hash_token
from site_reports.store import Limits
from tests import support

REPORTER_HASH = hash_token(support.REPORTER_TOKEN)
AGENT_HASH = hash_token(support.AGENT_TOKEN)
# A distinctive value: no error message may repeat it.
TOKEN_PASTED_AS_HASH = "pasted-token-SECRETVALUE-0001"
MIB = 1024 * 1024


def environment(**values: str) -> dict[str, str]:
    env = {"REPORTS_REPORTER_TOKEN_SHA256": REPORTER_HASH, "REPORTS_AGENT_TOKEN_SHA256": AGENT_HASH}
    env.update(values)
    return env


def run(argv: list[str], environ: dict[str, str]) -> tuple[int, str, str]:
    stdout, stderr = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        status = main(argv, environ)
    return status, stdout.getvalue(), stderr.getvalue()


class FromEnvTest(unittest.TestCase):
    def test_only_the_hashes_are_required(self) -> None:
        config = Config.from_env(environment())

        self.assertEqual(config.data_dir, Path("/data"))
        self.assertEqual((config.listen_host, config.listen_port), ("127.0.0.1", 8787))
        self.assertEqual(config.max_request_bytes, 12 * MIB)
        self.assertEqual(config.max_screenshot_bytes, 8 * MIB)
        self.assertEqual(config.max_reports_per_hour, 60)
        self.assertEqual(config.limits, Limits(1024 * MIB, timedelta(days=30), timedelta(days=365)))

    def test_every_variable_is_read(self) -> None:
        config = Config.from_env(
            environment(
                REPORTS_DATA_DIR="/srv/reports",
                REPORTS_LISTEN_HOST="0.0.0.0",
                REPORTS_LISTEN_PORT="9000",
                REPORTS_MAX_REQUEST_MB="20",
                REPORTS_MAX_SCREENSHOT_MB="12",
                REPORTS_MAX_STORAGE_MB="50",
                REPORTS_MAX_REPORTS_PER_HOUR="5",
                REPORTS_RESOLVED_RETENTION_DAYS="7",
                REPORTS_TOMBSTONE_RETENTION_DAYS="90",
            )
        )

        self.assertEqual(config.data_dir, Path("/srv/reports"))
        self.assertEqual((config.listen_host, config.listen_port), ("0.0.0.0", 9000))
        self.assertEqual(
            (config.max_request_bytes, config.max_screenshot_bytes), (20 * MIB, 12 * MIB)
        )
        self.assertEqual(config.max_reports_per_hour, 5)
        self.assertEqual(config.limits, Limits(50 * MIB, timedelta(days=7), timedelta(days=90)))

    def test_empty_optional_variables_mean_the_default(self) -> None:
        config = Config.from_env(
            environment(REPORTS_MAX_REQUEST_MB="", REPORTS_DATA_DIR=" ", REPORTS_LISTEN_HOST="")
        )

        self.assertEqual(config.max_request_bytes, 12 * MIB)
        self.assertEqual(config.data_dir, Path("/data"))
        self.assertEqual(config.listen_host, "127.0.0.1")

    def test_hash_lists_allow_rotation_case_and_spaces(self) -> None:
        second = hash_token("reporter-token-synthetic-0002")
        config = Config.from_env(
            environment(REPORTS_REPORTER_TOKEN_SHA256=f" {REPORTER_HASH.upper()} , {second} ")
        )

        for token in (support.REPORTER_TOKEN, "reporter-token-synthetic-0002"):
            credential = config.tokens.authenticate(f"Bearer {token}")
            self.assertEqual(credential.role, Role.REPORTER)

    def assert_refused(self, env: dict[str, str], *variables: str) -> str:
        with self.assertRaises(ConfigError) as caught:
            Config.from_env(env)
        message = str(caught.exception)
        self.assertEqual(caught.exception.variables, variables)
        for variable in variables:
            self.assertIn(variable, message)
        for value in env.values():
            if len(value.strip()) > 4:
                self.assertNotIn(value.strip(), message)
        return message

    def test_missing_or_empty_hash_lists_stop_start_up(self) -> None:
        for variable in ("REPORTS_REPORTER_TOKEN_SHA256", "REPORTS_AGENT_TOKEN_SHA256"):
            for value in (None, "", "  "):
                with self.subTest(variable=variable, value=value):
                    env = environment()
                    if value is None:
                        del env[variable]
                    else:
                        env[variable] = value

                    self.assert_refused(env, variable)

    def test_malformed_hashes_stop_start_up_without_echoing_the_value(self) -> None:
        for value in (
            TOKEN_PASTED_AS_HASH,
            REPORTER_HASH[:-1],
            REPORTER_HASH + "0",
            "g" * 64,
            f"{REPORTER_HASH},",
            f"{REPORTER_HASH};{REPORTER_HASH}",
        ):
            with self.subTest(value=value):
                message = self.assert_refused(
                    environment(REPORTS_REPORTER_TOKEN_SHA256=value),
                    "REPORTS_REPORTER_TOKEN_SHA256",
                )

                self.assertNotIn(value, message)

    def test_the_failing_entry_is_named_by_position(self) -> None:
        message = self.assert_refused(
            environment(REPORTS_AGENT_TOKEN_SHA256=f"{AGENT_HASH},{TOKEN_PASTED_AS_HASH}"),
            "REPORTS_AGENT_TOKEN_SHA256",
        )

        self.assertIn("entry 2", message)

    def test_a_hash_in_both_roles_stops_start_up(self) -> None:
        self.assert_refused(
            environment(REPORTS_AGENT_TOKEN_SHA256=f"{AGENT_HASH},{REPORTER_HASH.upper()}"),
            "REPORTS_REPORTER_TOKEN_SHA256",
            "REPORTS_AGENT_TOKEN_SHA256",
        )

    def test_numbers_out_of_range_stop_start_up(self) -> None:
        cases = {
            "REPORTS_MAX_REQUEST_MB": ("0", "65", "-1", "1.5", "12MB", "1234567", "\u0661\u0662"),
            "REPORTS_MAX_SCREENSHOT_MB": ("0", "48"),
            "REPORTS_MAX_STORAGE_MB": ("0", str(2**20 + 1)),
            "REPORTS_MAX_REPORTS_PER_HOUR": ("0", "10001"),
            "REPORTS_RESOLVED_RETENTION_DAYS": ("0", "3651"),
            "REPORTS_TOMBSTONE_RETENTION_DAYS": ("0", "3651"),
            "REPORTS_LISTEN_PORT": ("65536", "http"),
        }
        for variable, values in cases.items():
            for value in values:
                with self.subTest(variable=variable, value=value):
                    self.assert_refused(environment(**{variable: value}), variable)

    def test_the_request_limit_must_hold_a_full_screenshot(self) -> None:
        self.assert_refused(
            environment(REPORTS_MAX_REQUEST_MB="8", REPORTS_MAX_SCREENSHOT_MB="8"),
            "REPORTS_MAX_REQUEST_MB",
            "REPORTS_MAX_SCREENSHOT_MB",
        )
        Config.from_env(environment(REPORTS_MAX_REQUEST_MB="11", REPORTS_MAX_SCREENSHOT_MB="8"))
        # The largest screenshot limit fits the largest request limit.
        Config.from_env(environment(REPORTS_MAX_REQUEST_MB="64", REPORTS_MAX_SCREENSHOT_MB="47"))

    def test_the_listen_host_must_be_an_ip_address(self) -> None:
        for value in ("localhost", "reports.example", "127.0.0.1:80"):
            with self.subTest(value=value):
                self.assert_refused(environment(REPORTS_LISTEN_HOST=value), "REPORTS_LISTEN_HOST")
        self.assertEqual(Config.from_env(environment(REPORTS_LISTEN_HOST="::1")).listen_host, "::1")

    def test_the_data_directory_must_be_absolute(self) -> None:
        self.assert_refused(environment(REPORTS_DATA_DIR="data"), "REPORTS_DATA_DIR")


class TokensTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tokens = support.tokens()

    def test_each_token_maps_to_its_role(self) -> None:
        reporter = self.tokens.authenticate(f"Bearer {support.REPORTER_TOKEN}")
        agent = self.tokens.authenticate(f"Bearer {support.AGENT_TOKEN}")

        self.assertEqual(reporter, Credential(Role.REPORTER, REPORTER_HASH))
        self.assertEqual(agent, Credential(Role.AGENT, AGENT_HASH))

    def test_anything_but_a_known_bearer_token_is_refused(self) -> None:
        for header in (
            None,
            "",
            "Bearer",
            "Bearer ",
            "Bearer unknown-token",
            f"Basic {support.REPORTER_TOKEN}",
            support.REPORTER_TOKEN,
            f"Bearer {support.REPORTER_TOKEN} extra",
            f"Bearer {support.REPORTER_TOKEN},",
            f"Bearer\t{support.REPORTER_TOKEN}",
            f"Bearer {REPORTER_HASH}",
            f"Bearer {support.REPORTER_TOKEN}é",
        ):
            with self.subTest(header=header):
                self.assertIsNone(self.tokens.authenticate(header))

    def test_the_scheme_is_case_insensitive(self) -> None:
        credential = self.tokens.authenticate(f"BEARER  {support.AGENT_TOKEN} ")

        self.assertEqual(credential.role, Role.AGENT)

    def test_every_hash_is_compared_whatever_matches(self) -> None:
        tokens = support.tokens(reporter=("r1", "r2"), agent=("a1", support.AGENT_TOKEN, "a3"))
        for header in ("Bearer r1", "Bearer a3", "Bearer nobody"):
            with self.subTest(header=header):
                with mock.patch.object(
                    config_module.hmac, "compare_digest", wraps=config_module.hmac.compare_digest
                ) as compare:
                    tokens.authenticate(header)

                self.assertEqual(compare.call_count, 5)

    def test_tokens_need_both_roles_and_disjoint_valid_hashes(self) -> None:
        for reporter, agent in (
            ([], [AGENT_HASH]),
            ([REPORTER_HASH], []),
            ([REPORTER_HASH], [REPORTER_HASH]),
            (["not a hash"], [AGENT_HASH]),
        ):
            with self.subTest(reporter=reporter, agent=agent), self.assertRaises(ValueError):
                Tokens(reporter, agent)

    def test_repr_shows_counts_only(self) -> None:
        self.assertEqual(repr(self.tokens), "Tokens(reporter=1, agent=1)")
        self.assertNotIn(REPORTER_HASH, repr(Credential(Role.REPORTER, REPORTER_HASH)))


class CommandLineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = support.temporary_directory(self)
        self.data = self.root / "data"
        self.env = environment(REPORTS_DATA_DIR=str(self.data))

    def test_token_prints_a_token_and_its_hash(self) -> None:
        status, output, _ = run(["token"], {})

        self.assertEqual(status, 0)
        match = re.fullmatch(r"token=([A-Za-z0-9_-]{43})\nsha256=([0-9a-f]{64})\n", output)
        self.assertIsNotNone(match, output)
        self.assertEqual(hashlib.sha256(match[1].encode()).hexdigest(), match[2])
        self.assertNotEqual(run(["token"], {})[1], output)

    def test_token_loads_no_configuration(self) -> None:
        status, output, errors = run(
            ["token"], {"REPORTS_REPORTER_TOKEN_SHA256": TOKEN_PASTED_AS_HASH}
        )

        self.assertEqual((status, errors), (0, ""))
        self.assertTrue(output.startswith("token="))

    def test_python_m_site_reports_token_runs(self) -> None:
        result = subprocess.run(
            [sys.executable, "-E", "-s", "-B", "-m", "site_reports", "token"],
            cwd=support.SERVICE_ROOT,
            env={},
            capture_output=True,
            text=True,
            timeout=support.WAIT_SECONDS * 4,
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        token, digest = (line.split("=", 1)[1] for line in result.stdout.splitlines())
        self.assertEqual(hash_token(token), digest)

    def test_a_configuration_error_exits_2_naming_the_variable_only(self) -> None:
        env = environment(REPORTS_AGENT_TOKEN_SHA256=TOKEN_PASTED_AS_HASH)
        for command in (["serve"], ["sweep"], ["backup", str(self.root / "backup")]):
            with self.subTest(command=command[0]):
                status, output, errors = run(command, env)

                self.assertEqual(status, 2)
                self.assertEqual(output, "")
                self.assertIn("REPORTS_AGENT_TOKEN_SHA256", errors)
                self.assertNotIn(TOKEN_PASTED_AS_HASH, errors)
        self.assertFalse(self.data.exists())

    def test_an_unknown_database_version_exits_2(self) -> None:
        support.open_store(self, self.data, support.FakeClock()).close()
        connection = sqlite3.connect(self.data / "reports.sqlite3")
        connection.execute("PRAGMA user_version = 9")
        connection.close()

        status, _, errors = run(["sweep"], self.env)

        self.assertEqual(status, 2)
        self.assertIn("schema", errors)

    def test_sweep_applies_retention_and_reports_counts(self) -> None:
        self.data.mkdir()
        (self.data / "tmp").mkdir()
        (self.data / "tmp" / "upload.partial").write_bytes(b"x")

        status, output, errors = run(["sweep"], self.env)

        self.assertEqual((status, errors), (0, ""))
        self.assertEqual(output, "purged=0 expired=0 orphans=0 staging=1\n")
        self.assertEqual(list((self.data / "tmp").iterdir()), [])

    def test_backup_copies_into_a_new_directory_once(self) -> None:
        store = support.open_store(self, self.data, support.FakeClock())
        store.add(support.report())
        store.close()
        target = self.root / "backup"

        first = run(["backup", str(target)], self.env)
        second = run(["backup", str(target)], self.env)

        self.assertEqual(first, (0, "reports=1 screenshots=1 missing=0\n", ""))
        self.assertTrue((target / "reports.sqlite3").is_file())
        self.assertEqual(second[0], 1)
        self.assertIn("already exists", second[2])

    def test_an_os_error_exits_1_with_its_class_and_errno_only(self) -> None:
        status, _, errors = run(["backup", str(self.root / "missing" / "backup")], self.env)

        self.assertEqual(status, 1)
        self.assertEqual(errors, "site_reports: backup failed: FileNotFoundError ENOENT\n")

    def test_healthcheck_never_exits_2(self) -> None:
        status, _, errors = run(["healthcheck"], {"REPORTS_LISTEN_PORT": "not-a-port"})

        self.assertEqual(status, 1)
        self.assertIn("REPORTS_LISTEN_PORT", errors)
        self.assertNotIn("not-a-port", errors)

    def test_usage_errors_exit_2(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            main(["backup"], {})

        self.assertEqual(caught.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
