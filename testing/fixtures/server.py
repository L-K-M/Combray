#!/usr/bin/env python3
"""Synthetic pages and fake services for Combray's device checks (no live services, no personal data).

    python3 -I testing/fixtures/server.py [--port 8766]

The device reaches it through `adb reverse tcp:8766 tcp:8766` at http://localhost:8766.

Pages
  /solid/<rrggbb>.html       whole page in one color; title SOLID-<rrggbb> (pixel checks)
  /echo?q=...                title ECHO:<q as received> (query encoding checks)
  /cookie-set?v=<alnum>      sets cookie combray=<v> (one year)
  /cookie-echo               title COOKIE:<Cookie header>
  /opener.html               link with target=_blank to /solid/3b4f8a.html
  /rendered.html?p=<alnum>   text rendered by script after load: RENDERED-<p reversed>
  /once.html?k=<alnum>       title ONCE on the first request per key; later ones close unanswered
                             (a reload that fails like a lost connection)
  /private.html              page for exclusion checks
  /slow.html                 answers after SLOW_PAGE_SECONDS; title SLOW
  /screen.html               title SCREEN:<screen.width>:<screen.availWidth>:<outerWidth>:<innerWidth> (CSS px)
  /select.html               page-filling <select>; title SELECT:<value> after a change
  /dialogs.html              page-filling button: confirm, prompt, alert; title DIALOGS:<confirm>:<prompt>
  /upload.html               page-filling <input type=file accept=.txt>; title FILE:<name>:<text>
  /leave.html[?auto]         link to /solid/2e6b4f.html above a <textarea>; its beforeunload handler
                             always asks to stay (title LEAVE:<times asked>); ?auto follows the link
                             by script a second after load, without any user input
  /external.html?to=market|intent|badfallback|file[&auto=1][&blank=1]
                             page-filling #8a5a2b link (or, with auto, a script on load) to another
                             app's link or a file, with blank in a new window (target=_blank); the
                             title turns EXTERNAL:still-here 3 s later if the page is still shown
  /popup.html                calls window.open on load, without a tap; the title turns
                             POPUP:still-here 3 s later
  /scheme.html               page in #2e6b4f, or #3b4f8a when it matches prefers-color-scheme: dark
  /auth.html                 HTTP Basic sign-in (realm "fixture"): user "ada", password "lovelace" gives
                             title AUTH:ada; otherwise 401 with title AUTH-REQUIRED
  /media.html                <video> and <audio> from /media/slow.bin; title MEDIA:errors:<n> counts their
                             error events
  /media/slow.bin            512 KiB served over about eight seconds; /_admin/state "media" counts the
                             requests that arrived and the responses sent in full
  /zoom.html                 title ZOOM:<innerWidth>:<devicePixelRatio>, on load and on every resize
                             (page zoom checks)
  /fixed-scroll.html         transparent fixed scroll box over a red/green page
                              (?loading=1 holds an image until /_admin/finish-scroll-load)
  (the five control pages have a #1f6f78 margin on the left for paint checks)
Fake Karakeep (root http://10.0.2.2:8766/keep on the emulator: a host of its own, because a
service's own host is never captured for Hister)
  POST /keep/api/v1/bookmarks, GET /keep/api/v1/bookmarks (newest first), GET .../bookmarks/search?q=,
  GET /keep/api/v1/lists (manual list "reading" plus a smart list), PUT .../lists/<list>/bookmarks/<id>;
  key "fixture-key"
Fake Hister (root http://10.0.2.2:8766/hister on the emulator)
  POST /hister/api/add (needs Origin: hister://), GET /hister/search?q=&format=json; token "fixture-token"
Admin (for the checks)
  GET  /_admin/state                    JSON: bookmarks, lists, hister documents, request log, media
  POST /_admin/reset                    forget everything
  POST /_admin/fault?service=karakeep|hister&kind=<fault>&count=<n>
       faults: none, store_then_hang, too_many_requests, server_error, unauthorized
"""

import argparse
import base64
import html
import http.server
import json
import threading
import time
import urllib.parse

DEFAULT_PORT = 8766
KARAKEEP_KEY = "fixture-key"
SIGN_IN = ("ada", "lovelace")
HISTER_TOKEN = "fixture-token"
HANG_SECONDS = 20
# Long enough for a check to see the tab loading before the page commits.
SLOW_PAGE_SECONDS = 8
COOKIE_NAME = "combray"
COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
MAX_BODY_BYTES = 2 * 1024 * 1024
FAULTS = {"none", "store_then_hang", "too_many_requests", "server_error", "unauthorized"}
# Slow enough that a media download in progress is still running when a check looks.
MEDIA_CHUNK_BYTES = 16 * 1024
MEDIA_CHUNKS = 32
MEDIA_CHUNK_DELAY_SECONDS = 0.25

