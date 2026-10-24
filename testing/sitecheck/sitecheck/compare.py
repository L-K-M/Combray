"""Judges Servo's result for a page against the reference engine's result.

Pure and standard-library only: the inputs are two `PageResult`s (with the probe's
`text` still in their metrics) and the image evidence from
`images.compare_screenshots`, so the rules are unit-tested without a browser.

Rules, in the order they are checked:
1. Servo's harness failed: `harness-error`, excluded from triage.
2. Servo crashed (`crash`, 5): judged without the reference, since the engine
   died whatever the server sent.
3. Servo stopped responding (`hang`, 4): a script or command timed out, or the
   page was killed at its wall clock. Judged without the reference, unless the
   reference also hung or crashed on the page: then the page itself is at fault
   and the verdict is `reference-failed`.
4. No reference was loaded (`--no-reference`): `not-compared`, excluded from
   triage. No usable reference: `reference-failed`, excluded from triage.
5. Servo reported a navigation error that Chromium did not: `blank` (4) with the
   signature `navigation-error`.
6. A Servo result with neither page text nor a screenshot gives no evidence
   either way: `harness-error`, never a pass.
7. Every metric rule below runs and each one that fires adds a finding; the
   verdict is the most severe finding (ties go to the kind listed first in
   `VerdictKind`). A blank page is not also reported as missing content or
   looking different, and a slow load only when nothing else fired, so one
   cause is not counted under several issues. Page text never enters a
   finding, only counts and ratios.

Signatures group findings into issues. A panic location (`crash:<file>:<line>`)
or a script error (`js:<message>`) names a root cause, shared across sites. The
other kinds name only a symptom (`blank:text`, `hang:<cause>`, `images`, ...),
which unrelated causes on unrelated sites share; with a `scope` (an opaque tag of
the page's site, see runner.site_scope) their signatures become
`<symptom>@<scope>`, so marking one site's issue wontfix or fixed leaves other
sites' pages alone.

`img`, when given, has the shape `images.compare_screenshots` returns:
``{"servo": stats | None, "reference": stats | None, "similarity": {"pixel",
"ahash", "score"} | None}``. Missing entries skip the rules that need them.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

from sitecheck.images import SIMILARITY_SCORE
from sitecheck.model import (
    CrashInfo,
    Engine,
    Finding,
    PageResult,
    PageStatus,
    Verdict,
    VerdictKind,
)

# Severity of each verdict kind, 0 (fine or not Servo's fault) to 5 (crash).
SEVERITY: Mapping[VerdictKind, int] = {
    VerdictKind.CRASH: 5,
    VerdictKind.HANG: 4,
    VerdictKind.BLANK: 4,
    VerdictKind.CONTENT_MISSING: 3,
    VerdictKind.JS_ERROR: 2,
    VerdictKind.LAYOUT_OVERFLOW: 2,
    VerdictKind.IMAGES: 2,
    VerdictKind.SLOW_LOAD: 1,
    VerdictKind.VISUAL_DIFF: 1,
    VerdictKind.FLAKY: 1,
    VerdictKind.OK: 0,
    VerdictKind.REFERENCE_FAILED: 0,
    VerdictKind.NOT_COMPARED: 0,
    VerdictKind.EXCLUDED: 0,
    VerdictKind.HARNESS_ERROR: 0,
}

# Joins a symptom signature and its site scope ("blank:text@3f9a1c2e").
SCOPE_SEPARATOR = "@"

# The reference counts as failed when its page has fewer characters of text than
# this and its screenshot is near-uniform: an error page, a bot wall drawn on a
# canvas, or nothing at all.
_REFERENCE_MIN_TEXT = 50

# Grayscale thumbnail standard deviation (0-255) below which a screenshot is
# near-uniform, i.e. nothing visible was painted.
_BLANK_STDDEV_MAX = 4.0
# A blank Servo screenshot only counts when the reference shows real content.
_REFERENCE_CONTENT_STDDEV_MIN = 10.0

# Servo is blank when it has under 5 % of the reference's text, provided the
# reference has enough text for the ratio to mean something.
_BLANK_TEXT_RATIO = 0.05
_BLANK_TEXT_MIN_REFERENCE = 200

# Content check: the share of the reference's most frequent words (lowercased,
# at least three letters) that also occur in Servo's text.
_CONTENT_WORD = re.compile(r"[^\W\d_]{3,}")
_CONTENT_TOP_WORDS = 2000
_CONTENT_CONTAINMENT_MIN = 0.6
# With fewer distinct reference words a few missing words (a cookie banner, a
# date) would swing the ratio, so the rule does not apply.
_CONTENT_MIN_REFERENCE_WORDS = 20

# At most this many script errors per page become findings, so one noisy page
# cannot flood the issue list. The first errors are kept: later errors are often
# consequences of the first.
_JS_SIGNATURE_CAP = 5
# Only errors the page did not handle are compared. Console messages are what the
# page chose to log, and the engines format logged objects and caught errors
# differently ({"a": 1} against {a: 1}, a source form against a stack).
_COMPARED_ERROR_KINDS = frozenset({"uncaught", "rejection"})

# Overflow: Servo's document is more than 10 % wider than the viewport while the
# reference's fits (2 % slack for scrollbars and rounding).
_OVERFLOW_SERVO_RATIO = 1.10
_OVERFLOW_REFERENCE_RATIO = 1.02
# When the reference's own viewport is this much wider than Servo's, Chromium's
# mobile emulation widened its layout viewport to fit a page made for desktops
# (no mobile viewport meta) and zoomed out, which Servo does not do. That is one
# root cause for every such page, so it gets its own signature.
_WIDER_REFERENCE_VIEWPORT_RATIO = 1.10
_OVERFLOW_SIGNATURE = "layout-overflow"
_VIEWPORT_OVERFLOW_SIGNATURE = "layout-overflow:viewport"
_HANG_SIGNATURE = "hang"
_BLANK_SCREENSHOT_SIGNATURE = "blank:screenshot"
_BLANK_TEXT_SIGNATURE = "blank:text"
_NAVIGATION_ERROR_SIGNATURE = "navigation-error"
# A reference that also stopped responding or crashed puts the fault on the page.
_REFERENCE_STUCK = frozenset({PageStatus.HANG, PageStatus.CRASH})

# Images: Servo has at least 3 more broken images than the reference, or loads
# under half of the reference's images when the reference loads at least 4.
_IMAGES_BROKEN_EXCESS = 3
_IMAGES_LOADED_RATIO = 0.5
_IMAGES_MIN_REFERENCE_LOADED = 4

# Below this screenshot similarity (see images.SIMILARITY_SCORE) the pages look
# grossly different. Deliberately low: the measure is coarse and the finding is
# the weakest kind.
_VISUAL_SIMILARITY_MIN = 0.55

# Error message normalization, so the same failure on different pages (and in
# both engines) produces the same signature.
_SIGNATURE_MESSAGE_MAX = 160
_LONG_QUOTE_MIN = 41  # Quoted text longer than 40 characters is page data.
# A URL ends at whitespace, a quote or a bracket, so "(see https://x/a.js)"
# keeps its closing parenthesis.
_URL = re.compile(
    r"\b(?:[a-z][a-z0-9+.\-]*://|(?:data|blob|javascript|about):)[^\s'\"<>()\[\]]*",
    re.IGNORECASE,
)
_LINE_COLUMN = re.compile(r":\d+:\d+\b")
_LINE_WORD = re.compile(r"\b(line|column|col)\s*:?\s*\d+", re.IGNORECASE)
_LONG_QUOTE = re.compile(
    rf"\"[^\"]{{{_LONG_QUOTE_MIN},}}\"|'[^']{{{_LONG_QUOTE_MIN},}}'|`[^`]{{{_LONG_QUOTE_MIN},}}`"
)
# Ids, counters and timestamps differ per page and per load.
_LONG_NUMBER = re.compile(r"\b\d{4,}\b")
_HEX_ID = re.compile(r"\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{8,}\b", re.IGNORECASE)
# Engine-specific framing around the same message: Chromium's "Uncaught
# TypeError: x", SpiderMonkey's "uncaught exception: x", servoshell's log lines
# "Error at <url>:<line>:<col> x" (no error type) and "Unhandled promise
# rejection: x".
_MESSAGE_PREFIX = re.compile(
    r"^(?:uncaught\s+exception\s*:\s*|unhandled\s+promise\s+rejection\s*:\s*"
    r"|error\s+at\s+\S+:\d+:\d+\s+|uncaught\s+(?:\(in promise\)\s*)?)?"
    r"(?:[A-Za-z]*(?:Error|Exception)\s*:\s*)?",
    re.IGNORECASE,
)
_WHITESPACE = re.compile(r"\s+")
# A V8 stack after the message ("Error: x\n    at f (https://...)").
_STACK_TAIL = re.compile(r"\n\s*at\s.*", re.DOTALL)
# Page data that is no part of the failure: e-mail addresses, host names with a
# path ("api.example.ch/v2/user"), and host names under common top-level domains.
# Member expressions ("article.animate", "arr.at") are not hosts: bare names need a
# top-level domain from this list, chosen to avoid common property names.
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_HOST_WITH_PATH = re.compile(
    r"\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?/[^\s'\"<>()\[\]]*", re.IGNORECASE
)
_BARE_HOST = re.compile(
    r"\b(?:[a-z0-9-]+\.)+(?:com|net|org|edu|gov|ch|de|uk|fr|nl|es|se|dk|fi|pl|ru|jp|cn"
    r"|au|ca|br|eu|info|biz)\b(?![\w-]|\.\w)",
    re.IGNORECASE,
)
# The same failure in SpiderMonkey's and V8's words, mapped to one form.
_PROPERTY_ACCESS_WORDINGS = (
    # V8: Cannot read properties of null (reading 'foo')
    re.compile(
        r"Cannot read properties of (?P<value>null|undefined) \(reading '(?P<name>[^']*)'\)"
    ),
    # SpiderMonkey: can't access property "foo", o is null
    re.compile(r"can't access property \"(?P<name>[^\"]*)\", \S+ is (?P<value>null|undefined)"),
    # SpiderMonkey: can't access property "foo" of null
    re.compile(r"can't access property \"(?P<name>[^\"]*)\" of (?P<value>null|undefined)"),
)

# Crash locations from dependencies are absolute paths into the cargo or rustc
# source trees; keep the part that names the crate and file.
_CRASH_PATH_PREFIXES = (
    re.compile(r"^.*/\.cargo/registry/src/[^/]+/"),
    re.compile(r"^.*/\.cargo/git/checkouts/[^/]+/[^/]+/"),
    re.compile(r"^/rustc/[0-9a-f]+/"),
)
_CRASH_LOCATION = re.compile(r"^(?P<file>.+?):(?P<line>\d+)(?::\d+)?$")

# Ties between equally severe findings go to the kind listed first in VerdictKind.
KIND_ORDER = {kind: index for index, kind in enumerate(VerdictKind)}


def verdict(
    servo: PageResult,
    ref: PageResult | None,
    img: Mapping[str, Any] | None,
    *,
    scope: str | None = None,
) -> Verdict:
    """Servo's verdict for one page; `ref` is None when no reference was loaded.

    `scope` tags symptom signatures with the page's site (see the module docstring).
    """
    assert servo.engine is Engine.SERVO, "first argument must be Servo's result"
    assert ref is None or ref.engine is Engine.CHROMIUM, "reference must be Chromium's result"
    signatures = _Signatures(scope)

    if servo.status is PageStatus.ERROR:
        # The runner keeps servo.detail with the result; it may name the URL.
        return _excluded(VerdictKind.HARNESS_ERROR, "the harness failed to run servo")
    if servo.status is PageStatus.CRASH:
        return _single(
            VerdictKind.CRASH, "servo crashed", _crash_signature(servo.crash, signatures)
        )
    if servo.status is PageStatus.HANG:
        if ref is not None and ref.status in _REFERENCE_STUCK:
            return _excluded(
                VerdictKind.REFERENCE_FAILED,
                f"servo stopped responding, and the reference ended with status {ref.status.value}",
            )
        # servo.detail says which step stopped (it may name the URL, so it stays out).
        detail = "servo stopped responding: a script or the page's wall clock timed out"
        base = _HANG_SIGNATURE if not servo.hang_cause else f"{_HANG_SIGNATURE}:{servo.hang_cause}"
        return _single(VerdictKind.HANG, detail, signatures.symptom(base))

    if ref is None:
        return _excluded(
            VerdictKind.NOT_COMPARED, "no reference was loaded; only crashes and hangs are judged"
        )
    reference_problem = _reference_problem(ref, img)
    if reference_problem is not None:
        return _excluded(VerdictKind.REFERENCE_FAILED, reference_problem)
    assert ref.metrics is not None

    if servo.status is PageStatus.NAVIGATION_ERROR:
        return _single(
            VerdictKind.BLANK,
            "servo reported a navigation error; the reference loaded",
            signatures.symptom(_NAVIGATION_ERROR_SIGNATURE),
        )
    if servo.metrics is None:
        # servo.py promises metrics with an OK status; without them nothing
        # about the page is known.
        return _excluded(VerdictKind.HARNESS_ERROR, "servo result is ok but has no probe metrics")
    if not _has_content_evidence(servo.metrics, img):
        # Every rule below would be skipped, which must not read as a pass.
        return _excluded(
            VerdictKind.HARNESS_ERROR, "servo's probe and screenshot gave no content evidence"
        )

    findings = _metric_findings(servo, ref, img, signatures)
    if not findings:
        return Verdict(VerdictKind.OK, SEVERITY[VerdictKind.OK])

    findings.sort(key=lambda finding: (-finding.severity, KIND_ORDER[finding.kind]))
    worst = findings[0]
    return Verdict(worst.kind, worst.severity, tuple(findings))


def policy_excluded(detail: str) -> Verdict:
    """The verdict of a page the runner discarded because it left the URL policy."""
    return _excluded(VerdictKind.EXCLUDED, detail)


@dataclass(frozen=True)
class _Signatures:
    """Builds signatures for one page: symptom ones carry the page's scope."""

    scope: str | None

    def symptom(self, base: str) -> str:
        return base if self.scope is None else f"{base}{SCOPE_SEPARATOR}{self.scope}"


