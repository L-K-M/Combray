"""Tests for sitecheck.cli: argument handling, exit codes and command flows, with fake engines.

The only server involved is a fake Hister on 127.0.0.1; no engine process starts.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from unittest import mock

from sitecheck import cli, config, hister, reference, runner, store
from tests import fakes

NEWS = "https://www.example-news.org/"
NEWS_STORY = "https://www.example-news.org/2026/10/story-of-the-day"
SHOP = "https://shop.example-store.net/"
TOKEN = "tok-very-secret-test-token-1234"
USER_AGENT = config.user_agent("0.6.0")

_GIT_ENV = {
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@example.com",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@example.com",
}


class CliTestCase(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = self.root / "data"
        self.config = self.root / "config"
        self.config.mkdir()
        (self.root / "home").mkdir()
        self.binary = self.root / "servoshell"
        self.binary.write_text("#!/bin/sh\necho 'Version: Servo 0.6.0-test'\n")
        self.binary.chmod(0o755)
        self.environ = {
            config.DATA_DIR_ENV: str(self.data),
            config.CONFIG_DIR_ENV: str(self.config),
            "HOME": str(self.root / "home"),
            "PATH": os.environ.get("PATH", ""),
        }
        self.servo = fakes.FakeServo()
        self.reference = fakes.FakeReference()
        self.images_available = True
        self.clock = fakes.StepClock()  # Shared, so later commands see later times.
        self.reference_policies: list[reference.CachePolicy] = []
        # Every test host resolves to a public address unless a test says otherwise.
        self.addresses: dict[str, list[str]] = {}
        self.exposure: str | None = None

    def open_reference(self, data: Path, user_agent: str, policy: reference.CachePolicy) -> Any:
        self.reference_policies.append(policy)
        return fakes.reference_context(self.reference)

    def resolve(self, host: str) -> list[str]:
        return self.addresses.get(host, ["93.184.215.14"])

    def runtime(self) -> cli.Runtime:
        return cli.Runtime(
            environ=self.environ,
            stdout=io.StringIO(),
            stderr=io.StringIO(),
            now=self.clock,
            servo_identity=lambda binary: cli.ServoIdentity("0.6.0", USER_AGENT, "abc123"),
            servo_engine=lambda binary, user_agent: self.servo,
            open_reference=self.open_reference,
            compare_images=fakes.fake_images,
            images_available=lambda: self.images_available,
            browser_version=lambda: "chromium 156.0",
            resolve=self.resolve,
            webdriver_exposure=lambda binary, user_agent: self.exposure,
            handle_signals=False,
        )

    def sitecheck(self, *argv: str) -> tuple[int, str, str]:
        rt = self.runtime()
        code = cli.main(list(argv), rt)
        assert isinstance(rt.stdout, io.StringIO) and isinstance(rt.stderr, io.StringIO)
        return code, rt.stdout.getvalue(), rt.stderr.getvalue()

    def seed(self, *urls: str) -> None:
        seeds = self.root / "seeds.txt"
        seeds.write_text("# test seeds\n" + "\n".join(urls) + "\n")
        code, _, err = self.sitecheck("seed", "--file", str(seeds))
        self.assertEqual(code, 0, err)

    def open_store(self) -> store.Store:
        db = store.open_store(config.state_db_path(self.data))
        self.addCleanup(db.close)
        return db


class ArgumentTest(CliTestCase):
    def test_usage_errors_exit_2(self) -> None:
        for argv in (
            [],
            ["nope"],
            ["select", "--budget", "-1"],
            ["select", "--budget", "many"],
            ["run", "--budget", "3", NEWS],
            ["run", "--minutes", "0"],
            # No run's time budget: never ending, or too large for a timedelta.
            *(["run", "--servo", str(self.binary), "--minutes", m, NEWS] for m in ("inf", "nan")),
            ["run", "--servo", str(self.binary), "--minutes", "1e15", NEWS],
            ["check"],
            ["check", NEWS, "--repeat", "0"],
            ["mark", "hang"],
            ["mark", "hang", "--status", "done"],
            ["leakcheck"],
        ):
            with self.subTest(argv=argv):
                code, _, err = self.sitecheck(*argv)
                self.assertEqual(code, cli.EXIT_USAGE)
                self.assertIn("error:", err)

    def test_help_exits_0(self) -> None:
        with mock.patch("sys.stdout", new=io.StringIO()) as out:
            self.assertEqual(self.sitecheck("--help")[0], 0)
        self.assertIn("doctor", out.getvalue())

    def test_module_entry_point(self) -> None:
        package_root = Path(cli.__file__).resolve().parent.parent
        result = subprocess.run(
            [
                os.sys.executable,
                "-I",
                "-c",
                f"import sys; sys.path.insert(0, {str(package_root)!r}); "
                "sys.argv = ['sitecheck', '--help']; "
                "import runpy; runpy.run_module('sitecheck', run_name='__main__')",
            ],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("usage: sitecheck", result.stdout)


class DoctorTest(CliTestCase):
    def write_hister_env(self, url: str) -> None:
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL={url}\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o600)

    def test_reports_hister_by_host_only(self) -> None:
        self.write_hister_env("https://hister.example-host.net/secret-path")

        code, out, err = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))

        self.assertEqual(code, 0, out + err)
        self.assertIn("ok    hister: configured for hister.example-host.net (not contacted", out)
        self.assertIn("Version: Servo 0.6.0-test", out)
        self.assertIn("ok    chromium: chromium 156.0", out)
        for secret in (TOKEN, "secret-path"):
            self.assertNotIn(secret, out + err)
        self.assertFalse(self.data.exists())  # Doctor only looks.

    def test_an_unusable_hister_url_fails_without_echoing_it(self) -> None:
        self.write_hister_env("https://hister.example-host.net/secret-path?k=secret-value")
        code, out, err = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("FAIL  hister: configured but unusable", out)
        for secret in (TOKEN, "secret-path", "secret-value"):
            self.assertNotIn(secret, out + err)

    def test_fails_without_a_servo_binary_or_pillow(self) -> None:
        self.images_available = False
        code, out, _ = self.sitecheck("doctor", "--offline")
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("FAIL  servo: no servoshell found", out)
        self.assertIn("FAIL  pillow", out)
        self.assertIn("warn  hister: not configured", out)

    def test_fails_when_servoshells_webdriver_port_answers_on_the_network(self) -> None:
        self.exposure = "192.168.1.5:4444"
        code, out, _ = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("FAIL  webdriver: servoshell's WebDriver server answers at 192.168.1.5", out)

        self.exposure = None
        code, out, _ = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))
        self.assertIn("ok    webdriver: answers on loopback only", out)

    def test_a_damaged_store_fails_its_check_and_the_other_checks_still_run(self) -> None:
        db_path = config.state_db_path(self.data)

        def garbage() -> None:
            db_path.write_bytes(b"not a database " * 100)

        def newer_schema() -> None:
            with contextlib.closing(sqlite3.connect(db_path)) as connection:
                connection.execute("PRAGMA user_version = 999")

        config.ensure_private_dir(self.data)
        for damage in (garbage, newer_schema):
            with self.subTest(damage=damage.__name__):
                db_path.unlink(missing_ok=True)
                damage()

                code, out, err = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))

                self.assertEqual(code, cli.EXIT_FAILURE, out + err)
                self.assertIn(f"FAIL  store: cannot read {db_path}", out)
                for later in ("servo:", "chromium:", "disk:"):
                    self.assertIn(later, out)

    def test_warns_about_a_readable_token_file(self) -> None:
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL=https://hister.example-host.net\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o644)
        code, out, err = self.sitecheck("doctor", "--offline", "--servo", str(self.binary))
        self.assertEqual(code, 0)
        self.assertIn("warn  hister: warning:", out)
        self.assertNotIn(TOKEN, out + err)


class RunFlowTest(CliTestCase):
    def test_seed_select_run_issues_mark_report(self) -> None:
        self.seed(NEWS, NEWS_STORY, SHOP, "https://www.example-news.org/logout")

        code, out, err = self.sitecheck("select", "--budget", "2", "--json", "--seed", "s")
        self.assertEqual(code, 0, err)
        plan = json.loads(out)
        self.assertEqual(len(plan), 2)
        self.assertEqual({item["bucket"] for item in plan}, {"new"})

        self.servo.script[SHOP] = [fakes.JS_ERROR_LOAD]
        code, out, err = self.sitecheck(
            "run", "--servo", str(self.binary), "--budget", "5", "--label", "nightly"
        )
        self.assertEqual(code, 0, err)
        self.assertIn("3 of 3 pages checked", out)
        self.assertIn("verdicts: js-error 1, ok 2", out)
        report_md = Path(out.split("report: ", 1)[1].splitlines()[0])
        self.assertTrue(report_md.is_file())
        self.assertTrue(report_md.with_name("report.html").is_file())
        self.assertIn("plan: 3 new", err)
        self.assertIn("] js-error shop.example-store.net", err)

        code, out, _ = self.sitecheck("issues", "--json")
        self.assertEqual(code, 0)
        queue = json.loads(out)["issues"]
        self.assertEqual([issue["signature"] for issue in queue], ["js:fakeFn is not defined"])
        self.assertEqual(queue[0]["urls"], [SHOP])

        code, out, _ = self.sitecheck(
            "mark", "js:fakeFn is not defined", "--status", "wontfix", "--note", "site bug"
        )
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(self.sitecheck("issues", "--json")[1])["issues"], [])
        all_issues = json.loads(self.sitecheck("issues", "--json", "--all")[1])["issues"]
        self.assertEqual([issue["status"] for issue in all_issues], ["wontfix"])

        self.assertEqual(
            self.sitecheck("mark", "js:unknown", "--status", "fixed")[0], cli.EXIT_USAGE
        )
        code, out, _ = self.sitecheck("report", "--run", "latest")
        self.assertEqual(code, 0)
        self.assertTrue(out.splitlines()[0].endswith("report.md"))
        self.assertEqual(
            self.sitecheck("report", "--run", "20200101T000000Z-0000")[0], cli.EXIT_USAGE
        )

    def test_named_urls_and_local_files(self) -> None:
        page = self.root / "fixture.html"
        page.write_text("<p>hi</p>")
        code, out, err = self.sitecheck(
            "run",
            "--servo",
            str(self.binary),
            "http://127.0.0.1:8000/case.html?utm_source=x",
            str(page),
        )
        self.assertEqual(code, 0, err)
        self.assertIn("2 of 2 pages checked", out)
        db = self.open_store()
        self.assertIn("127.0.0.1", db.hosts_and_sites().hosts)
        run_id = db.latest_run_id()
        assert run_id is not None
        outcomes = db.outcomes_for_run(run_id)
        self.assertEqual(
            set(outcomes), {"http://127.0.0.1:8000/case.html", page.resolve().as_uri()}
        )
        # A private host is checked when named, but never joins the selection pool.
        self.assertEqual(db.selection_rows(), [])

    def test_named_urls_must_pass_the_safety_rules(self) -> None:
        for url in (
            "https://www.example-news.org/account/logout",
            "https://user:pw@www.example-news.org/",
            "javascript:alert(1)",
            str(self.root / "missing.html"),
            "http://[::1/case.html",  # Not a URL urllib can split.
        ):
            for command in ("run", "check"):
                with self.subTest(url=url, command=command):
                    code, _, err = self.sitecheck(command, "--servo", str(self.binary), url)
                    self.assertEqual(code, cli.EXIT_USAGE)
                    self.assertTrue(
                        "refusing" in err or "no such file" in err or "policy" in err, err
                    )

    def test_a_run_needs_pages_a_binary_and_pillow(self) -> None:
        code, _, err = self.sitecheck("run", "--servo", str(self.binary))
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("no eligible pages", err)

        code, _, err = self.sitecheck("run", "--servo", str(self.root / "nope"), NEWS)
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("not an executable file", err)

        self.images_available = False
        code, _, err = self.sitecheck("run", "--servo", str(self.binary), NEWS)
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("Pillow", err)
        self.assertEqual(
            self.sitecheck("run", "--servo", str(self.binary), "--no-reference", NEWS)[0], 0
        )

    def test_a_run_where_every_page_fails_in_the_harness_exits_1(self) -> None:
        self.servo.default = fakes.ERROR_LOAD
        code, _, err = self.sitecheck("run", "--servo", str(self.binary), NEWS, SHOP)
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("2 of 2 pages could not be judged", err)

    def test_a_run_that_could_not_judge_most_pages_exits_1(self) -> None:
        # No network: neither engine loads anything, so nothing was learned.
        self.reference.failing = frozenset({NEWS, SHOP})
        code, out, err = self.sitecheck("run", "--servo", str(self.binary), NEWS, SHOP)
        self.assertEqual(code, cli.EXIT_FAILURE, out + err)
        self.assertIn("could not be judged", err)

    def test_an_aborted_run_still_gets_its_report(self) -> None:
        self.servo.default = fakes.ERROR_LOAD
        urls = [f"https://site{n}.example-site.org/" for n in range(6)]
        code, _, err = self.sitecheck("run", "--servo", str(self.binary), *urls)
        self.assertEqual(code, cli.EXIT_FAILURE)
        run_id = self.open_store().latest_run_id()
        assert run_id is not None
        report_md = config.run_dir(self.data, run_id) / "report.md"
        self.assertTrue(report_md.is_file(), err)
        self.assertIn("not finished", report_md.read_text())

    def test_a_run_needs_free_disk_space(self) -> None:
        full = mock.Mock(free=10 << 20)
        with mock.patch("shutil.disk_usage", return_value=full):
            code, _, err = self.sitecheck("run", "--servo", str(self.binary), NEWS)
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("free", err)
        self.assertEqual(self.servo.calls, [])

    def test_an_operating_system_error_is_one_line(self) -> None:
        with mock.patch.object(runner, "run", side_effect=OSError(28, "No space left on device")):
            code, _, err = self.sitecheck("run", "--servo", str(self.binary), NEWS)
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("sitecheck: ", err)
        self.assertNotIn("Traceback", err)

    def test_old_run_directories_are_pruned(self) -> None:
        config.ensure_private_dir(self.data)
        old_clock = fakes.StepClock(datetime(2026, 8, 1, tzinfo=UTC))
        with store.open_store(config.state_db_path(self.data), clock=old_clock) as db:
            old = db.create_run(label="nightly")
            kept = db.create_run(label="nightly")  # The latest nightly stays.
        for run_id in (old, kept):
            config.run_dir(self.data, run_id).mkdir(parents=True)

        code, _, err = self.sitecheck("run", "--servo", str(self.binary), NEWS)

        self.assertEqual(code, 0, err)
        self.assertFalse(config.run_dir(self.data, old).exists())
        self.assertTrue(config.run_dir(self.data, kept).exists())

    def test_symptom_issues_are_scoped_to_their_site(self) -> None:
        self.servo.default = fakes.HANG_LOAD
        self.sitecheck("run", "--servo", str(self.binary), NEWS, NEWS_STORY, SHOP)
        signatures = sorted(issue["signature"] for issue in self.open_store().issues())
        self.assertEqual(len(signatures), 2)  # One per site.
        self.assertTrue(all(sig.startswith("hang@") for sig in signatures), signatures)
        for host in ("example-news", "example-store"):
            self.assertFalse(any(host in sig for sig in signatures))

    def test_a_page_that_leaves_the_policy_is_discarded_and_not_planned_again(self) -> None:
        self.seed(NEWS, SHOP)
        logout = "https://www.example-news.org/account/logout"
        self.servo.script[NEWS] = [fakes.Load(metrics={**fakes.good_metrics(), "url": logout})]

        code, out, err = self.sitecheck("run", "--servo", str(self.binary), "--budget", "5")

        self.assertEqual(code, 0, err)
        self.assertIn("excluded 1", out)
        code, out, _ = self.sitecheck("select", "--json", "--seed", "s")
        self.assertEqual([item["url"] for item in json.loads(out)], [SHOP])

    def test_the_run_lock_is_exclusive(self) -> None:
        config.ensure_private_dir(self.data)
        for argv in (
            ["run", "--servo", str(self.binary), NEWS],
            ["mark", "hang", "--status", "fixed"],
        ):
            with self.subTest(argv=argv), config.run_lock(self.data):
                code, _, err = self.sitecheck(*argv)
                self.assertEqual(code, cli.EXIT_USAGE)
                self.assertIn("another site check run", err)

    def test_a_deny_entry_added_later_removes_stored_pages(self) -> None:
        self.seed(NEWS, NEWS_STORY, SHOP)
        (self.config / config.DENY_LIST_NAME).write_text("example-news.org\n")

        code, out, err = self.sitecheck("select", "--json", "--seed", "s")
        self.assertEqual(code, 0, err)
        self.assertEqual([item["url"] for item in json.loads(out)], [SHOP])

        code, out, err = self.sitecheck("run", "--servo", str(self.binary), "--budget", "5")
        self.assertEqual(code, 0, err)
        self.assertEqual([url for url, _ in self.servo.calls], [SHOP])
        eligible = {row["url"] for row in self.open_store().selection_rows()}
        self.assertEqual(eligible, {SHOP})

        # Removing the entry brings the pages back.
        (self.config / config.DENY_LIST_NAME).write_text("")
        self.sitecheck("run", "--servo", str(self.binary), "--budget", "0")
        eligible = {row["url"] for row in self.open_store().selection_rows()}
        self.assertEqual(eligible, {NEWS, NEWS_STORY, SHOP})

    def test_pages_whose_host_resolves_to_a_private_address_are_not_loaded(self) -> None:
        self.seed(NEWS, SHOP, "https://nas.example-family.net/")
        self.addresses["nas.example-family.net"] = ["192.168.1.20"]

        code, _, err = self.sitecheck("run", "--servo", str(self.binary), "--budget", "5")

        self.assertEqual(code, 0, err)
        loaded = {url for url, _ in self.servo.calls}
        self.assertEqual(loaded, {NEWS, SHOP})
        eligible = {row["url"] for row in self.open_store().selection_rows()}
        self.assertNotIn("https://nas.example-family.net/", eligible)

    def test_a_resolver_that_answers_private_addresses_for_everything_stops_the_run(self) -> None:
        self.seed(NEWS, SHOP, NEWS_STORY)
        for host in ("www.example-news.org", "shop.example-store.net"):
            self.addresses[host] = ["198.18.0.5"]  # A fake-IP proxy.

        code, _, err = self.sitecheck("run", "--servo", str(self.binary), "--budget", "5")

        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("private addresses", err)
        self.assertEqual(len(self.open_store().selection_rows()), 3)  # Nothing excluded.

    def test_a_zero_budget_checks_nothing(self) -> None:
        self.seed(NEWS, SHOP)
        code, out, err = self.sitecheck("run", "--servo", str(self.binary), "--budget", "0")
        self.assertEqual(code, 0, err)
        self.assertIn("0 of 0 pages checked", out)
        self.assertEqual(self.servo.calls, [])


class CheckTest(CliTestCase):
    def test_check_prints_json_and_writes_no_store(self) -> None:
        code, out, err = self.sitecheck("check", "--servo", str(self.binary), NEWS, "--json")
        self.assertEqual(code, 0, err)
        records = json.loads(out)
        self.assertEqual([(r["url"], r["verdict"]) for r in records], [(NEWS, "ok")])
        self.assertEqual(records[0]["reference"]["status"], "ok")
        self.assertTrue(Path(records[0]["page_dir"]).is_relative_to(self.data / "checks"))
        self.assertFalse(config.state_db_path(self.data).exists())

    def test_check_exits_1_on_a_failure_and_repeats(self) -> None:
        self.servo.script[NEWS] = [fakes.CRASH_LOAD]
        code, out, _ = self.sitecheck("check", "--servo", str(self.binary), NEWS, "--repeat", "2")
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("crash (severity 5)", out)
        self.assertIn("servo attempt 2: crash", out)
        self.assertEqual(len(self.servo.calls), 2)

    def test_check_without_reference_judges_nothing_but_crashes_and_hangs(self) -> None:
        code, out, err = self.sitecheck(
            "check", "--servo", str(self.binary), "--no-reference", NEWS
        )
        self.assertEqual(code, cli.EXIT_NOT_JUDGED)
        self.assertIn("not-compared", out)
        self.assertIn("not judged", err)
        self.assertEqual(self.reference.calls, [])

        self.servo.script[NEWS] = [fakes.CRASH_LOAD]
        code, _, _ = self.sitecheck("check", "--servo", str(self.binary), "--no-reference", NEWS)
        self.assertEqual(code, cli.EXIT_FAILURE)

    def test_check_does_not_pass_pages_it_could_not_judge(self) -> None:
        self.reference.failing = frozenset({NEWS})
        code, out, _ = self.sitecheck("check", "--servo", str(self.binary), NEWS, "--json")
        self.assertEqual(code, cli.EXIT_NOT_JUDGED)
        self.assertEqual(json.loads(out)[0]["verdict"], "reference-failed")

        self.servo.script[SHOP] = [fakes.ERROR_LOAD]
        code, _, _ = self.sitecheck("check", "--servo", str(self.binary), SHOP)
        self.assertEqual(code, cli.EXIT_NOT_JUDGED)

        # A real failure wins over pages that could not be judged.
        self.servo.script[SHOP] = [fakes.CRASH_LOAD]
        code, _, _ = self.sitecheck("check", "--servo", str(self.binary), NEWS, SHOP)
        self.assertEqual(code, cli.EXIT_FAILURE)

    def test_check_loads_a_fresh_reference(self) -> None:
        self.sitecheck("check", "--servo", str(self.binary), NEWS)
        self.assertEqual(self.reference_policies, [reference.CachePolicy.REFRESH])

    def test_one_failing_page_does_not_end_a_check(self) -> None:
        real = runner._write_engine_files
        calls: list[str] = []

        def failing_for_shop(directory: Path, result: Any, names: Any) -> None:
            calls.append(result.url)
            if result.url == SHOP:
                raise OSError(28, "No space left on device")
            real(directory, result, names)

        with mock.patch.object(runner, "_write_engine_files", side_effect=failing_for_shop):
            code, out, _ = self.sitecheck(
                "check", "--servo", str(self.binary), NEWS, SHOP, NEWS_STORY, "--json"
            )
        records = json.loads(out)
        self.assertEqual([r["verdict"] for r in records], ["ok", "harness-error", "ok"])
        self.assertEqual(code, cli.EXIT_NOT_JUDGED)

    def test_check_record_makes_a_run(self) -> None:
        code, _, err = self.sitecheck("check", "--servo", str(self.binary), "--record", NEWS)
        self.assertEqual(code, 0, err)
        db = self.open_store()
        run_id = db.latest_run_id(label="check")
        assert run_id is not None
        self.assertEqual(list(db.outcomes_for_run(run_id)), [NEWS])


class IssuesTest(CliTestCase):
    def test_issues_json_says_which_run_and_whether_it_finished(self) -> None:
        self.servo.script[SHOP] = [fakes.JS_ERROR_LOAD]
        self.sitecheck("run", "--servo", str(self.binary), "--label", "nightly", NEWS, SHOP)
        self.sitecheck("check", "--servo", str(self.binary), "--record", NEWS)

        code, out, _ = self.sitecheck("issues", "--json")
        latest = json.loads(out)
        self.assertEqual(code, 0)
        self.assertEqual((latest["label"], latest["issues"]), ("check", []))

        code, out, _ = self.sitecheck("issues", "--json", "--label", "nightly")
        nightly = json.loads(out)
        self.assertEqual(nightly["label"], "nightly")
        self.assertTrue(nightly["finished"])
        self.assertEqual(nightly["pages"], 2)
        self.assertEqual(nightly["verdicts"], {"js-error": 1, "ok": 1})
        self.assertEqual(
            [issue["signature"] for issue in nightly["issues"]], ["js:fakeFn is not defined"]
        )

        code, out, _ = self.sitecheck("report", "--label", "nightly")
        self.assertEqual(code, 0)
        self.assertIn(nightly["run_id"], out)

    def test_an_unfinished_run_is_flagged(self) -> None:
        config.ensure_private_dir(self.data)
        with self.open_store() as db:
            db.create_run(label="nightly")
        code, out, err = self.sitecheck("issues", "--json")
        self.assertEqual(code, 0)
        self.assertFalse(json.loads(out)["finished"])
        code, out, err = self.sitecheck("issues")
        self.assertIn("did not finish", err)


class UrlsTest(CliTestCase):
    def test_lists_a_runs_pages_by_final_verdict_one_site_at_a_time(self) -> None:
        self.servo.script[SHOP] = [fakes.JS_ERROR_LOAD]
        self.sitecheck(
            "run", "--servo", str(self.binary), "--label", "nightly", NEWS, NEWS_STORY, SHOP
        )

        code, out, _ = self.sitecheck("urls", "--label", "nightly")
        self.assertEqual(code, 0)
        self.assertEqual(out.split(), [NEWS, NEWS_STORY])

        code, out, _ = self.sitecheck("urls", "--label", "nightly", "--verdict", "js-error")
        self.assertEqual(out.split(), [SHOP])

        code, out, _ = self.sitecheck(
            "urls", "--label", "nightly", "--verdict", "ok", "--verdict", "js-error", "--limit", "2"
        )
        # Every site gets a page before any site gets a second one.
        self.assertEqual(sorted(out.split()), sorted([NEWS, SHOP]))


class SecretIsolationTest(CliTestCase):
    def test_the_hister_settings_leave_the_process_environment(self) -> None:
        secrets = {"HISTER_TOKEN": TOKEN, "HISTER_URL": "https://hister.example-host.net"}
        with mock.patch.dict(os.environ, {**self.environ, **secrets}, clear=True):
            rt = self.runtime()
            rt.environ = os.environ
            code = cli.main(["select", "--json"], rt)
            self.assertEqual(code, 0)
            self.assertNotIn("HISTER_TOKEN", os.environ)
            self.assertNotIn("HISTER_URL", os.environ)
            self.assertEqual(rt.environ["HISTER_TOKEN"], TOKEN)  # Still readable here.


class SignalCleanupTest(unittest.TestCase):
    """A stopped command leaves no engine behind (the real CLI in a subprocess)."""

    def test_sigterm_during_a_page_kills_servoshell(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        checkout = root / "checkout"
        (checkout / "target" / "medium").mkdir(parents=True)
        (checkout / "Cargo.toml").write_text('[package]\nname = "servo"\nversion = "0.6.0"\n')
        fake = Path(__file__).resolve().parent / "fixtures" / "fake_servoshell.py"
        binary = checkout / "target" / "medium" / "servoshell"
        binary.write_text(f'#!/bin/sh\nexec "{sys.executable}" -I "{fake}" --mode=hang "$@"\n')
        binary.chmod(0o755)
        page = root / "page.html"
        page.write_text("<p>page</p>")
        (root / "home").mkdir()
        environ = {
            config.DATA_DIR_ENV: str(root / "data"),
            config.CONFIG_DIR_ENV: str(root / "config"),
            "HOME": str(root / "home"),
            "PATH": os.environ.get("PATH", ""),
        }
        package_root = Path(cli.__file__).resolve().parent.parent
        command = [
            sys.executable,
            "-I",
            "-c",
            f"import sys; sys.path.insert(0, {str(package_root)!r}); "
            "from sitecheck import cli; sys.exit(cli.main())",
            "check",
            "--no-reference",
            "--servo",
            str(binary),
            str(page),
        ]
        process = subprocess.Popen(command, env=environ, stderr=subprocess.PIPE)
        self.addCleanup(process.kill)
        pids = self.wait_for_fake_pids(root / "data")

        process.send_signal(signal.SIGTERM)
        _, stderr = process.communicate(timeout=30)

        self.assertEqual(process.returncode, 128 + signal.SIGTERM, stderr)
        self.assertIn(b"stopped by signal", stderr)
        for pid in pids:
            self.assert_gone(pid)

    def wait_for_fake_pids(self, data: Path) -> list[int]:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            for log in data.glob("checks/*/pages/*/servo.log"):
                facts = [
                    line.removeprefix("stdout| FAKE ").partition("=")
                    for line in log.read_text().splitlines()
                    if line.startswith("stdout| FAKE ")
                ]
                pids = [int(value) for key, _, value in facts if key in ("pid", "child_pid")]
                if len(pids) == 2:
                    return pids
            time.sleep(0.05)
        self.fail("the fake servoshell never started")

    def assert_gone(self, pid: int) -> None:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.05)
        os.kill(pid, signal.SIGKILL)
        self.fail(f"process {pid} outlived the command")


class _FakeHister:
    """Serves /api/history from a list of documents, one page of PAGE_SIZE at a time."""

    def __init__(self, documents: list[dict[str, Any]]) -> None:
        self.queries: list[dict[str, list[str]]] = []
        self.tokens: list[str | None] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                parts = urllib.parse.urlsplit(self.path)
                query = urllib.parse.parse_qs(parts.query)
                fake.queries.append(query)
                fake.tokens.append(self.headers.get("X-Access-Token"))
                start = 0
                if "last" in query:
                    start = 1 + next(
                        i for i, d in enumerate(documents) if d["url"] == query["last"][0]
                    )
                body = json.dumps(
                    {"documents": documents[start : start + hister.PAGE_SIZE]}
                ).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args: Any) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


class SyncTest(CliTestCase):
    def test_sync_needs_hister_settings(self) -> None:
        code, _, err = self.sitecheck("sync")
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("Hister is not configured", err)

    def test_sync_stores_eligible_pages_and_hashes_excluded_ones(self) -> None:
        reset = "https://www.example-bank.org/password/reset?token=0123456789abcdef0123456789"
        documents = [
            {
                "url": NEWS_STORY + "?utm_source=feed",
                "type": "web",
                "add_count": 2,
                "updated": 1_790_000_000,
            },
            {"url": NEWS_STORY, "type": "web", "add_count": 3, "updated": 1_790_000_100},
            {"url": reset, "type": "web", "add_count": 1},
            {"url": "https://intranet/wiki", "type": 0},
            {"url": "ftp://files.example-news.org/x", "type": "web"},
        ]
        fake = _FakeHister(documents)
        self.addCleanup(fake.close)
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL={fake.url}\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o600)

        code, out, err = self.sitecheck("sync")
        self.assertEqual(code, 0, err)
        self.assertIn("full sync: 4 documents in 1 pages (1 skipped); 2 eligible, 2 excluded", out)
        self.assertIn("excluded by reason: private-host 1, sensitive 1", out)
        self.assertNotIn(TOKEN, out + err)
        self.assertEqual(fake.tokens, [TOKEN])

        db = self.open_store()
        rows = db.selection_rows()
        self.assertEqual([(row["url"], row["add_count"]) for row in rows], [(NEWS_STORY, 5)])
        self.assertEqual(rows[0]["hister_updated"], 1_790_000_100)
        known = db.known_urls()
        self.assertFalse(any("token=" in url or "intranet/wiki" in url for url in known))
        self.assertIn("www.example-bank.org", db.hosts_and_sites().hosts)
        self.assertIsNotNone(db.get_meta(hister.LAST_SYNC_META_KEY))

        code, out, _ = self.sitecheck("sync")
        self.assertEqual(code, 0)
        self.assertIn("incremental sync", out)
        self.assertIn("date_from", fake.queries[-1])

    def test_visit_counts_survive_a_sync_that_rereads_some_documents(self) -> None:
        documents = [
            {"url": NEWS_STORY + "?utm_source=feed", "type": "web", "add_count": 40},
            {"url": NEWS_STORY, "type": "web", "add_count": 2, "updated": 1_790_000_100},
        ]
        fake = _FakeHister(documents)
        self.addCleanup(fake.close)
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL={fake.url}\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o600)

        self.assertEqual(self.sitecheck("sync")[0], 0)
        (row,) = self.open_store().selection_rows()
        self.assertEqual(row["add_count"], 42)

        # The incremental sync sees only the recently updated document.
        documents[:] = [
            {"url": NEWS_STORY, "type": "web", "add_count": 3, "updated": 1_790_000_200}
        ]
        self.assertEqual(self.sitecheck("sync")[0], 0)
        (row,) = self.open_store().selection_rows()
        self.assertEqual(row["add_count"], 43)

    def test_a_deny_entry_applies_to_history_synced_before(self) -> None:
        documents = [{"url": NEWS_STORY, "type": "web", "add_count": 1}]
        fake = _FakeHister(documents)
        self.addCleanup(fake.close)
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL={fake.url}\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o600)
        self.assertEqual(self.sitecheck("sync")[0], 0)

        (self.config / config.DENY_LIST_NAME).write_text("example-news.org\n")
        self.assertEqual(self.sitecheck("sync", "--full")[0], 0)

        self.assertEqual(self.open_store().selection_rows(), [])


class LeakcheckCliTest(CliTestCase):
    def test_needs_an_inventory(self) -> None:
        code, _, err = self.sitecheck("leakcheck", "--text", str(self.binary))
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("no page inventory", err)

    def test_an_empty_inventory_is_not_a_pass(self) -> None:
        pr = self.root / "pr.md"
        pr.write_text("Fixes layout on some-private-site.ch\n")
        config.ensure_private_dir(self.data)
        self.open_store()  # What a failed first sync leaves behind.

        code, _, err = self.sitecheck("leakcheck", "--text", str(pr))
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("no terms", err)
        code, _, err = self.sitecheck("leakcheck", "--text", str(pr), "--allow-empty-inventory")
        self.assertEqual(code, 0, err)

    def test_hister_configured_but_never_synced_is_not_a_pass(self) -> None:
        env_file = self.config / config.HISTER_ENV_NAME
        env_file.write_text(f"HISTER_URL=https://hister.example-host.net\nHISTER_TOKEN={TOKEN}\n")
        env_file.chmod(0o600)
        self.seed(SHOP)
        pr = self.root / "pr.md"
        pr.write_text("A reduced test case.\n")

        code, _, err = self.sitecheck("leakcheck", "--text", str(pr))
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertIn("sitecheck sync", err)

    def test_hosts_seen_while_loading_count_as_history(self) -> None:
        self.seed(SHOP)
        with self.open_store() as db:
            db.add_derived_hosts(["static.acme-cdn.example-cdn.net"])
        pr = self.root / "pr.md"
        pr.write_text("Reduced from a script on static.acme-cdn.example-cdn.net\n")
        code, out, _ = self.sitecheck("leakcheck", "--text", str(pr))
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn("static.acme-cdn.example-cdn.net", out)

    def test_exit_codes_for_text(self) -> None:
        self.seed(SHOP, "https://www.bbc.com/news")  # The second is also a bundled seed.
        with self.open_store() as db:
            db.upsert_pages(
                [
                    store.PageRow(
                        "excluded:00",
                        "www.private-blog.net",
                        "private-blog.net",
                        None,
                        store.PageSource.HISTER,
                        False,
                        "sensitive",
                    )
                ]
            )
        leaky = self.root / "pr.md"
        leaky.write_text("Fixes the menu on www.private-blog.net\n")
        clean = self.root / "clean.md"
        clean.write_text("Fixes a reduced test case; see shop.example-store.net from the seeds.\n")

        code, out, _ = self.sitecheck("leakcheck", "--text", str(leaky))
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn(f"{leaky}:1: matched host www.private-blog.net", out)
        # The seed site is a test seed here, not in the bundled list, so it counts too.
        self.assertEqual(self.sitecheck("leakcheck", "--text", str(clean))[0], cli.EXIT_FAILURE)
        clean.write_text("Fixes a reduced test case on example.com and www.bbc.com/news.\n")
        self.assertEqual(self.sitecheck("leakcheck", "--text", str(clean))[0], 0)

    def test_git_range(self) -> None:
        self.seed(SHOP)
        repo = self.root / "repo"
        repo.mkdir()
        with mock.patch.dict(os.environ, _GIT_ENV):

            def git(*arguments: str) -> None:
                subprocess.run(
                    ["git", "-C", str(repo), "-c", "commit.gpgsign=false", *arguments],
                    check=True,
                    capture_output=True,
                )

            git("init", "--quiet", "--initial-branch=main")
            (repo / "a.txt").write_text("base\n")
            git("add", "a.txt")
            git("commit", "--quiet", "--no-verify", "-m", "base")
            (repo / "a.txt").write_text("base\nshop.example-store.net\n")
            git("commit", "--quiet", "--no-verify", "-am", "change")
            code, out, _ = self.sitecheck(
                "leakcheck", "--repo", str(repo), "--git-range", "HEAD~1..HEAD"
            )
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn(": matched host shop.example-store.net", out)
        self.assertTrue(out.startswith("a.txt:2 (commit "), out)
        with mock.patch.dict(os.environ, _GIT_ENV):
            code, out, _ = self.sitecheck(
                "leakcheck",
                "--repo",
                str(repo),
                "--git-range",
                "HEAD~1..HEAD",
                "--exclude-ref",
                "HEAD",
            )
        self.assertEqual((code, out), (0, ""))

    def test_a_scan_git_could_not_finish_is_an_error_not_a_pass(self) -> None:
        self.seed(SHOP)
        repo = self.root / "repo"
        repo.mkdir()
        with mock.patch.dict(os.environ, _GIT_ENV):
            for arguments in (
                ["init", "--quiet", "--initial-branch=main"],
                ["commit", "--quiet", "--no-verify", "--allow-empty", "-m", "base"],
            ):
                subprocess.run(
                    ["git", "-C", str(repo), "-c", "commit.gpgsign=false", *arguments], check=True
                )
            (repo / "shop.example-store.net.html").write_text("<p>synthetic</p>\n")
            for arguments in (["add", "--all"], ["commit", "--quiet", "--no-verify", "-m", "x"]):
                subprocess.run(
                    ["git", "-C", str(repo), "-c", "commit.gpgsign=false", *arguments], check=True
                )
            with fakes.git_timing_out("cat-file"):
                code, out, err = self.sitecheck(
                    "leakcheck", "--repo", str(repo), "--git-range", "HEAD~1..HEAD"
                )
        self.assertEqual((code, out), (cli.EXIT_USAGE, ""), err)
        self.assertIn("git cat-file", err)

    def test_text_files_are_scanned_whatever_the_base_contains(self) -> None:
        self.seed(SHOP)
        repo = self.root / "repo"
        repo.mkdir()
        pr = self.root / "pr.md"
        pr.write_text("Fixes the menu on shop.example-store.net.\n")
        with mock.patch.dict(os.environ, _GIT_ENV):
            subprocess.run(["git", "-C", str(repo), "init", "--quiet", "-b", "main"], check=True)
            (repo / "a.txt").write_text("shop.example-store.net is already here\n")
            for arguments in (["add", "a.txt"], ["commit", "--quiet", "--no-verify", "-m", "base"]):
                subprocess.run(
                    ["git", "-C", str(repo), "-c", "commit.gpgsign=false", *arguments], check=True
                )
            (repo / "b.txt").write_text("synthetic\n")
            for arguments in (["add", "b.txt"], ["commit", "--quiet", "--no-verify", "-m", "fix"]):
                subprocess.run(
                    ["git", "-C", str(repo), "-c", "commit.gpgsign=false", *arguments], check=True
                )
            code, out, _ = self.sitecheck(
                "leakcheck", "--repo", str(repo), "--git-range", "HEAD~1..HEAD", "--text", str(pr)
            )
        self.assertEqual(code, cli.EXIT_FAILURE)
        self.assertIn(f"{pr}:1: matched host shop.example-store.net", out)


if __name__ == "__main__":
    unittest.main()