lock = threading.Lock()
def fresh_lists():
    """List id -> bookmark ids in it. Lists that are not here answer 404."""
    return {"reading": []}


def fresh_media():
    """Requests for /media/slow.bin that arrived, and responses sent to the end."""
    return {"requests": 0, "completed": 0}


state = {"bookmarks": {}, "lists": fresh_lists(), "hister": {}, "log": [], "media": fresh_media()}
faults = {"karakeep": ["none", 0], "hister": ["none", 0]}
once_served = set()  # /once.html keys already answered (a set, so not part of the JSON state)
scroll_load_finished = threading.Event()


def page(title, body, head=""):
    return (
        "<!doctype html><html><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width, initial-scale=1'>"
        f"<title>{html.escape(title)}</title>{head}</head><body>{body}</body></html>"
    )


# Each control fills the page except a left margin, so a tap in the middle reaches it and a pixel
# in the margin shows the page has painted.
CONTROL_STYLE = ("<style>html,body{margin:0;background:#1f6f78}"
                 ".c{display:block;box-sizing:border-box;margin-left:12vw;width:80vw;height:90vh;font-size:40px}</style>")
# A news-style page for reader view: markers say what must and must not reach the article.
ARTICLE_PARAGRAPH = ("Readers keep coming back to this story because it explains, at some length and with care, "
                     "how the fixture server lays out a page that looks like a real article. ")
ARTICLE_BODY = (
    "<nav><a href='/'>READER-NAV-TEXT</a> <a href='/solid/112233.html'>Section</a></nav>"
    "<article><h1>Reader fixture</h1>"
    # The link comes first, so reader view shows it without scrolling.
    f"<p>READER-BODY-ONE <a id='inner' href='/solid/2f6b3c.html'>READER-LINK</a> {ARTICLE_PARAGRAPH * 3}</p>"
    f"<p>{ARTICLE_PARAGRAPH * 3}</p>"
    "<h2>READER-HEADING</h2>"
    f"<p>READER-BODY-TWO {ARTICLE_PARAGRAPH * 3}</p>"
    "<ul><li>READER-ITEM-ONE</li><li>READER-ITEM-TWO</li></ul>"
    # Numbered from 3, then from an item's own value: reader view shows 3., 4. and 9.
    "<ol start='3'><li>READER-STEP-THREE</li><li>READER-STEP-FOUR</li><li value='9'>READER-STEP-NINE</li></ol>"
    "<ol reversed><li>READER-COUNTDOWN-THREE</li><li>READER-COUNTDOWN-TWO</li><li>READER-COUNTDOWN-ONE</li></ol></article>"
    "<aside>READER-ASIDE-TEXT related stories</aside><footer>READER-FOOTER-TEXT</footer>"
)

# Reports in its title whether the page can observe that an element is on screen. Sites built on
# Next.js (anthropic.com, openai.com) fail to start without IntersectionObserver.
INTERSECTION_PAGE = page(
    "IO:waiting",
    "<p id='target'>observed</p><script>"
    "if (!('IntersectionObserver' in window)) { document.title = 'IO:missing'; } else {"
    " new IntersectionObserver(function (entries) {"
    "  if (entries.some(function (e) { return e.isIntersecting; })) document.title = 'IO:visible';"
    " }).observe(document.getElementById('target')); }</script>",
)