def normalize_message(message: str) -> str:
    """An error message with its page- and engine-specific parts removed.

    Strips a trailing stack, URLs (with their line and column), host names and
    e-mail addresses, other line and column numbers, quoted text longer than 40
    characters, long numbers and hex ids, and the engine's framing ("Uncaught
    TypeError: "), maps SpiderMonkey's and V8's wording of a property access on
    null or undefined to one form, then collapses whitespace and caps the length.
    Case is kept because identifiers are case-sensitive.
    """
    text = _STACK_TAIL.sub("", message)
    text = _WHITESPACE.sub(" ", text).strip()
    text = _MESSAGE_PREFIX.sub("", text, count=1)
    text = _canonical_wording(text)
    text = _URL.sub("<url>", text)
    text = _EMAIL.sub("<email>", text)
    text = _HOST_WITH_PATH.sub("<url>", text)
    text = _BARE_HOST.sub("<host>", text)
    text = _LINE_COLUMN.sub("", text)
    text = _LINE_WORD.sub(lambda match: match.group(1), text)
    text = _LONG_QUOTE.sub(lambda match: f"{match.group(0)[0]}...{match.group(0)[0]}", text)
    text = _HEX_ID.sub("<id>", text)
    text = _LONG_NUMBER.sub("<n>", text)
    text = _WHITESPACE.sub(" ", text).strip()
    if not text:
        return "<empty>"
    return text[:_SIGNATURE_MESSAGE_MAX].rstrip()


