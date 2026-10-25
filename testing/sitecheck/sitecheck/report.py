"""A run's report (Markdown and HTML) and its triage queue.

Reports are written into the run's directory in the private data directory and name
pages by their full URL, so they never leave this machine. The HTML report is
self-contained: inline CSS, screenshots by relative path, no scripts and no external
resources (its Content-Security-Policy forbids them too), and every value from the
store is escaped, since URLs and error messages come from arbitrary sites.

Issues group findings by signature, the same root cause across pages. Their priority
is severity times the number of affected sites, ties broken by how often the owner
visits the affected pages, so a crash on two sites outranks a layout problem on one.
`triage_queue` is that list as data, for `sitecheck issues --json` and the nightly
agent.
"""

from __future__ import annotations

import html
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlsplit

from sitecheck import compare, config, reference, servo, urlpolicy
from sitecheck.model import VerdictKind
from sitecheck.runner import LOCAL_FILE_LABEL, display_host
from sitecheck.store import IssueRecord, IssueStatus, ResultRecord, Store, StoreError

REPORT_MARKDOWN_NAME = "report.md"
REPORT_HTML_NAME = "report.html"

_THUMBNAIL_WIDTH = 160
_SHORT_REVISION = 12
_VERDICT_ORDER = {kind.value: index for index, kind in enumerate(VerdictKind)}
_NO_SERVO_SCREENSHOT = "no Servo screenshot"


def _is_failing(verdict: str) -> bool:
    try:
        return VerdictKind(verdict).is_failure
    except ValueError:
        return False


def _is_neutral(verdict: str) -> bool:
    """Neither a pass nor a Servo failure, for the change summary: flaky, or unjudged."""
    if verdict == VerdictKind.FLAKY.value:
        return True
    try:
        return not VerdictKind(verdict).triaged
    except ValueError:
        return True


@dataclass(frozen=True)
class ReportFiles:
    markdown: Path
    html: Path


@dataclass(frozen=True)
class _Page:
    url: str
    key: str  # config.page_key(url); the page's anchor and directory name.
    site: str
    weight: int  # Visits Hister counted, at least 1.
    verdict: str
    severity: int
    signatures: tuple[str, ...]
    findings: tuple[Mapping[str, Any], ...]
    bucket: str | None
    attempts: tuple[str, ...]  # Each attempt's own verdict.
    servo_detail: str | None  # Why Servo's last attempt was not a clean load.
    servo_ms: int
    reference_ms: int | None
    reference_cached: bool
    servo_screenshot: str | None  # Relative to the run directory.
    reference_screenshot: str | None

    @property
    def screenshot_note(self) -> str | None:
        """Why the screenshot rules could not judge Servo, for a page Servo loaded."""
        if self.servo_screenshot is None and self.reference_screenshot is not None:
            return _NO_SERVO_SCREENSHOT
        return None


@dataclass(frozen=True)
class _Issue:
    signature: str
    kind: str
    severity: int
    status: str
    pages: tuple[_Page, ...]  # Most visited first.
    record: IssueRecord | None

    @property
    def sites(self) -> tuple[str, ...]:
        return tuple(sorted({page.site for page in self.pages}))

    @property
    def weight(self) -> int:
        return sum(page.weight for page in self.pages)

    @property
    def priority(self) -> int:
        return self.severity * len(self.sites)


@dataclass(frozen=True)
class _Change:
    url: str
    before: str
    now: str


@dataclass(frozen=True)
class _Changes:
    compare_to: str
    regressions: tuple[_Change, ...]  # ok before, failing now.
    fixed: tuple[_Change, ...]  # failing before, ok now.
    changed: tuple[_Change, ...]  # failing both times, with a different verdict.
    only_now: int  # Pages this run checked and the other did not.


@dataclass(frozen=True)
class _Report:
    run: Mapping[str, Any]
    run_dir: Path
    pages: tuple[_Page, ...]
    issues: tuple[_Issue, ...]
    changes: _Changes | None