# Reports in its title whether element animations work: docs.mistral.ai runs an endless marquee
# through Element.animate() and shows an error page without it.
ANIMATE_PAGE = page(
    "WA:waiting",
    "<div id='box'>animated</div><script>"
    "var failed = false;"
    "function fail(why) { failed = true; document.title = 'WA:fail:' + why; }"
    "try {"
    " var box = document.getElementById('box');"
    " if (!box.animate) { document.title = 'WA:missing'; } else {"
    "  if (typeof box.getAnimations !== 'function') fail('no getAnimations');"
    "  else if (!Array.isArray(box.getAnimations())) fail('getAnimations not a list');"
    "  if (typeof document.getAnimations !== 'function') fail('no document.getAnimations');"
    "  else if (!Array.isArray(document.getAnimations())) fail('document.getAnimations not a list');"
    "  var loop = box.animate([{transform: 'translateX(0)'}, {transform: 'translateX(-50%)'}],"
    "   {duration: 1000, iterations: Infinity});"
    "  var fade;"
    "  loop.playbackRate = 2;"
    "  if (window.DOMParser) {"
    "   var parsed = new DOMParser().parseFromString('<p>x', 'text/html');"
    "   if (parsed.getAnimations().length !== 0) fail('parsed document sees animations');"
    "  }"
    "  loop.ready.then(function () {"
    "   var at = loop.currentTime, timing = loop.effect.getComputedTiming();"
    "   if (Math.abs(timing.localTime - at) > 1) fail('localTime ' + timing.localTime + ' at ' + at);"
    "   if (timing.currentIteration !== Math.floor(timing.localTime / 1000)) fail('iteration ' + timing.currentIteration);"
    "   if (Math.abs(timing.progress - (timing.localTime % 1000) / 1000) > 1e-3) fail('progress ' + timing.progress);"
    "   loop.finished.then(function () { fail('endless finished'); },"
    "    function (e) { if (e.name !== 'AbortError') fail('cancel ' + e.name); });"
    "   loop.cancel();"
    "   if (loop.playState !== 'idle') fail('playState ' + loop.playState);"
    "   fade = box.animate([{opacity: 1}, {opacity: 0.5}], {duration: 100, fill: 'forwards'});"
    "   return fade.finished;"
    "  }).then(function (done) {"
    "   if (done !== fade) return fail('finished value');"
    "   var held = getComputedStyle(box).opacity;"
    "   fade.cancel();"
    "   var reverted = getComputedStyle(box).opacity;"
    "   if (held !== '0.5') return fail('fill ' + held);"
    "   if (reverted !== '1') return fail('revert ' + reverted);"
    "   if (!failed) document.title = 'WA:ok';"
    "  }).catch(function (e) { fail(e.message); });"
    " }"
    "} catch (e) { fail(e.message); }</script>",
)

# Reports in its title the widths a page reads for its screen and window. Servo answers 0 for the
# screen and outer window unless the engine provides them.
SCREEN_PAGE = page(
    "SCREEN",
    "<script>document.title = ['SCREEN', screen.width, screen.availWidth, outerWidth, innerWidth]"
    ".join(':');</script>",
)

# Combray cannot play media: both elements must fail, without downloading, so their handlers run.
MEDIA_PAGE = page(
    "MEDIA",
    "<video src='/media/slow.bin?element=video' autoplay muted onerror='failed()'></video>"
    "<audio src='/media/slow.bin?element=audio' autoplay onerror='failed()'></audio>",
    "<script>var errors = 0; function failed() { errors++; document.title = 'MEDIA:errors:' + errors; }</script>",
)

# Reports its layout viewport: page zoom changes both the CSS width and the device pixel ratio.
ZOOM_PAGE = page(
    "ZOOM-WAITING",
    "<p>zoom</p><script>"
    "function report() { document.title = 'ZOOM:' + innerWidth + ':' + devicePixelRatio; }"
    "addEventListener('load', report); addEventListener('resize', report);</script>",
)

# A fixed drawer's scroll node is beside the viewport in Servo's spatial tree. An empty drawer
# must pass a swipe to the page, including while an unrelated image still holds its load event.
def fixed_scroll_page(pending_image):
    return page(
        "FIXED-SCROLL",
        "<div class='layer'><div class='box'></div></div>"
        "<section class='top'></section><section class='rest'></section>"
        + pending_image,
        "<style>html,body{margin:0}.layer{position:fixed;inset:0}"
        ".box{height:100%;overflow-y:auto}.top{height:60vh;background:#b03a2e}"
        ".rest{height:300vh;background:#2e6b4f}</style>",
    )


# Find in page: the word appears three times, a screen and a half apart and in three cases, and
# also where find must not count it (hidden, a script, a form field). The title reports the
# scroll offset and the selected text: "FIND:<scrollY>:<selection>".
FIND_GAP = "<div style='height:150vh'></div>"
FIND_PAGE = page(
    "FIND",
    "<p>Find fixture: the word is further down.</p>"
    f"{FIND_GAP}<p>First combraneedle here.</p>"
    f"{FIND_GAP}<p>Second CombraNeedle here.</p>"
    f"{FIND_GAP}<p>Third COMBRANEEDLE here.</p>{FIND_GAP}"
    "<div hidden>combraneedle hidden</div><div style='display:none'>combraneedle not shown</div>"
    "<textarea>combraneedle in a field</textarea><script>var unused = 'combraneedle in a script';</script>"
    "<script>function report() { var t = 'FIND:' + Math.round(window.scrollY) + ':' + String(window.getSelection());"
    " if (document.title !== t) document.title = t; }"
    " addEventListener('scroll', report); document.addEventListener('selectionchange', report);"
    " setInterval(report, 250); report();</script>",
    "<style>body{margin:0 16px;font-size:20px}</style>",
)