def _canonical_wording(text: str) -> str:
    for wording in _PROPERTY_ACCESS_WORDINGS:
        text = wording.sub(
            lambda match: f"can't access property '{match['name']}' of {match['value']}", text
        )
    return text


def _has_content_evidence(metrics: Mapping[str, Any], img: Mapping[str, Any] | None) -> bool:
    has_text = isinstance(metrics.get("text"), str) or _number(metrics, "textLength") is not None
    return has_text or _stddev(img, "servo") is not None


def _metric_findings(
    servo: PageResult, ref: PageResult, img: Mapping[str, Any] | None, signatures: _Signatures
) -> list[Finding]:
    assert servo.metrics is not None and ref.metrics is not None
    findings: list[Finding] = []

    blank = _blank_finding(servo.metrics, ref.metrics, img, signatures)
    if blank is not None:
        findings.append(blank)
    else:
        # A blank page also misses the content and looks different; reporting
        # those too would count the same page under three issues.
        findings.extend(_optional(_content_finding(servo.metrics, ref.metrics, signatures)))

    findings.extend(_js_findings(servo, ref))
    findings.extend(_optional(_overflow_finding(servo.metrics, ref.metrics, signatures)))
    findings.extend(_optional(_images_finding(servo.metrics, ref.metrics, signatures)))

    # A load event that never fired only matters when nothing else explains it.
    if not findings and servo.navigation_timed_out and not ref.navigation_timed_out:
        findings.append(
            _finding(
                VerdictKind.SLOW_LOAD,
                "servo's load event timed out; the reference's did not",
                signatures.symptom("slow-load"),
            )
        )

    if blank is None:
        findings.extend(_optional(_visual_finding(img, signatures)))
    return findings