def write_report(
    store: Store, data: Path, run_id: str, compare_to: str | None = None
) -> ReportFiles:
    """Write report.md and report.html into the run's directory and return their paths.

    `compare_to` names an earlier run for the "changes" section.
    """
    report = _build(store, data, run_id, compare_to)
    config.ensure_private_dir(report.run_dir)
    files = ReportFiles(
        markdown=report.run_dir / REPORT_MARKDOWN_NAME, html=report.run_dir / REPORT_HTML_NAME
    )
    # Reports list history URLs: owner-only, like everything in the data directory.
    config.write_private_file(files.markdown, _markdown(report).encode("utf-8"))
    config.write_private_file(files.html, _html(report).encode("utf-8"))
    return files


def triage_queue(
    store: Store, data: Path, run_id: str, *, include_closed: bool = False
) -> list[dict[str, Any]]:
    """The run's issues as data, highest priority first.

    Only open issues unless `include_closed`: wontfix and upstream ones are decided,
    and fixed ones seen again were reopened by the run. `example_page_dir` holds the
    most visited affected page's screenshots, JSON records and servo.log (retries in
    its attempt-N directories).
    """
    report = _build(store, data, run_id, None)
    queue = []
    for issue in report.issues:
        if not include_closed and issue.status != IssueStatus.OPEN.value:
            continue
        example = issue.pages[0]
        record = issue.record
        queue.append(
            {
                "signature": issue.signature,
                "kind": issue.kind,
                "severity": issue.severity,
                "status": issue.status,
                "priority": issue.priority,
                "site_count": len(issue.sites),
                "page_count": len(issue.pages),
                "visit_weight": issue.weight,
                "sites": list(issue.sites),
                "urls": [page.url for page in issue.pages],
                "example_url": example.url,
                "example_page_dir": str(config.page_dir(report.run_dir, example.url)),
                "first_seen_run": record["first_seen_run"] if record else None,
                "notes": record["notes"] if record else None,
            }
        )
    return queue


def _build(store: Store, data: Path, run_id: str, compare_to: str | None) -> _Report:
    run = store.get_run(run_id)
    if run is None:
        raise StoreError(f"No run has id {run_id!r}")
    run_dir = config.run_dir(data, run_id)

    page_rows = {row["url"]: row for row in store.selection_rows()}
    attempts_by_url: dict[str, list[ResultRecord]] = {}
    for result in store.results_for_run(run_id):
        attempts_by_url.setdefault(result["url"], []).append(result)

    pages = tuple(
        _page(outcome, attempts_by_url.get(url, [outcome]), page_rows.get(url), run_dir)
        for url, outcome in store.outcomes_for_run(run_id).items()
    )
    pages = tuple(sorted(pages, key=lambda page: (_VERDICT_ORDER.get(page.verdict, 99), page.url)))
    issue_records = {record["signature"]: record for record in store.issues()}
    changes = None if compare_to is None else _changes(store, run_id, compare_to)
    return _Report(run, run_dir, pages, _issues(pages, issue_records), changes)


def _page(
    outcome: ResultRecord,
    attempts: Sequence[ResultRecord],
    page_row: Mapping[str, Any] | None,
    run_dir: Path,
) -> _Page:
    url = outcome["url"]
    metrics = outcome["metrics"] or {}
    reference_record = (
        metrics.get("reference") if isinstance(metrics.get("reference"), Mapping) else None
    )
    servo_record = metrics.get("servo") if isinstance(metrics.get("servo"), Mapping) else {}
    add_count = page_row.get("add_count") if page_row else None
    page_dir = config.page_dir(run_dir, url)

    artifacts = run_dir / outcome["artifacts"] if outcome["artifacts"] else page_dir
    servo_screenshot = _relative_if_exists(artifacts / servo.SCREENSHOT_NAME, run_dir)
    # Servo's notes matter when it did not load cleanly, and when it took no
    # screenshot of a page it loaded (they say why).
    show_detail = servo_record.get("status") != "ok" or servo_screenshot is None
    servo_detail = servo_record.get("detail") if show_detail else None
    findings = metrics.get("findings")
    return _Page(
        url=url,
        key=config.page_key(url),
        site=page_row["site"] if page_row else _site(url),
        weight=max(add_count or 1, 1),
        verdict=outcome["verdict"],
        severity=outcome["severity"],
        signatures=tuple(outcome["signatures"]),
        findings=tuple(findings) if isinstance(findings, list) else (),
        bucket=metrics.get("bucket"),
        attempts=tuple(
            str((attempt["metrics"] or {}).get("attempt_verdict", attempt["verdict"]))
            for attempt in attempts
        ),
        servo_detail=str(servo_detail) if servo_detail else None,
        servo_ms=outcome["duration_ms"],
        reference_ms=reference_record.get("duration_ms") if reference_record else None,
        reference_cached=bool(reference_record.get("cached")) if reference_record else False,
        servo_screenshot=servo_screenshot,
        reference_screenshot=_relative_if_exists(page_dir / reference.SCREENSHOT_NAME, run_dir),
    )