# Links a page may not follow inside Combray: no app on the emulator takes the market: link, the
# intent: link names a fallback page (/solid/2e6b4f.html), badfallback one that is no address
# ("http://"), and file: is refused.
EXTERNAL_LINKS = {
    "market": "market://details?id=ch.lkmc.combray.fixture",
    "intent": "intent://fixture#Intent;scheme=combray-fixture;"
              "S.browser_fallback_url=http%3A%2F%2Flocalhost%3A8766%2Fsolid%2F2e6b4f.html;end",
    "badfallback": "intent://fixture#Intent;scheme=combray-fixture;S.browser_fallback_url=http%3A%2F%2F;end",
    "file": "file:///system/etc/hosts",
}
STILL_HERE = "setTimeout(function(){document.title='EXTERNAL:still-here';},3000);"


def external_page(to, automatic, blank):
    href = json.dumps(EXTERNAL_LINKS[to])
    target = " target='_blank'" if blank else ""
    if automatic:
        body = f"<p>external</p><script>addEventListener('load',function(){{location.href={href};{STILL_HERE}}});</script>"
    else:
        body = (f"<a id='l' href='{html.escape(EXTERNAL_LINKS[to])}'{target} onclick=\"{STILL_HERE}\" "
                "style='display:block;height:100vh;font-size:48px;color:#fff'>other app</a>")
    return page("EXTERNAL", body, "<style>html,body{margin:0;background:#8a5a2b}</style>")


# Asks for a window as soon as it loads, which no user tapped for.
POPUP_PAGE = page(
    "POPUP",
    "<p>popup</p><script>addEventListener('load',function(){window.open('/solid/3b4f8a.html');"
    "setTimeout(function(){document.title='POPUP:still-here';},3000);});</script>",
)

# The page's color says which theme it was told: light #2e6b4f, dark #3b4f8a.
SCHEME_PAGE = page(
    "SCHEME",
    "<p>scheme</p>",
    "<style>html,body{margin:0;min-height:100%;background:#2e6b4f}"
    "@media (prefers-color-scheme: dark){html,body{background:#3b4f8a}}</style>",
)

# Kimi queries this selector list during startup. DOM matching can support :has() independently
# of stylesheet invalidation, which remains disabled until Servo implements it.
HAS_SELECTOR_PAGE = page(
    "HAS:waiting",
    "<main id='root'><section><h1 id='intro'>Intro</h1></section>"
    "<div class='markdown'><h2 id='heading'><strong>Heading</strong></h2>"
    "<h3>Plain heading</h3><p id='paragraph'><strong>Paragraph</strong></p>"
    "<p><em><strong>Nested, not a direct child</strong></em></p></div></main>"
    "<div id='probe'></div><script>"
    "function expect(actual, expected) {"
    " if (actual !== expected) throw new Error(actual + ' != ' + expected);"
    "}"
    "try {"
    " var root = document.getElementById('root');"
    " var selector = ':scope > section > h1, .markdown :is(h2, h3):has(> strong), .markdown > p:has(> strong)';"
    " expect(Array.from(root.querySelectorAll(selector), function (e) { return e.id; }).join(','),"
    "  'intro,heading,paragraph');"
    " expect(root.querySelector('h2:has(> strong)').id, 'heading');"
    " var heading = document.getElementById('heading');"
    " expect(heading.matches(':is(h2,h3):has(> strong)'), true);"
    " expect(heading.firstChild.closest('h2:has(> strong)'), heading);"
    " expect(heading.matches('h2:not(:has(> em))'), true);"
    " expect(heading.matches('h2:has(+ h3)'), true);"
    " expect(heading.matches('h2:has(~ p > em)'), true);"
    " expect(heading.matches(':is(h2,h3):not([hidden])'), true);"
    " root.lang = 'en'; expect(heading.matches(':lang(en)'), true);"
    " expect(heading.matches(':empty'), false);"
    " [':has(:has(strong))', 'svg|path', ':combray-unknown'].forEach(function (invalid) {"
    "  var rejected = false;"
    "  try { root.querySelector(invalid); } catch (e) { rejected = e.name === 'SyntaxError'; }"
    "  expect(rejected, true);"
    " });"
    " var probe = document.getElementById('probe');"
    " function color() { return getComputedStyle(probe).color; }"
    " expect(CSS.supports('selector(:has(*))'), false);"
    " expect(color(), 'rgb(10, 20, 30)');"
    " var child = document.createElement('strong'); probe.appendChild(child);"
    " expect(probe.matches(':has(> strong.active)'), false);"
    " child.className = 'active'; expect(probe.matches(':has(> strong.active)'), true);"
    " expect(document.querySelector('#probe:has(> strong.active)'), probe);"
    " expect(color(), 'rgb(10, 20, 30)');"
    " child.className = ''; expect(probe.matches(':has(> strong.active)'), false);"
    " expect(document.querySelector('#probe:has(> strong.active)'), null);"
    " child.remove(); expect(color(), 'rgb(10, 20, 30)');"
    " expect(probe.matches(':has(> strong)'), false);"
    " document.title = 'HAS:ok';"
    "} catch (e) { document.title = 'HAS:fail:' + e.message; }"
    "</script>",
    "<style>#probe{color:rgb(10,20,30)}"
    "#probe:has(> strong){color:rgb(40,50,60)}"
    "#probe:has(> strong.active){color:rgb(70,80,90)}</style>",
)