def _reference_problem(ref: PageResult | None, img: Mapping[str, Any] | None) -> str | None:
    """Why the reference is no usable baseline, or None when it is."""
    if ref is None:
        return "no reference result"
    if ref.status is not PageStatus.OK:
        return f"reference status {ref.status.value}"
    if ref.metrics is None:
        return "reference has no probe metrics"

    text_length = _number(ref.metrics, "textLength")
    stddev = _stddev(img, "reference")
    # Without image statistics a low-text page cannot be told from a failed one.
    # It is kept: the text and screenshot rules cannot fire on such a baseline,
    # and the remaining rules (errors, overflow, images) are still meaningful.
    if text_length is None or stddev is None:
        return None
    if text_length < _REFERENCE_MIN_TEXT and stddev < _BLANK_STDDEV_MAX:
        return f"reference has {int(text_length)} characters of text and a blank screenshot"
    return None


def _blank_finding(
    servo: Mapping[str, Any],
    ref: Mapping[str, Any],
    img: Mapping[str, Any] | None,
    signatures: _Signatures,
) -> Finding | None:
    servo_stddev = _stddev(img, "servo")
    ref_stddev = _stddev(img, "reference")
    if (
        servo_stddev is not None
        and ref_stddev is not None
        and servo_stddev < _BLANK_STDDEV_MAX
        and ref_stddev >= _REFERENCE_CONTENT_STDDEV_MIN
    ):
        detail = (
            f"servo screenshot is near-uniform (stddev {servo_stddev:.1f}, "
            f"reference {ref_stddev:.1f})"
        )
        return _finding(VerdictKind.BLANK, detail, signatures.symptom(_BLANK_SCREENSHOT_SIGNATURE))

    servo_text = _number(servo, "textLength")
    ref_text = _number(ref, "textLength")
    if servo_text is None or ref_text is None or ref_text < _BLANK_TEXT_MIN_REFERENCE:
        return None
    if servo_text < _BLANK_TEXT_RATIO * ref_text:
        detail = f"servo has {int(servo_text)} characters of text, the reference {int(ref_text)}"
        return _finding(VerdictKind.BLANK, detail, signatures.symptom(_BLANK_TEXT_SIGNATURE))
    return None