def _issues(pages: Sequence[_Page], records: Mapping[str, IssueRecord]) -> tuple[_Issue, ...]:
    by_signature: dict[str, list[_Page]] = {}
    severity: dict[str, int] = {}
    kind: dict[str, str] = {}
    for page in pages:
        for signature in page.signatures:
            by_signature.setdefault(signature, []).append(page)
            finding = _finding_for(page, signature)
            finding_kind = str(finding.get("kind", page.verdict)) if finding else page.verdict
            finding_severity = _severity(finding, finding_kind)
            if finding_severity >= severity.get(signature, -1):
                severity[signature] = finding_severity
                kind[signature] = finding_kind

    issues = []
    for signature, affected in by_signature.items():
        record = records.get(signature)
        issues.append(
            _Issue(
                signature=signature,
                kind=kind[signature],
                severity=severity[signature],
                status=record["status"] if record else IssueStatus.OPEN.value,
                pages=tuple(sorted(affected, key=lambda page: (-page.weight, page.url))),
                record=record,
            )
        )
    issues.sort(key=lambda issue: (-issue.priority, -issue.weight, issue.signature))
    return tuple(issues)


def _finding_for(page: _Page, signature: str) -> Mapping[str, Any] | None:
    for finding in page.findings:
        if isinstance(finding, Mapping) and finding.get("signature") == signature:
            return finding
    return None


def _severity(finding: Mapping[str, Any] | None, kind: str) -> int:
    value = finding.get("severity") if finding else None
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    try:
        return compare.SEVERITY[VerdictKind(kind)]
    except ValueError:
        return 0


def _changes(store: Store, run_id: str, compare_to: str) -> _Changes:
    now = store.outcomes_for_run(run_id)
    before = store.outcomes_for_run(compare_to)
    regressions, fixed, changed = [], [], []
    for url in sorted(now.keys() & before.keys()):
        was, is_now = before[url]["verdict"], now[url]["verdict"]
        if was == is_now or _is_neutral(was) or _is_neutral(is_now):
            continue
        change = _Change(url, was, is_now)
        if was == VerdictKind.OK.value:
            regressions.append(change)
        elif is_now == VerdictKind.OK.value:
            fixed.append(change)
        else:
            changed.append(change)
    return _Changes(
        compare_to=compare_to,
        regressions=tuple(regressions),
        fixed=tuple(fixed),
        changed=tuple(changed),
        only_now=len(now.keys() - before.keys()),
    )


def _site(url: str) -> str:
    parts = urlsplit(url)
    if parts.scheme == "file":
        return LOCAL_FILE_LABEL
    return urlpolicy.site_of(parts.hostname or "")


def _relative_if_exists(path: Path, run_dir: Path) -> str | None:
    if not path.is_file():
        return None
    return path.relative_to(run_dir).as_posix()


def _failing(pages: Iterable[_Page]) -> list[_Page]:
    return [page for page in pages if _is_failing(page.verdict)]


def _verdict_counts(pages: Sequence[_Page]) -> list[tuple[str, int]]:
    counts: dict[str, int] = {}
    for page in pages:
        counts[page.verdict] = counts.get(page.verdict, 0) + 1
    return sorted(counts.items(), key=lambda item: _VERDICT_ORDER.get(item[0], 99))


