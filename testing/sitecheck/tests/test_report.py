"""Tests for sitecheck.report: reports and the triage queue, from runs made with fake engines."""

from __future__ import annotations

import io
import re
import tempfile
import unittest
from pathlib import Path

from sitecheck import config, report, runner, store
from sitecheck.model import PageError
from tests import fakes

NEWS = "https://news.example-site.org/a"
NEWS_2 = "https://news.example-site.org/b"
SHOP = "https://shop.example-store.net/"
BLOG = "https://blog.example-blog.com/"
# A URL and an error message a hostile site could produce.
HOSTILE_URL = 'https://evil.example-site.org/"><script>alert(1)</script>'
HOSTILE_ERROR = fakes.Load(errors=(PageError("uncaught", "<img src=x onerror=alert(2)>"),))


class ReportTestCase(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.data = config.ensure_private_dir(Path(temporary.name) / "data")
        self.store = store.open_store(config.state_db_path(self.data), clock=fakes.StepClock())
        self.addCleanup(self.store.close)
        self.store.upsert_pages(
            store.PageRow(url, host, site, None, store.PageSource.HISTER, True, "ok", 1, visits)
            for url, host, site, visits in (
                (NEWS, "news.example-site.org", "example-site.org", 50),
                (NEWS_2, "news.example-site.org", "example-site.org", 1),
                (SHOP, "shop.example-store.net", "example-store.net", 3),
                (BLOG, "blog.example-blog.com", "example-blog.com", 2),
            )
        )

    def run_with(
        self, script: dict[str, list[fakes.Load]], *urls: str, label: str = "nightly"
    ) -> str:
        servo = fakes.FakeServo(script={url: list(loads) for url, loads in script.items()})
        engines = runner.Engines(servo, fakes.FakeReference(), fakes.fake_images)
        run_id = self.store.create_run(label=label, servo_rev="0123456789abcdef", servo_binary="/x")
        runner.run(
            self.store,
            self.data,
            run_id,
            [runner.RunTarget(url, "new") for url in urls],
            engines,
            policy=runner.AttemptPolicy.retrying(0),
            progress=io.StringIO(),
        )
        return run_id


class TriageQueueTest(ReportTestCase):
    def test_issues_are_ordered_by_severity_times_sites_then_visits(self) -> None:
        run_id = self.run_with(
            {
                NEWS: [fakes.JS_ERROR_LOAD],
                SHOP: [fakes.JS_ERROR_LOAD],
                NEWS_2: [fakes.CRASH_LOAD],
                BLOG: [fakes.HANG_LOAD],
            },
            NEWS,
            NEWS_2,
            SHOP,
            BLOG,
        )
        queue = report.triage_queue(self.store, self.data, run_id)

        # crash 5x1 and hang 4x1 vs js-error 2x2: 5, 4, 4; the hang and the js error
        # tie on priority, and the js error's pages are visited more.
        self.assertEqual(
            [(issue["signature"], issue["priority"]) for issue in queue],
            [
                ("crash:components/layout/flow.rs:42", 5),
                ("js:fakeFn is not defined", 4),
                ("hang", 4),
            ],
        )
        js = queue[1]
        self.assertEqual(js["sites"], ["example-site.org", "example-store.net"])
        self.assertEqual(js["urls"], [NEWS, SHOP])  # Most visited first.
        self.assertEqual(js["example_url"], NEWS)
        self.assertEqual(js["visit_weight"], 53)
        self.assertEqual(
            js["example_page_dir"], str(config.page_dir(config.run_dir(self.data, run_id), NEWS))
        )
        self.assertEqual(js["status"], "open")
        self.assertEqual(js["first_seen_run"], run_id)

    def test_decided_issues_leave_the_queue_unless_asked_for(self) -> None:
        run_id = self.run_with({SHOP: [fakes.HANG_LOAD]}, SHOP)
        self.store.mark_issue("hang", store.IssueStatus.WONTFIX, "server is slow")

        self.assertEqual(report.triage_queue(self.store, self.data, run_id), [])
        everything = report.triage_queue(self.store, self.data, run_id, include_closed=True)
        self.assertEqual([issue["status"] for issue in everything], ["wontfix"])
        self.assertIn("server is slow", everything[0]["notes"])

    def test_ok_runs_have_no_issues(self) -> None:
        run_id = self.run_with({}, NEWS, SHOP)
        self.assertEqual(report.triage_queue(self.store, self.data, run_id), [])


class WriteReportTest(ReportTestCase):
    def test_writes_markdown_and_html_into_the_run_directory(self) -> None:
        run_id = self.run_with({NEWS: [fakes.CRASH_LOAD]}, NEWS, SHOP)
        files = report.write_report(self.store, self.data, run_id)

        run_dir = config.run_dir(self.data, run_id)
        self.assertEqual(files.markdown, run_dir / report.REPORT_MARKDOWN_NAME)
        markdown = files.markdown.read_text()
        self.assertIn(f"# Site check report: run {run_id}", markdown)
        self.assertIn("| crash | 1 |", markdown)
        self.assertIn("| ok | 1 |", markdown)
        self.assertIn("`crash:components/layout/flow.rs:42`", markdown)
        self.assertIn("0123456789ab", markdown)
        shop_key = config.page_key(SHOP)
        self.assertIn(f"pages/{shop_key}/servo.png", markdown)
        self.assertIn(f"pages/{shop_key}/reference.png", markdown)

        page = files.html.read_text()
        self.assertTrue(page.startswith("<!doctype html>"))
        self.assertIn(f'<img src="pages/{shop_key}/servo.png"', page)
        self.assertIn("Content-Security-Policy", page)

    def test_a_page_without_a_servo_screenshot_says_so(self) -> None:
        # Servo answers the probe but cannot take a screenshot of a page whose load
        # never completes; the screenshot rules then could not run.
        no_screenshot = fakes.Load(detail="screenshot failed: timeout: no screenshot")
        servo = fakes.FakeServo(script={SHOP: [no_screenshot]})
        original = servo.run_page

        def without_screenshot(url: str, out_dir: Path) -> object:
            result = original(url, out_dir)
            result.screenshot.unlink()
            result.screenshot = None
            return result

        servo.run_page = without_screenshot  # type: ignore[method-assign]
        engines = runner.Engines(servo, fakes.FakeReference(), fakes.fake_images)
        run_id = self.store.create_run(label="nightly")
        runner.run(
            self.store,
            self.data,
            run_id,
            [runner.RunTarget(SHOP, "new")],
            engines,
            progress=io.StringIO(),
        )
        files = report.write_report(self.store, self.data, run_id)

        markdown = files.markdown.read_text()
        self.assertIn("no Servo screenshot", markdown)
        self.assertIn("screenshot failed", markdown)
        self.assertIn("no Servo screenshot", files.html.read_text())

    def test_reports_are_private_and_show_why_servo_did_not_load(self) -> None:
        run_id = self.run_with({SHOP: [fakes.HANG_LOAD]}, SHOP)
        files = report.write_report(self.store, self.data, run_id)

        for path in (files.markdown, files.html):
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertIn("↳ servo: wall clock exceeded", files.markdown.read_text())
        self.assertIn("<li>servo: wall clock exceeded</li>", files.html.read_text())

    def test_rewriting_replaces_links_and_loose_modes(self) -> None:
        run_id = self.run_with({}, SHOP)
        run_dir = config.run_dir(self.data, run_id)
        outside = self.data.parent / "outside.md"
        outside.write_text("untouched")
        (run_dir / report.REPORT_MARKDOWN_NAME).symlink_to(outside)
        (run_dir / report.REPORT_HTML_NAME).write_text("old")
        (run_dir / report.REPORT_HTML_NAME).chmod(0o644)

        files = report.write_report(self.store, self.data, run_id)

        self.assertEqual(outside.read_text(), "untouched")
        self.assertFalse(files.markdown.is_symlink())
        self.assertEqual(files.html.stat().st_mode & 0o777, 0o600)

    def test_html_has_no_external_resources_or_scripts(self) -> None:
        run_id = self.run_with({HOSTILE_URL: [HOSTILE_ERROR]}, HOSTILE_URL, NEWS)
        page = report.write_report(self.store, self.data, run_id).html.read_text()

        self.assertNotIn("<script", page)
        self.assertNotIn("<img src=x", page)
        self.assertIn("&lt;script&gt;", page)
        # Every resource is a relative path into the run directory.
        for source in re.findall(r'<(?:img|link|script)[^>]*(?:src|href)="([^"]*)"', page):
            self.assertRegex(source, r"^pages/[0-9a-f]{16}/")

    def test_markdown_escapes_table_breaking_characters(self) -> None:
        url = "https://news.example-site.org/a|b"
        run_id = self.run_with({}, url)
        markdown = report.write_report(self.store, self.data, run_id).markdown.read_text()
        row = next(line for line in markdown.splitlines() if "a%7Cb" in line)
        self.assertEqual(row.count(" | "), 7)  # Eight cells, none split by the URL.

    def test_changes_against_an_earlier_run(self) -> None:
        first = self.run_with({SHOP: [fakes.HANG_LOAD], BLOG: [fakes.HANG_LOAD]}, NEWS, SHOP, BLOG)
        second = self.run_with(
            {NEWS: [fakes.CRASH_LOAD], BLOG: [fakes.JS_ERROR_LOAD]}, NEWS, SHOP, BLOG, NEWS_2
        )
        self.assertEqual(self.store.previous_run_id(second), first)
        markdown = report.write_report(
            self.store, self.data, second, compare_to=first
        ).markdown.read_text()

        self.assertIn(f"## Changes since run {first}", markdown)
        self.assertIn(f"### Regressions (1)\n\n- ok → crash: <{NEWS}>", markdown)
        self.assertIn(f"### Fixed (1)\n\n- hang → ok: <{SHOP}>", markdown)
        self.assertIn(f"### Different failure (1)\n\n- hang → js-error: <{BLOG}>", markdown)
        self.assertIn("1 pages were not in run", markdown)

    def test_an_unfinished_run_says_so(self) -> None:
        run_id = self.store.create_run(label="nightly")
        markdown = report.write_report(self.store, self.data, run_id).markdown.read_text()
        self.assertIn("not finished (interrupted or aborted)", markdown)
        self.assertIn("No issues in this run.", markdown)

    def test_unknown_run(self) -> None:
        with self.assertRaises(store.StoreError):
            report.write_report(self.store, self.data, "20261024T000000Z-ffff")


if __name__ == "__main__":
    unittest.main()