def _content_finding(
    servo: Mapping[str, Any], ref: Mapping[str, Any], signatures: _Signatures
) -> Finding | None:
    servo_text = servo.get("text")
    ref_text = ref.get("text")
    if not isinstance(servo_text, str) or not isinstance(ref_text, str):
        return None

    frequent = [word for word, _ in Counter(_words(ref_text)).most_common(_CONTENT_TOP_WORDS)]
    if len(frequent) < _CONTENT_MIN_REFERENCE_WORDS:
        return None

    servo_words = set(_words(servo_text))
    containment = sum(1 for word in frequent if word in servo_words) / len(frequent)
    if containment >= _CONTENT_CONTAINMENT_MIN:
        return None
    detail = (
        f"{containment:.2f} of the reference's {len(frequent)} most frequent words "
        "appear in servo's text"
    )
    return _finding(VerdictKind.CONTENT_MISSING, detail, signatures.symptom("content-missing"))


@dataclass(frozen=True)
class _ScriptError:
    kind: str  # "uncaught", "rejection" or "console".
    message: str  # Normalized.
    source: str | None
    line: int | None

    @property
    def place(self) -> tuple[str, int] | None:
        if not self.source or not self.line:
            return None
        return self.source, self.line


def _js_findings(servo: PageResult, ref: PageResult) -> list[Finding]:
    """Unhandled errors only Servo reported.

    An error matches the reference by its normalized message, or, since the engines
    word many errors differently, by the script and line both report it at.
    """
    # Every reference error counts for a message match: the page may log there what
    # it throws in Servo.
    reference = list(_script_errors(ref))
    reference_messages = {error.message for error in reference}
    reference_places = {
        error.place
        for error in reference
        if error.kind in _COMPARED_ERROR_KINDS and error.place is not None
    }
    servo_only: list[str] = []
    for error in _script_errors(servo):
        if error.kind not in _COMPARED_ERROR_KINDS:
            continue
        if error.message in reference_messages or error.place in reference_places:
            continue
        if error.message not in servo_only:
            servo_only.append(error.message)

    shown = servo_only[:_JS_SIGNATURE_CAP]
    return [
        _finding(
            VerdictKind.JS_ERROR,
            f"script error {index} of {len(servo_only)} that only servo reported",
            f"js:{message}",
        )
        for index, message in enumerate(shown, start=1)
    ]