def _run_facts(report: _Report) -> list[tuple[str, str]]:
    run = report.run
    revision = run["servo_rev"] or "unknown"
    servo = f"{revision[:_SHORT_REVISION]} ({run['servo_binary'] or 'unknown binary'})"
    sites = {page.site for page in report.pages}
    return [
        ("Run", run["id"]),
        ("Label", run["label"] or "(none)"),
        ("Started", run["started"]),
        ("Finished", run["finished"] or "not finished (interrupted or aborted)"),
        ("Servo", servo),
        ("Reference", run["reference_engine"] or "none"),
        ("Pages", f"{len(report.pages)} on {len(sites)} sites"),
    ]


def _attempts_text(page: _Page) -> str:
    return " → ".join(page.attempts)


def _timing_text(page: _Page) -> tuple[str, str]:
    servo = f"{page.servo_ms / 1000:.1f} s"
    if page.reference_ms is None:
        return servo, "none"
    reference = f"{page.reference_ms / 1000:.1f} s"
    if page.reference_cached:
        reference += " (cached)"
    return servo, reference


# --- Markdown -----------------------------------------------------------------------


def _markdown(report: _Report) -> str:
    lines = [f"# Site check report: run {report.run['id']}", ""]
    lines += ["| | |", "|---|---|"]
    lines += [f"| {name} | {_md(value)} |" for name, value in _run_facts(report)]

    failing = _failing(report.pages)
    lines += ["", "## Summary", "", "| Verdict | Pages |", "|---|---|"]
    lines += [f"| {verdict} | {count} |" for verdict, count in _verdict_counts(report.pages)]
    lines += [
        "",
        f"{len(failing)} failing pages on {len({page.site for page in failing})} sites, "
        f"{len(report.issues)} distinct signatures.",
    ]

    if report.changes is not None:
        lines += _markdown_changes(report.changes)

    lines += ["", "## Issues", ""]
    if not report.issues:
        lines += ["No issues in this run.", ""]
    for number, issue in enumerate(report.issues, start=1):
        lines += [
            f"### {number}. `{_md_code(issue.signature)}`",
            "",
            f"{issue.kind}, severity {issue.severity}, {issue.status}. Priority "
            f"{issue.priority}: {len(issue.sites)} sites, {len(issue.pages)} pages, "
            f"visit weight {issue.weight}.",
            "",
        ]
        lines += [f"- {_md_url(page.url)} ([page](#p-{page.key}))" for page in issue.pages]
        lines.append("")

    lines += [
        "## Pages",
        "",
        "| Verdict | Sev | Page | Bucket | Attempts | Servo | Reference | Screenshots |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for page in report.pages:
        servo_time, reference_time = _timing_text(page)
        shots = " ".join(
            f"[{name}]({quote(path)})"
            for name, path in (("servo", page.servo_screenshot), ("ref", page.reference_screenshot))
            if path
        )
        if page.screenshot_note:
            shots = f"{shots} ({page.screenshot_note})".strip()
        lines.append(
            f'| <a id="p-{page.key}"></a>{page.verdict} | {page.severity} | '
            f"{_md_url(page.url)} | {page.bucket or ''} | {_md(_attempts_text(page))} | "
            f"{servo_time} | {_md(reference_time)} | {shots} |"
        )
        for finding in page.findings:
            if isinstance(finding, Mapping) and finding.get("kind") != VerdictKind.OK.value:
                lines.append(
                    f"| | | ↳ {_md(str(finding.get('kind')))}: "
                    f"{_md(str(finding.get('detail')))} | | | | | |"
                )
        if page.servo_detail:
            lines.append(f"| | | ↳ servo: {_md(page.servo_detail)} | | | | | |")
    lines.append("")
    return "\n".join(lines)


def _markdown_changes(changes: _Changes) -> list[str]:
    lines = ["", f"## Changes since run {changes.compare_to}", ""]
    sections = (
        ("Regressions", changes.regressions),
        ("Fixed", changes.fixed),
        ("Different failure", changes.changed),
    )
    for title, items in sections:
        lines.append(f"### {title} ({len(items)})")
        lines.append("")
        lines += [f"- {item.before} → {item.now}: {_md_url(item.url)}" for item in items]
        if items:
            lines.append("")
    lines.append(f"{changes.only_now} pages were not in run {changes.compare_to}.")
    return lines


# Inline markup and table syntax; the text never starts a line, so block markers are safe.
_MARKDOWN_SPECIAL = str.maketrans({char: f"\\{char}" for char in "\\`*_[]<>|"})


def _md(text: str) -> str:
    return text.translate(_MARKDOWN_SPECIAL).replace("\n", " ")


def _md_code(text: str) -> str:
    # A code span cannot contain its own delimiter; the signature stays readable.
    return text.replace("`", "'").replace("|", "\\|")


def _md_url(url: str) -> str:
    """A clickable autolink, with the characters that would end it or a table cell encoded."""
    safe = quote(url, safe=":/?#[]@!$&'()*+,;=%~.-_")
    return f"<{safe}>"


# --- HTML ---------------------------------------------------------------------------

_CSS = """
:root { color-scheme: light dark; --fg: #1d1d1f; --bg: #fff; --muted: #6e6e73;
  --line: #d2d2d7; --bad: #b3261e; --warn: #8a5a00; --good: #1b6e3a; }
@media (prefers-color-scheme: dark) { :root { --fg: #f5f5f7; --bg: #1c1c1e;
  --muted: #a1a1a6; --line: #3a3a3c; --bad: #ff6b60; --warn: #e0a030; --good: #4cc27a; } }
body { font: 15px/1.45 system-ui, sans-serif; color: var(--fg); background: var(--bg);
  margin: 0 auto; max-width: 1100px; padding: 16px; }
h1 { font-size: 1.5em; } h2 { margin-top: 2em; border-bottom: 1px solid var(--line); }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; vertical-align: top; padding: 6px 8px;
  border-bottom: 1px solid var(--line); }
th { color: var(--muted); font-weight: 600; }
code { font: 13px ui-monospace, monospace; overflow-wrap: anywhere; }
a { color: inherit; overflow-wrap: anywhere; }
.muted { color: var(--muted); }
.v-crash, .v-hang, .v-blank, .v-content-missing { color: var(--bad); font-weight: 600; }
.v-js-error, .v-layout-overflow, .v-images, .v-slow-load, .v-visual-diff, .v-flaky {
  color: var(--warn); font-weight: 600; }
.v-ok { color: var(--good); }
.shots { display: flex; gap: 8px; }
.shots figure { margin: 0; }
.shots img { width: THUMBNAIL_WIDTHpx; border: 1px solid var(--line); display: block; }
.shots figcaption { font-size: 12px; color: var(--muted); }
.issue { margin: 1em 0; }
.findings { margin: 4px 0 0; padding-left: 18px; color: var(--muted); }
""".replace("THUMBNAIL_WIDTH", str(_THUMBNAIL_WIDTH))

# Nothing in the report needs scripts or remote resources; a hostile URL or error
# message that slipped past escaping still could not load or run anything.
_CSP = "default-src 'none'; img-src 'self' file:; style-src 'unsafe-inline'"


def _html(report: _Report) -> str:
    e = _escape
    run_id = report.run["id"]
    parts = [
        "<!doctype html>",
        '<html lang="en"><head><meta charset="utf-8">',
        f'<meta http-equiv="Content-Security-Policy" content="{_CSP}">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        f"<title>Site check {e(run_id)}</title>",
        f"<style>{_CSS}</style></head><body>",
        f"<h1>Site check report: run {e(run_id)}</h1>",
        "<table>",
    ]
    parts += [
        f"<tr><th>{e(name)}</th><td>{e(value)}</td></tr>" for name, value in _run_facts(report)
    ]
    parts.append("</table>")

    failing = _failing(report.pages)
    parts += ["<h2>Summary</h2>", "<table><tr><th>Verdict</th><th>Pages</th></tr>"]
    parts += [
        f'<tr><td class="v-{e(verdict)}">{e(verdict)}</td><td>{count}</td></tr>'
        for verdict, count in _verdict_counts(report.pages)
    ]
    parts.append("</table>")
    parts.append(
        f"<p>{len(failing)} failing pages on {len({page.site for page in failing})} sites, "
        f"{len(report.issues)} distinct signatures.</p>"
    )

    if report.changes is not None:
        parts += _html_changes(report.changes)

    parts.append("<h2>Issues</h2>")
    if not report.issues:
        parts.append("<p>No issues in this run.</p>")
    for number, issue in enumerate(report.issues, start=1):
        links = "".join(
            f'<li>{_html_url(page.url)} <a href="#p-{page.key}">page</a></li>'
            for page in issue.pages
        )
        parts.append(
            f'<div class="issue"><h3>{number}. <code>{e(issue.signature)}</code></h3>'
            f'<p><span class="v-{e(issue.kind)}">{e(issue.kind)}</span>, severity '
            f"{issue.severity}, {e(issue.status)}. Priority {issue.priority}: "
            f"{len(issue.sites)} sites, {len(issue.pages)} pages, visit weight "
            f"{issue.weight}.</p><ul>{links}</ul></div>"
        )

    parts += [
        "<h2>Pages</h2>",
        "<table><tr><th>Page</th><th>Screenshots (Servo, reference)</th></tr>",
    ]
    parts += [_html_page_row(page) for page in report.pages]
    parts += ["</table>", "</body></html>", ""]
    return "\n".join(parts)


def _html_changes(changes: _Changes) -> list[str]:
    e = _escape
    parts = [f"<h2>Changes since run {e(changes.compare_to)}</h2>"]
    sections = (
        ("Regressions", changes.regressions),
        ("Fixed", changes.fixed),
        ("Different failure", changes.changed),
    )
    for title, items in sections:
        parts.append(f"<h3>{title} ({len(items)})</h3>")
        if items:
            parts.append(
                "<ul>"
                + "".join(
                    f'<li><span class="v-{e(item.before)}">{e(item.before)}</span> → '
                    f'<span class="v-{e(item.now)}">{e(item.now)}</span>: '
                    f"{_html_url(item.url)}</li>"
                    for item in items
                )
                + "</ul>"
            )
    parts.append(
        f'<p class="muted">{changes.only_now} pages were not in run {e(changes.compare_to)}.</p>'
    )
    return parts


def _html_page_row(page: _Page) -> str:
    e = _escape
    servo_time, reference_time = _timing_text(page)
    findings = "".join(
        f"<li>{e(str(finding.get('kind')))}: {e(str(finding.get('detail')))}"
        + (f" <code>{e(str(finding.get('signature')))}</code>" if finding.get("signature") else "")
        + "</li>"
        for finding in page.findings
        if isinstance(finding, Mapping) and finding.get("kind") != VerdictKind.OK.value
    )
    if page.servo_detail:
        findings += f"<li>servo: {e(page.servo_detail)}</li>"
    shots = "".join(
        f'<figure><a href="{e(quote(path))}"><img src="{e(quote(path))}" alt="{name} '
        f'screenshot" loading="lazy"></a><figcaption>{name}</figcaption></figure>'
        for name, path in (
            ("Servo", page.servo_screenshot),
            ("reference", page.reference_screenshot),
        )
        if path
    )
    if page.screenshot_note:
        shots = f'<p class="muted">{e(page.screenshot_note)}</p>{shots}'
    return (
        f'<tr id="p-{page.key}"><td><span class="v-{e(page.verdict)}">{e(page.verdict)}</span> '
        f"(severity {page.severity})<br>{_html_url(page.url)}<br>"
        f'<span class="muted">{e(page.site)} · {e(page.bucket or "named")} · attempts '
        f"{e(_attempts_text(page))} · Servo {e(servo_time)} · reference "
        f'{e(reference_time)}</span><ul class="findings">{findings}</ul></td>'
        f'<td><div class="shots">{shots}</div></td></tr>'
    )


def _html_url(url: str) -> str:
    """The URL as a link when it is a web page, else as text."""
    text = _escape(url)
    if urlsplit(url).scheme not in ("http", "https"):
        return f"<code>{text}</code>"
    host = _escape(display_host(url))
    return (
        f'<a href="{text}" rel="noreferrer noopener">{host}</a> <code class="muted">{text}</code>'
    )


def _escape(value: object) -> str:
    return html.escape(str(value), quote=True)
