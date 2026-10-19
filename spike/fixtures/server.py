#!/usr/bin/env python3
"""Fixture web server for Combray's M0 engine checks.

Serves synthetic pages only; no personal data, no external requests. Bind to loopback and
expose it to a device with `adb reverse tcp:PORT tcp:PORT`. The device can then use two
different sites for the same server: http://localhost:PORT and http://127.0.0.1:PORT.

    python3 -I spike/fixtures/server.py --port 8765
"""

import argparse
import hashlib
import html
import http.server
import json
import time
import urllib.parse

DEFAULT_PORT = 8765
COOKIE_NAME = "m0cookie"
COOKIE_MAX_AGE_SECONDS = 86400
MAX_DELAY_MS = 30000

# Every page shows a nonce created by script at load time, so two loads of the same URL
# (two tabs, or a reload) render distinguishable text.
NONCE_SCRIPT = """<script>
  window.m0Nonce = Math.random().toString(36).slice(2, 12);
  addEventListener("DOMContentLoaded", function () {
    var slot = document.getElementById("nonce");
    if (slot) slot.textContent = "NONCE-" + window.m0Nonce;
  });
</script>"""


def swatch_color(title):
    """A distinct, stable color per page, so a screenshot shows which document is painted."""
    digest = hashlib.sha256(title.encode()).digest()
    return "#" + "".join(f"{40 + byte % 160:02x}" for byte in digest[:3])


def page(title, body, head="", color=None):
    # The swatch sits right below the heading; the harness compares its pixels on screen with
    # the color the current document declares.
    color = color or swatch_color(title)
    return f"""<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>{NONCE_SCRIPT}{head}</head>
<body style="font-family: sans-serif; margin: 16px">
<h1>{html.escape(title)}</h1>
<div id="swatch" style="background:{color};height:120px"></div>
<p id="nonce">NONCE-pending</p>
{body}
</body></html>"""


def simple(letter, color):
    return page(
        f"M0-{letter}",
        f"<p>Fixture page {letter}. MARKER-PAGE-{letter}.</p>",
        color=color,
    )


def js_rendered():
    return page(
        "M0-JS",
        '<div id="target">static placeholder</div>',
        head="""<script>
  addEventListener("load", function () {
    setTimeout(function () {
      document.getElementById("target").textContent =
        "JS-RENDERED-PHRASE " + window.m0Nonce;
    }, 300);
  });
</script>""",
    )


def stale_title():
    return page(
        "M0-STALE-A",
        "<p>Changes its title while being replaced. MARKER-PAGE-STALE-A.</p>",
        head="""<script>
  function stale() { document.title = "M0-STALE-FROM-A"; }
  addEventListener("pagehide", stale);
  addEventListener("unload", stale);
</script>""",
    )


def input_page():
    filler = "".join(f"<p>Scroll filler line {i}.</p>" for i in range(120))
    return page(
        "M0-INPUT",
        """<button id="tap" style="width:240px;height:96px;font-size:24px"
  onclick="window.tapCount=(window.tapCount||0)+1;this.textContent='taps '+window.tapCount">tap me</button>
<p><input id="field" style="width:280px;height:56px;font-size:24px" autocomplete="off"></p>
<p><textarea id="area" style="width:280px;height:80px">area</textarea></p>"""
        + filler,
        head="<script>window.tapCount = 0;</script>",
    )


def big_dom(nodes):
    items = "".join(f"<li>Item {i} of the slow capture fixture</li>" for i in range(nodes))
    return page("M0-SLOW", f"<p>MARKER-PAGE-SLOW.</p><ul>{items}</ul>")


def multibyte():
    paragraph = "Grüße aus Zürich — 日本語のテキスト — Ελληνικά — emoji 😀🎉 — עברית. "
    return page("M0-MULTIBYTE", "<p>" + paragraph * 200 + "</p>")


def large():
    # Roughly 1.6 MB of text, well beyond the 256 KiB capture budget.
    line = "<p>" + ("Large fixture sentence for bounded capture. " * 40) + "</p>"
    return page("M0-LARGE", line * 900)


def empty():
    return """<!doctype html><html><head><meta charset="utf-8"><title>M0-EMPTY</title></head>
<body></body></html>"""


def hostile_throw():
    return page(
        "M0-HOSTILE-THROW",
        "<p>MARKER-PAGE-HOSTILE-THROW.</p>",
        head="""<script>
  window.getComputedStyle = function () { throw new Error("hostile getComputedStyle"); };
</script>""",
    )