def _overflow_finding(
    servo: Mapping[str, Any], ref: Mapping[str, Any], signatures: _Signatures
) -> Finding | None:
    servo_scroll = _number(servo, "scrollWidth")
    servo_inner = _number(servo, "innerWidth")
    ref_scroll = _number(ref, "scrollWidth")
    ref_inner = _number(ref, "innerWidth")
    if None in (servo_scroll, servo_inner, ref_scroll, ref_inner):
        return None
    assert servo_scroll is not None and servo_inner is not None
    assert ref_scroll is not None and ref_inner is not None

    if servo_scroll <= servo_inner * _OVERFLOW_SERVO_RATIO:
        return None
    if ref_scroll > ref_inner * _OVERFLOW_REFERENCE_RATIO:
        return None
    detail = (
        f"servo document is {int(servo_scroll)} px wide in a {int(servo_inner)} px "
        f"viewport; reference {int(ref_scroll)} px in a {int(ref_inner)} px viewport"
    )
    # A desktop-only page is one known root cause on every site; anything else is
    # only a symptom.
    signature = signatures.symptom(_OVERFLOW_SIGNATURE)
    if ref_inner > servo_inner * _WIDER_REFERENCE_VIEWPORT_RATIO:
        signature = _VIEWPORT_OVERFLOW_SIGNATURE
    return _finding(VerdictKind.LAYOUT_OVERFLOW, detail, signature)


def _images_finding(
    servo: Mapping[str, Any], ref: Mapping[str, Any], signatures: _Signatures
) -> Finding | None:
    servo_broken = _number(servo, "imagesBroken")
    ref_broken = _number(ref, "imagesBroken")
    if (
        servo_broken is not None
        and ref_broken is not None
        and servo_broken - ref_broken >= _IMAGES_BROKEN_EXCESS
    ):
        detail = f"servo has {int(servo_broken)} broken images, the reference {int(ref_broken)}"
        return _finding(VerdictKind.IMAGES, detail, signatures.symptom("images"))

    servo_loaded = _number(servo, "imagesLoaded")
    ref_loaded = _number(ref, "imagesLoaded")
    if servo_loaded is None or ref_loaded is None or ref_loaded < _IMAGES_MIN_REFERENCE_LOADED:
        return None
    if servo_loaded < _IMAGES_LOADED_RATIO * ref_loaded:
        detail = f"servo loaded {int(servo_loaded)} images, the reference {int(ref_loaded)}"
        return _finding(VerdictKind.IMAGES, detail, signatures.symptom("images"))
    return None