CONTROL_PAGES = {
    "/select.html": (
        "SELECT",
        "<select class='c' id='s' onchange=\"document.title='SELECT:'+this.value\">"
        "<option value='a'>Alpha</option><optgroup label='Later'><option value='b'>Bravo</option>"
        "<option value='c'>Charlie</option><option value='d' disabled>Delta</option></optgroup></select>",
        "",
    ),
    "/dialogs.html": (
        "DIALOGS",
        "<button class='c' onclick=\"var r=confirm('Proceed?');var p=prompt('Name?','Zo\u00eb');"
        "alert('Done');document.title='DIALOGS:'+r+':'+p\">ask</button>",
        "",
    ),
    "/upload.html": (
        "UPLOAD",
        "<input class='c' type='file' accept='.txt' id='f'>",
        "<script>addEventListener('load',function(){var f=document.getElementById('f');"
        "f.onchange=function(){var file=f.files[0];var r=new FileReader();"
        "r.onload=function(){document.title='FILE:'+file.name+':'+r.result};r.readAsText(file)}})</script>",
    ),
    # The link fills most of the page, so a tap in the middle follows it.
    "/leave.html": (
        "LEAVE",
        "<a class='c' id='away' href='/solid/2e6b4f.html' style='height:60vh'>leave</a>"
        "<textarea style='margin:2vh 0 0 12vw;width:80vw;height:20vh;font-size:24px'>draft</textarea>",
        "<script>var asked=0;addEventListener('beforeunload',function(e){asked++;"
        "document.title='LEAVE:'+asked;e.preventDefault();e.returnValue='unsaved'});"
        "if(location.search=='?auto')addEventListener('load',function(){"
        "setTimeout(function(){document.getElementById('away').click()},1000)})</script>",
    ),
    # A search field the page focuses on load, as search pages do; Enter sends it to /echo.
    "/autofocus.html": (
        "AUTOFOCUS",
        "<form action='/echo'><input class='c' type='search' name='q' id='q'></form>",
        "<script>addEventListener('load',function(){var q=document.getElementById('q');"
        "q.onfocus=function(){document.title='AUTOFOCUS:focused'};"
        "q.onblur=function(){document.title='AUTOFOCUS:blurred'};q.focus()})</script>",
    ),
}


