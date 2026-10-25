"""A stand-in for servoshell in the unit tests: enough W3C WebDriver for sitecheck.servo.

tests/test_servo.py wraps it in a shell script that it passes as the binary:

    python3 -I fake_servoshell.py --mode=MODE <the servoshell arguments>

It parses the servoshell flags the runner passes, reports what it received as
``FAKE key=value`` lines on stdout (which the runner stores in servo.log with a
``stdout| `` prefix), and serves WebDriver on the ``--webdriver`` port. The log
lines it prints copy the formats recorded from the real servoshell 0.6.0.

Modes (what happens when the page is navigated to, unless noted):
    normal          prints console/uncaught/rejection lines; everything succeeds
    slow-start      listens late and answers "not ready" twice before "ready"
    never-ready     always answers "not ready"
    usage-error     exits with status 2 at startup, like a rejected flag
    startup-panic   panics in servoshell's hook format at startup, then exits 101
    nav-timeout     navigation fails with the W3C "timeout" error
    nav-error       navigation fails with a W3C "unknown error"
    neterror-page   the probe sees Servo's network error page
    script-panic    a script-thread panic in servoshell's hook format; process lives on
    std-panic-exit  a panic in Rust's default format, then exit status 101
    exit-code       exits with status 3 without answering
    killed          dies by SIGKILL without answering
    hang            starts a child in its group, ignores SIGTERM, never answers
    script-timeout  the probe fails with the W3C "script timeout" error
    no-screenshot   the screenshot command fails
    late-load       navigation answers at once, but the page replaces about:blank 1 s later
    slow-screenshot the screenshot answers after 3 s
    stuck-screenshot the screenshot never answers
    slow-session    creating the session answers after 3 s

The location script the runner polls answers [location.href, readyState]; the probe's
"url" metric is the same location.
"""

from __future__ import annotations

import base64
import json
import os
import signal
import struct
import subprocess
import sys
import time
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

SESSION_ID = "fake-session"
LOG_PREFIX = "[2026-10-24T10:27:09Z ERROR "


def say(line: str) -> None:
    print(line, flush=True)


def log_error(target: str, message: str) -> None:
    print(f"{LOG_PREFIX}{target}] {message}", file=sys.stderr, flush=True)


def tiny_png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    pixels = zlib.compress(b"\x00\xff\xff\xff\xff")
    return (
        b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")
    )


def metrics(mode: str, url: str) -> dict:
    text = "Fake page text for the unit tests."
    title_length, element_count = 9, 40
    if mode == "neterror-page":
        # Servo's neterror.html: its title, a paragraph.
        text = "Could not load the requested page: ConnectionFailure"
        title_length, element_count = len("Error loading page"), 5
    elif mode == "script-panic":
        # Servo's crash.html: no title, a paragraph, a button, the details.
        text = "Servo crashed!\n\nReload\n\nexplicit panic from script"
        title_length, element_count = 0, 7
    return {
        "readyState": "complete",
        "url": url,
        "hasTitle": title_length > 0,
        "titleLength": title_length,
        "text": text,
        "textLength": len(text),
        "elementCount": element_count,
        "linkCount": 2,
        "imageCount": 1,
        "imagesLoaded": 1,
        "imagesBroken": 0,
        "iframeCount": 0,
        "formCount": 0,
        "scriptCount": 1,
        "styleSheetCount": 1,
        "scrollWidth": 412,
        "scrollHeight": 2000,
        "innerWidth": 412,
        "innerHeight": 915,
        "bodyRect": {"x": 0, "y": 0, "width": 412, "height": 2000},
        "errors": [],
        "probeErrors": [],
    }


def on_navigate(mode: str) -> None:
    """Side effects of loading the page; returns only if the request should be answered."""
    if mode == "normal":
        say("fake-console-error")
        # Page-controlled stdout that merely looks like a panic must not count as one.
        say("thread 'main' panicked at forged/by/page.rs:1:1:")
        say("Error at https://fake.example/app.js:3:7 fake-uncaught")
        log_error("servoshell::desktop::headless_window", "fake-console-error")
        log_error(
            "servoshell::desktop::headless_window",
            "Error at https://fake.example/app.js:3:7 fake-uncaught",
        )
        log_error(
            "script::runtime::script_runtime",
            'Unhandled promise rejection: (new Error("fake-rejection", "https://fake.example/app.js", 10))',
        )
        print(
            "UNSUPPORTED (log once): POSSIBLE ISSUE: unit 1 GLD_TEXTURE_INDEX_2D is unloadable",
            file=sys.stderr,
            flush=True,
        )
    elif mode == "script-panic":
        print(
            "explicit panic from script (thread Script#1, at components/script/dom/testing/servotestutils.rs:77)",
            file=sys.stderr,
        )
        print("      std::panicking::panic_with_hook", file=sys.stderr)
        print(
            "      <std::sys::thread::unix::Thread>::new::thread_start", file=sys.stderr, flush=True
        )
        log_error("servoshell::panic_hook", "explicit panic from script")
    elif mode == "std-panic-exit":
        print("thread 'main' panicked at components/fake/lib.rs:12:5:", file=sys.stderr)
        print("called `Option::unwrap()` on a `None` value", file=sys.stderr)
        print(
            "note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace",
            file=sys.stderr,
            flush=True,
        )
        os._exit(101)
    elif mode == "exit-code":
        os._exit(3)
    elif mode == "killed":
        os.kill(os.getpid(), signal.SIGKILL)
    elif mode == "hang":
        child = subprocess.Popen(["sleep", "300"])
        say(f"FAKE child_pid={child.pid}")
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        while True:
            time.sleep(60)