def hostile_huge():
    return page(
        "M0-HOSTILE-HUGE",
        "<p>MARKER-PAGE-HOSTILE-HUGE.</p>",
        head="""<script>
  var huge = "H".repeat(5 * 1024 * 1024);
  Object.defineProperty(CharacterData.prototype, "data", { get: function () { return huge; } });
</script>""",
    )


def hostile_wide():
    return page(
        "M0-HOSTILE-WIDE",
        "<p>MARKER-PAGE-HOSTILE-WIDE.</p>",
        head="""<script>
  Object.defineProperty(Node.prototype, "childNodes", { get: function () { return { length: 1e9 }; } });
</script>""",
    )


def shadow_page():
    return page(
        "M0-SHADOW",
        "<m0-card><span>SLOTTED-LIGHT-TEXT</span></m0-card><p>MARKER-PAGE-SHADOW.</p>",
        head="""<script>
  customElements.define("m0-card", class extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: "open" }).innerHTML =
        "<p>SHADOW-TEXT</p><slot></slot><p hidden>HIDDEN-SHADOW-TEXT</p>";
    }
  });
</script>""",
    )


def timer_page():
    return page(
        "M0-TIMER",
        "<p>Counts timer ticks every 100 ms.</p>",
        head="""<script>
  window.ticks = 0;
  setInterval(function () { window.ticks++; }, 100);
</script>""",
    )


def storage_page(query):
    value = query.get("set", [""])[0]
    script = "localStorage.setItem('m0', %s);" % json.dumps(value) if value else ""
    return page(
        "M0-STORAGE",
        '<p id="stored">pending</p>',
        head=f"""<script>
  {script}
  addEventListener("DOMContentLoaded", function () {{
    document.getElementById("stored").textContent = "STORED-" + localStorage.getItem("m0");
  }});
</script>""",
    )


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "CombrayM0Fixtures/1"

    def log_message(self, fmt, *args):  # quieter, single-line log
        print(f"{self.address_string()} {fmt % args}", flush=True)

    def send_page(self, body, status=200, headers=()):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        routes = {
            "/a.html": lambda: simple("A", "#2e6b4f"),
            "/b.html": lambda: simple("B", "#8a5a2b"),
            "/c.html": lambda: simple("C", "#3b4f8a"),
            "/js-rendered.html": js_rendered,
            "/stale-title-a.html": stale_title,
            "/input.html": input_page,
            "/slow-capture.html": lambda: big_dom(20000),
            "/multibyte.html": multibyte,
            "/large.html": large,
            "/empty.html": empty,
            "/hostile-throw.html": hostile_throw,
            "/hostile-huge.html": hostile_huge,
            "/hostile-wide.html": hostile_wide,
            "/timer.html": timer_page,
            "/shadow.html": shadow_page,
            "/storage.html": lambda: storage_page(query),
        }
        if url.path in routes:
            self.send_page(routes[url.path]())
            return

        if url.path == "/redirect":
            target = query.get("to", ["/b.html"])[0]
            # Same-origin paths only: "//host" would be a scheme-relative external redirect, and
            # browsers drop tabs and newlines ("/\t/host"), which could also smuggle headers.
            printable = all("!" <= ch <= "~" for ch in target)
            if not printable or not target.startswith("/") or target.startswith("//") or "\\" in target:
                self.send_page(page("bad redirect", ""), status=400)
                return
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if url.path == "/delay":
            try:
                delay_ms = max(0, min(int(query.get("ms", ["1000"])[0]), MAX_DELAY_MS))
            except ValueError:
                self.send_page(page("bad delay", ""), status=400)
                return
            time.sleep(delay_ms / 1000)
            self.send_page(simple("DELAYED", "#666666"))
            return

        if url.path == "/cookie-set":
            # ASCII only: the value goes into a header, and the harness compares it verbatim.
            value = "".join(ch for ch in query.get("v", ["unset"])[0] if ch.isascii() and ch.isalnum())[:32]
            cookie = f"{COOKIE_NAME}={value}; Max-Age={COOKIE_MAX_AGE_SECONDS}; Path=/"
            self.send_page(
                page("M0-COOKIE-SET", f"<p>COOKIE-SET-{value}</p>"),
                headers=[("Set-Cookie", cookie)],
            )
            return

        if url.path == "/cookie-echo":
            received = self.headers.get("Cookie", "")
            self.send_page(
                page("M0-COOKIE-ECHO", f"<p id='cookie'>COOKIE-HEADER[{html.escape(received)}]</p>")
            )
            return

        self.send_page(page("not found", ""), status=404)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fixtures on http://127.0.0.1:{args.port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