def _visual_finding(img: Mapping[str, Any] | None, signatures: _Signatures) -> Finding | None:
    if img is None:
        return None
    pair = img.get("similarity")
    if not isinstance(pair, Mapping):
        return None
    score = _number(pair, SIMILARITY_SCORE)
    if score is None or score >= _VISUAL_SIMILARITY_MIN:
        return None
    return _finding(
        VerdictKind.VISUAL_DIFF,
        f"screenshot similarity {score:.2f}",
        signatures.symptom("visual-diff"),
    )


def _script_errors(result: PageResult) -> Iterable[_ScriptError]:
    """The errors the harness and the in-page capture saw, messages normalized.

    Both sources are read for both engines: they overlap, and normalizing makes
    the overlap collapse, while each catches errors the other misses (Servo runs
    the capture script late; its log misses nothing printed before it).
    """
    for error in result.errors:
        yield _ScriptError(error.kind, normalize_message(error.message), error.source, error.line)

    captured = (result.metrics or {}).get("errors")
    if not isinstance(captured, list):
        return
    for entry in captured:
        if not isinstance(entry, Mapping) or not isinstance(entry.get("message"), str):
            continue
        kind = entry.get("type") if isinstance(entry.get("type"), str) else "uncaught"
        source = entry.get("source") if isinstance(entry.get("source"), str) else None
        line = entry.get("line")
        line = line if isinstance(line, int) and not isinstance(line, bool) else None
        yield _ScriptError(kind, normalize_message(entry["message"]), source, line)


def _crash_signature(crash: CrashInfo | None, signatures: _Signatures) -> str:
    """A panic location names a root cause; anything less is a symptom of this site."""
    if crash is None:
        return signatures.symptom("crash:unknown")

    if crash.location:
        location = crash.location.strip()
        for prefix in _CRASH_PATH_PREFIXES:
            location = prefix.sub("", location, count=1)
        match = _CRASH_LOCATION.match(location)
        if match:
            return f"crash:{match.group('file')}:{match.group('line')}"
        return f"crash:{location}"

    if crash.message.strip():
        return signatures.symptom(f"crash:{normalize_message(crash.message)}")
    if crash.signal is not None:
        return signatures.symptom(f"crash:signal-{crash.signal}")
    if crash.exit_code is not None:
        return signatures.symptom(f"crash:exit-{crash.exit_code}")
    return signatures.symptom("crash:unknown")


def _words(text: str) -> list[str]:
    return _CONTENT_WORD.findall(text.lower())


def _number(metrics: Mapping[str, Any], key: str) -> float | None:
    """A numeric metric, or None when the probe could not measure it."""
    value = metrics.get(key)
    # bool is an int subclass; a probe returning True for a count is a bug, not 1.
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return float(value)


def _stddev(img: Mapping[str, Any] | None, engine: str) -> float | None:
    if img is None:
        return None
    image_stats = img.get(engine)
    if not isinstance(image_stats, Mapping):
        return None
    return _number(image_stats, "stddev")


def _finding(kind: VerdictKind, detail: str, signature: str | None) -> Finding:
    return Finding(kind, SEVERITY[kind], detail, signature)


def _optional(finding: Finding | None) -> list[Finding]:
    return [] if finding is None else [finding]


def _single(kind: VerdictKind, detail: str, signature: str) -> Verdict:
    finding = _finding(kind, detail, signature)
    return Verdict(kind, finding.severity, (finding,))


def _excluded(kind: VerdictKind, detail: str) -> Verdict:
    """A verdict that says nothing about Servo, so it carries no signature."""
    return Verdict(kind, SEVERITY[kind], (_finding(kind, detail, None),))