class Handler(BaseHTTPRequestHandler):
    server: FakeServer

    def log_message(self, format: str, *args: object) -> None:
        pass  # Keep the access log out of servo.log.

    def reply(self, value: object, status: int = 200) -> None:
        body = json.dumps({"value": value}).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def fail(self, error: str, message: str, status: int = 500) -> None:
        self.reply({"error": error, "message": message, "stacktrace": ""}, status)

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self) -> None:
        mode = self.server.mode
        if self.path == "/status":
            self.server.status_polls += 1
            ready = mode != "never-ready" and not (
                mode == "slow-start" and self.server.status_polls <= 2
            )
            self.reply({"ready": ready, "message": "fake"})
        elif self.path == f"/session/{SESSION_ID}/screenshot":
            if mode == "no-screenshot":
                self.fail("unknown error", "fake screenshot failure")
                return
            if mode == "slow-screenshot":
                time.sleep(3)
            elif mode == "stuck-screenshot":
                time.sleep(300)
            self.reply(base64.b64encode(tiny_png()).decode("ascii"))
        else:
            self.fail("unknown command", self.path, 404)

    def do_POST(self) -> None:
        mode = self.server.mode
        body = self.read_json()
        if self.path == "/session":
            if mode == "slow-session":
                time.sleep(3)
            say("FAKE capabilities=" + json.dumps(body, sort_keys=True))
            self.reply({"sessionId": SESSION_ID, "capabilities": {"browserName": "servo"}})
        elif self.path == f"/session/{SESSION_ID}/url":
            url = body.get("url", "")
            say("FAKE navigate=" + url)
            on_navigate(mode)
            # Like Servo's cold start: the initial about:blank load reports complete
            # to the navigate command before the requested page replaces it.
            delay = 1.0 if mode == "late-load" else 0.0
            self.server.pending = (url, time.monotonic() + delay)
            if mode == "nav-timeout":
                self.fail("timeout", "fake page load timeout")
            elif mode == "nav-error":
                self.fail("unknown error", "fake navigation failure")
            else:
                self.reply(None)
        elif self.path == f"/session/{SESSION_ID}/execute/sync":
            script = body.get("script", "")
            location = self.server.location()
            if "location.href" in script and "probeErrors" not in script:
                self.reply([location, "complete"])
            elif "probeErrors" not in script:
                say("FAKE scroll")
                self.reply(None)
            elif mode == "script-timeout":
                self.fail("script timeout", "fake script timeout")
            else:
                say("FAKE probed=" + location)
                self.reply(metrics(mode, location))
        else:
            self.fail("unknown command", self.path, 404)

    def do_DELETE(self) -> None:
        if self.path == f"/session/{SESSION_ID}":
            say("FAKE deleted")
            self.reply(None)
        else:
            self.fail("unknown command", self.path, 404)


class FakeServer(HTTPServer):
    def __init__(self, port: int, mode: str) -> None:
        super().__init__(("127.0.0.1", port), Handler)
        self.mode = mode
        self.status_polls = 0
        self.pending: tuple[str, float] | None = None  # (URL, when it replaces about:blank)

    def location(self) -> str:
        if self.pending is None or time.monotonic() < self.pending[1]:
            return "about:blank"
        return self.pending[0]


def main(argv: list[str]) -> int:
    mode = "normal"
    options: dict[str, str] = {}
    for arg in argv:
        name, _, value = arg.partition("=")
        if name == "--mode":
            mode = value
        elif name.startswith("--"):
            options[name] = value

    say(f"FAKE pid={os.getpid()}")
    say("FAKE argv=" + json.dumps([arg for arg in argv if not arg.startswith("--mode=")]))
    say("FAKE env=" + json.dumps(sorted(os.environ)))
    say("FAKE rust_backtrace=" + os.environ.get("RUST_BACKTRACE", ""))
    say("FAKE tmpdir=" + os.environ.get("TMPDIR", ""))
    say("FAKE config_dir=" + options["--config-dir"])
    say("FAKE config_dir_entries=" + json.dumps(sorted(os.listdir(options["--config-dir"]))))
    say("FAKE userscripts=" + json.dumps(sorted(os.listdir(options["--userscripts"]))))

    if mode == "usage-error":
        print("error: unexpected argument", file=sys.stderr, flush=True)
        return 2
    if mode == "startup-panic":
        print(
            "Address already in use (thread WebDriver, at components/webdriver_server/lib.rs:150)",
            file=sys.stderr,
        )
        log_error("servoshell::panic_hook", "Address already in use")
        return 101
    if mode == "slow-start":
        time.sleep(0.5)

    FakeServer(int(options["--webdriver"]), mode).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