def take_fault(service):
    with lock:
        kind, count = faults[service]
        if count <= 0:
            return "none"
        faults[service][1] = count - 1
        return kind


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body=b"", content_type="text/html; charset=utf-8", headers=()):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, status, value, headers=()):
        self.send(status, json.dumps(value), "application/json", headers)

    def body(self):
        length = min(int(self.headers.get("Content-Length") or 0), MAX_BODY_BYTES)
        return self.rfile.read(length)

    def record(self, entry):
        with lock:
            state["log"].append(entry)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query, keep_blank_values=True)
        path = url.path

        if path.startswith("/solid/") and path.endswith(".html"):
            color = path[len("/solid/"):-len(".html")]
            if len(color) != 6 or any(c not in "0123456789abcdef" for c in color):
                return self.send(400, page("bad color", ""))
            return self.send(200, page(f"SOLID-{color}", f"<p>SOLID-{color}</p>",
                                       f"<style>html,body{{margin:0;min-height:100%;background:#{color}}}</style>"))
        if path == "/echo":
            received = query.get("q", [""])[0]
            return self.send(200, page(f"ECHO:{received}", f"<p id='q'>{html.escape(received)}</p>"))
        if path == "/cookie-set":
            value = "".join(c for c in query.get("v", ["unset"])[0] if c.isascii() and c.isalnum())[:32]
            cookie = f"{COOKIE_NAME}={value}; Max-Age={COOKIE_MAX_AGE_SECONDS}; Path=/"
            return self.send(200, page(f"COOKIE-SET:{value}", "<p>set</p>"), headers=[("Set-Cookie", cookie)])
        if path == "/cookie-echo":
            return self.send(200, page(f"COOKIE:{self.headers.get('Cookie', '')}", "<p>echo</p>"))
        if path == "/opener.html":
            # The link fills the page, so a tap anywhere in the page area follows it.
            return self.send(200, page("OPENER", "<a id='open' target='_blank' href='/solid/3b4f8a.html' "
                                                 "style='display:block;height:100vh;font-size:48px;color:#fff'>open new tab</a>",
                                       "<style>html,body{margin:0;background:#5b2a86}</style>"))
        if path == "/rendered.html":
            # The rendered phrase is the parameter reversed: it is in neither the URL nor the title.
            phrase = "".join(c for c in query.get("p", ["x"])[0] if c.isascii() and c.isalnum())[:40][::-1]
            script = f"<script>addEventListener('load',function(){{document.getElementById('t').textContent='RENDERED-{phrase}';}});</script>"
            return self.send(200, page("RENDERED", "<p id='t'>static placeholder</p>", script))
        if path in CONTROL_PAGES:
            title, control, script = CONTROL_PAGES[path]
            return self.send(200, page(title, control, CONTROL_STYLE + script))
        if path == "/article.html":
            # Reader view keeps the article and drops the navigation, aside and footer around it.
            return self.send(200, page("ARTICLE", ARTICLE_BODY))
        if path == "/scroll.html":
            # Distinct endpoints and a live title prove root-page jumps, not just button state.
            return self.send(200, page("SCROLL-TOP",
                "<section style='height:100vh;background:#2e6b4f'>TOP</section>"
                "<section style='height:500vh;background:#8a5a2b'>MIDDLE</section>"
                "<section style='height:100vh;background:#3b4f8a'>BOTTOM</section>",
                "<style>html,body{margin:0;scroll-behavior:smooth}</style>"
                "<script>const update=()=>{const s=document.scrollingElement;"
                "const edge=s.scrollTop<=1?'SCROLL-TOP':"
                "s.scrollTop+innerHeight>=s.scrollHeight-1?'SCROLL-BOTTOM':'SCROLL-MIDDLE';"
                "const title=edge+':'+s.scrollTop+','+innerHeight+','+s.scrollHeight;"
                "if(document.title!==title)document.title=title;};"
                "addEventListener('scroll',update);</script>"))
        if path == "/intersection.html":
            return self.send(200, INTERSECTION_PAGE)
        if path == "/animate.html":
            return self.send(200, ANIMATE_PAGE)
        if path == "/slow.html":
            time.sleep(SLOW_PAGE_SECONDS)
            return self.send(200, page("SLOW", "<p>SLOW</p>"))
        if path == "/screen.html":
            return self.send(200, SCREEN_PAGE)
        if path == "/media.html":
            return self.send(200, MEDIA_PAGE)
        if path == "/media/slow.bin":
            return self.send_slow_media()
        if path == "/zoom.html":
            return self.send(200, ZOOM_PAGE)
        if path == "/fixed-scroll.html":
            pending_image = "<img hidden src='/scroll-pending-image'>" if query.get("loading") == ["1"] else ""
            return self.send(http.HTTPStatus.OK, fixed_scroll_page(pending_image))
        if path == "/scroll-pending-image":
            scroll_load_finished.wait()
            return self.send(http.HTTPStatus.NOT_FOUND)
        if path == "/find.html":
            return self.send(200, FIND_PAGE)
        if path == "/external.html":
            to = query.get("to", [""])[0]
            if to not in EXTERNAL_LINKS:
                return self.send(400, page("bad link", ""))
            auto, blank = (query.get(name, [""])[0] == "1" for name in ("auto", "blank"))
            return self.send(200, external_page(to, auto, blank))
        if path == "/popup.html":
            return self.send(200, POPUP_PAGE)
        if path == "/scheme.html":
            return self.send(200, SCHEME_PAGE)
        if path == "/has-selector.html":
            return self.send(http.HTTPStatus.OK, HAS_SELECTOR_PAGE)
        if path == "/empty.html":
            # Nothing to read: reader view finds no article.
            return self.send(200, page("EMPTY", ""))
        if path == "/private.html":
            return self.send(200, page("PRIVATE", "<p>PRIVATE-PAGE-TEXT</p>"))
        if path == "/once.html":
            key = "".join(c for c in query.get("k", [""])[0] if c.isascii() and c.isalnum())[:40]
            if not key:
                return self.send(400, page("ONCE-NEEDS-K", "<p>missing or empty k</p>"))
            with lock:
                first = key not in once_served
                once_served.add(key)
            if first:
                return self.send(200, page("ONCE", "<p>ONCE</p>"))
            # No response at all: the engine sees a network failure and commits its error page.
            self.close_connection = True
            return None
        if path == "/auth.html":
            expected = "Basic " + base64.b64encode(":".join(SIGN_IN).encode()).decode()
            if self.headers.get("Authorization") == expected:
                return self.send(200, page(f"AUTH:{SIGN_IN[0]}", "<p>signed in</p>"))
            return self.send(401, page("AUTH-REQUIRED", "<p>sign-in required</p>"),
                             headers=[("WWW-Authenticate", 'Basic realm="fixture"')])

        if path in ("/keep/api/v1/bookmarks", "/keep/api/v1/bookmarks/search", "/keep/api/v1/lists"):
            if self.headers.get("Authorization") != f"Bearer {KARAKEEP_KEY}":
                return self.send_json(401, {"error": "unauthorized"})
            text = query.get("q", [None])[0] if path.endswith("/search") else None
            self.record({"service": "karakeep", "method": "GET", "path": path, "q": text})
            with lock:
                if path.endswith("/lists"):
                    manual = [{"id": key, "name": f"List {key}", "icon": "📚", "parentId": None, "type": "manual"}
                              for key in state["lists"]]
                    smart = {"id": "smart", "name": "Smart", "icon": "🔎", "parentId": None, "type": "smart"}
                    return self.send_json(200, {"lists": manual + [smart]})
                # Newest first, like Karakeep; a search matches the address or the title.
                found = [b for b in reversed(state["bookmarks"].values())
                         if text is None or text.lower() in (b["url"] + " " + (b["title"] or "")).lower()]
                items = [{"id": b["id"], "title": None, "content": {"type": "link", "url": b["url"], "title": b["title"]}}
                         for b in found]
            return self.send_json(200, {"bookmarks": items, "nextCursor": None})
        if path == "/hister/search":
            if not self.hister_authorized():
                return self.send_json(401, {"error": "unauthorized"})
            text = query.get("q", [""])[0]
            self.record({"service": "hister", "method": "GET", "q": text})
            with lock:
                docs = [d for d in state["hister"].values() if text and text.lower() in (d["text"] + " " + d["title"]).lower()]
            return self.send_json(200, {
                "query": text,
                "total": len(docs),
                "documents": [{"url": d["url"], "title": d["title"], "text": d["text"][:200]} for d in docs],
                # Like the pinned server: null when no history is promoted.
                "history": None,
                # Like the pinned server: always present, empty when there is no next page.
                "page_key": "",
            })
        if path == "/_admin/state":
            with lock:
                return self.send_json(200, state)
        return self.send(404, page("not found", ""))

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        path = url.path
        raw = self.body()

        if path == "/_admin/reset":
            scroll_load_finished.clear()
            with lock:
                state["bookmarks"].clear()
                state["lists"].clear()
                state["lists"].update(fresh_lists())
                state["hister"].clear()
                state["log"].clear()
                once_served.clear()
                state["media"] = fresh_media()
                for value in faults.values():
                    value[0], value[1] = "none", 0
            return self.send_json(200, {"ok": True})
        if path == "/_admin/finish-scroll-load":
            scroll_load_finished.set()
            return self.send_json(http.HTTPStatus.OK, {"ok": True})
        if path == "/_admin/fault":
            service = query.get("service", [""])[0]
            kind = query.get("kind", ["none"])[0]
            if service not in faults or kind not in FAULTS:
                return self.send_json(400, {"error": "bad fault"})
            with lock:
                faults[service] = [kind, int(query.get("count", ["1"])[0])]
            return self.send_json(200, {"ok": True})

        if path == "/keep/api/v1/bookmarks":
            return self.karakeep_create(raw)
        if path == "/hister/api/add":
            return self.hister_add(raw)
        return self.send(404, page("not found", ""))

    def do_PUT(self):
        path = urllib.parse.urlsplit(self.path).path
        self.body()
        parts = path.split("/")
        # /keep/api/v1/lists/{list}/bookmarks/{bookmark}
        if len(parts) != 8 or parts[:5] != ["", "keep", "api", "v1", "lists"] or parts[6] != "bookmarks":
            return self.send(404, page("not found", ""))
        if self.headers.get("Authorization") != f"Bearer {KARAKEEP_KEY}":
            return self.send_json(401, {"error": "unauthorized"})
        self.record({"service": "karakeep", "method": "PUT", "list": parts[5], "bookmark": parts[7]})
        with lock:
            members = state["lists"].get(parts[5])
            if members is None:
                return self.send_json(404, {"error": "list not found"})
            if parts[7] not in {b["id"] for b in state["bookmarks"].values()}:
                return self.send_json(404, {"error": "bookmark not found"})
            if parts[7] not in members:
                members.append(parts[7])
        return self.send(204)

    def karakeep_create(self, raw):
        fault = take_fault("karakeep")
        if fault == "unauthorized" or self.headers.get("Authorization") != f"Bearer {KARAKEEP_KEY}":
            return self.send_json(401, {"error": "unauthorized"})
        try:
            data = json.loads(raw)
        except ValueError:
            return self.send_json(400, {"error": "bad json"})
        if data.get("type") != "link" or not str(data.get("url", "")).startswith(("http://", "https://")):
            return self.send_json(400, {"error": "bad bookmark"})
        self.record({"service": "karakeep", "method": "POST", "url": data["url"], "title": data.get("title")})
        if fault == "too_many_requests":
            return self.send_json(429, {"error": "slow down"}, headers=[("Retry-After", "1")])
        if fault == "server_error":
            return self.send_json(503, {"error": "unavailable"})
        with lock:
            existing = state["bookmarks"].get(data["url"])
            if existing is None:
                existing = {"id": f"bm{len(state['bookmarks']) + 1}", "url": data["url"], "title": data.get("title")}
                state["bookmarks"][data["url"]] = existing
                status = 201
            else:
                status = 200
        if fault == "store_then_hang":
            time.sleep(HANG_SECONDS)
        return self.send_json(status, existing)

    def send_slow_media(self):
        with lock:
            state["media"]["requests"] += 1
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(MEDIA_CHUNK_BYTES * MEDIA_CHUNKS))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            for _ in range(MEDIA_CHUNKS):
                self.wfile.write(bytes(MEDIA_CHUNK_BYTES))
                self.wfile.flush()
                time.sleep(MEDIA_CHUNK_DELAY_SECONDS)
        except ConnectionError:
            # The client stopped the download.
            self.close_connection = True
            return
        with lock:
            state["media"]["completed"] += 1

    def hister_authorized(self):
        token = self.headers.get("X-Access-Token") or self.headers.get("Authorization", "").removeprefix("Bearer ")
        return token == HISTER_TOKEN

    def hister_add(self, raw):
        fault = take_fault("hister")
        if fault == "unauthorized" or not self.hister_authorized():
            return self.send_json(401, {"error": "unauthorized"})
        # Hister's CSRF guard: native clients identify with Origin: hister:// (docs/INTEGRATIONS.md).
        if self.headers.get("Origin") != "hister://":
            return self.send_json(403, {"error": "csrf"})
        try:
            data = json.loads(raw)
        except ValueError:
            return self.send_json(400, {"error": "bad json"})
        if not data.get("url"):
            return self.send_json(400, {"error": "url required"})
        self.record({"service": "hister", "method": "POST", "url": data["url"], "bytes": len(raw)})
        if fault == "too_many_requests":
            return self.send_json(429, {"error": "slow down"}, headers=[("Retry-After", "1")])
        if fault == "server_error":
            return self.send_json(503, {"error": "unavailable"})
        key = data["url"].split("#")[0]
        with lock:
            state["hister"][key] = {"url": data["url"], "title": data.get("title", ""), "text": data.get("text", "")}
        if fault == "store_then_hang":
            time.sleep(HANG_SECONDS)
        return self.send(201)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"serving Combray fixtures on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
