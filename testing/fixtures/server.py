#!/usr/bin/env python3
"""Synthetic pages and fake services for Combray's device checks (no live services, no personal data).

    python3 -I testing/fixtures/server.py [--port 8766]
    python3 -I testing/fixtures/server.py --write-pdfs DIR

The device reaches it through `adb reverse tcp:8766 tcp:8766` at http://localhost:8766.

Pages
  /solid/<rrggbb>.html       whole page in one color; title SOLID-<rrggbb> (pixel checks)
  /echo?q=...                title ECHO:<q as received> (query encoding checks)
  /cookie-set?v=<alnum>      sets cookie combray=<v> (one year)
  /cookie-echo               title COOKIE:<Cookie header>
  /opener.html               link with target=_blank to /solid/3b4f8a.html
  /image-download.html?k=<key>[&linked=1][&redirect=1][&fail=1]
                             page-filling green image, optionally linked to a blue page;
                             sets the browser cookie; title IMAGE-DOWNLOAD
  /download-image.png?k=<key>[&fail=1]
                             original 1x1 green PNG; fail=1 refuses the separate downloader
  /download-image-redirect.png?k=<key>[&fail=1]
                             redirects to the original PNG, forwarding fail
  /rendered.html?p=<alnum>   text rendered by script after load: RENDERED-<p reversed>
  /once.html?k=<alnum>       title ONCE on the first request per key; later ones close unanswered
                             (a reload that fails like a lost connection)
  /reused-close.html?k=<alnum>
                             title FRESH when it is a connection's first request; on a connection that
                             already carried a request it closes the connection unanswered, as a server
                             whose idle timeout races the request. /_admin/state "reused_close" counts,
                             per key, the requests "answered" and "dropped"
  /private.html              page for exclusion checks
  /slow.html                 answers after SLOW_PAGE_SECONDS; title SLOW
  /stop-loading.html?k=<key>  committed article, heartbeat title and held parser-blocking script
  /stop-loading-pending.html?k=<key> holds the document response before its first commit
  /screen.html               title SCREEN:<screen.width>:<screen.availWidth>:<outerWidth>:<innerWidth> (CSS px)
  /select.html               page-filling <select>; title SELECT:<value> after a change
  /dialogs.html              page-filling button: confirm, prompt, alert; title DIALOGS:<confirm>:<prompt>
  /upload.html               page-filling <input type=file accept=.txt>; title FILE:<name>:<text>
  /shadow-input.html         nested closed-shadow password field; title SHADOW:<state>,
                              then SHADOW:input:<value>:<paste count> after typing or pasting
  /contenteditable.html     rich text editor; title EDITOR:<state>, then
                              EDITOR:input:<text>:<last inputType> after each input;
                              ?k=<key> also reports each event's exact payload and DOM to
                              /_admin/editor-report, and ?cancel=<inputType> cancels that
                              beforeinput (no input event, no DOM change)
  /nested-editable.html     an editing host inside contenteditable=false; NESTED:* titles
  /shadow-editor.html       a contenteditable in a closed shadow root; SHADOWEDITOR:* titles
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
  /contrast.html             inherited text-color swatch, green paint marker and page canvas;
                              ?mode=white|dark|meta|authored exercises site color declarations
  /adaptive.html?mode=plain|meta-only|css-only|dark
                             light page for adaptive dark mode: a text-color border swatch on the left,
                             the green paint marker and the page canvas; the modes opt out in
                             <meta name=color-scheme> or CSS (light only) or are dark already;
                             title ADAPTIVE:<mode>
  /auth.html                 HTTP Basic sign-in (realm "fixture"): user "ada", password "lovelace" gives
                             title AUTH:ada; otherwise 401 with title AUTH-REQUIRED
  /media.html                <video> and <audio> from /media/slow.bin; title MEDIA:errors:<n> counts their
                             error events
  /media/slow.bin            512 KiB served over about eight seconds; /_admin/state "media" counts the
                             requests that arrived and the responses sent in full
  /video.html?k=<alnum>      <video src=/media/clip.mp4?k=...>; title VIDEO:errors:<n>
  /audio.html                <audio src=/media/tone.wav>; title AUDIO:errors:<n>
  /canplay.html?k=<alnum>    <video> that attaches /media/clip.mp4?k=... only when canPlayType
                             claims the MP4 codecs, as capability-driven sites do; title
                             CANPLAY:errors:<n>, or CANPLAY:unsupported when the claim is No
  /media-source.html?k=<alnum>
                             <video> whose only <source> is clip.mp4 typed video/mp4 with
                             codecs; skipped when the engine claims no MP4; title TYPED:errors:<n>
  /hls.html?k=<alnum>        <video> that attaches /media/hls.m3u8?k=... only when canPlayType
                             claims the HLS codecs; title HLS:errors:<n>, or HLS:unsupported
                             when the claim is No
  /hls-noext.html?k=<alnum>  the same, but the playlist is /media/hls-manifest: no .m3u8 for
                             the player to infer from
  /media-player.html?k=<alnum>
                             a site's own player: in an open shadow root, a <video> of
                             /media/clip.mp4?k=... taken out of hit testing under an overlay with
                             an error line and a play button, over the top half of the page; title
                             PLAYER:errors:<n>:outside:<n>:plays:<n> counts its errors, clicks
                             below it and clicks on its button
  /media-redirect.html?to=clip|file[&k=<alnum>]
                             <video> from /media/redirect; title REDIRECT:errors:<n>
  /media-local.html          a data: and a file: <video>, neither of them a web address; title LOCAL:errors:<n>
  /media-data-link.html?k=<alnum>
                             page-filling link to a data: page whose <video> is /media/clip.mp4?k=...
  /media/clip.mp4            testing/fixtures/media/clip.mp4 (6 s, green then blue; see its README),
                             with byte ranges (206, 416)
  /media/tone.wav            3 s of a 440 Hz tone, with byte ranges
  /media/hls.m3u8            variant playlist in X's shape (version 6, a separate audio
                             playlist, root-relative URIs): hls-video.m3u8 + hls-audio.m3u8;
                             also served extensionless at /media/hls-manifest
  /media/hls-video.m3u8      video playlist: EXT-X-MAP hls-init.mp4 + two fMP4 segments,
                             remuxed from clip.mp4 at start-up (green half, then blue)
  /media/hls-audio.m3u8      audio playlist: two committed AAC segments (media/README.md)
  /media/hls-init.mp4        fMP4 init segment for the video playlist
  /media/hls-video-{0,1}.m4s fMP4 media segments (video/iso.segment)
  /media/hls-audio-{0,1}.aac ADTS AAC media segments
  /media/hls-live.m3u8       a live video playlist (no ENDLIST), for download refusal
  /media/hls-refused.m3u8    a finite playlist whose segment redirects to file:, for download policy checks
                             /_admin/state "media" counts all of them under "hls"
  /media/redirect?to=clip|file[&k=<alnum>]
                             302 to /media/clip.mp4?k=..., or to file:///system/etc/hosts
                             /_admin/state "media" also has, for "clip" and "tone": requests, bytes sent
                             and the newest MEDIA_SEEN_LIMIT requests' Cookie, Referer and whether the
                             User-Agent names Servo ("servo_agent"); and "redirects", the redirects sent
  /zoom.html                 title ZOOM:<innerWidth>:<devicePixelRatio>, on load and on every resize
                             (page zoom checks)
  /fit-width.html            a row of an icon and a long sentence in an auto grid column; title
                             FIT:<scrollWidth>:<innerWidth>, on load and on every resize
  /fixed-scroll.html         transparent fixed scroll box over a red/green page
                              (?loading=1 holds an image until /_admin/finish-scroll-load)
  /wide.html                 title WIDE; no viewport meta tag; a 1080 CSS px row, red on its left
                             half and green on its right (?initial-scale=1 adds a viewport meta tag
                             with width=device-width, initial-scale=1)
  /agent.html                403 "Access denied" when the User-Agent names Firefox without a Gecko/
                             token, as some site firewalls answer; else title AGENT:same when
                             navigator.userAgent equals the header, AGENT:differs when not
  /document.pdf              one-page PDF (application/pdf) whose page reads PDF-FIXTURE-TEXT
  /document.bin              the same PDF as application/octet-stream with nosniff (Servo's stand-in page)
  /pdf/<name>.pdf            generated PDFs (pdf_fixtures; --write-pdfs DIR writes them for the viewer's
                             host tests): each page a band of one colour over its top 65%, with a
                             metadata title unless named otherwise, and Cache-Control: no-store
                               plain.pdf?k=     plum, PDF-PLAIN-TITLE; requests counted per k
                               untitled.pdf     plum, no title
                               pages.pdf        300 pages: sand, then navy; PDF-PAGES-TITLE
                               page-link.pdf    teal, linked to page 3 (sand), then navy
                               web-link.pdf     teal, linked to /solid/2e6b4f.html; javascript:, file: and
                                                intent: links below the band, an https: one off the page
                               cookie.pdf       plum, PDF-COOKIE-TITLE with the /cookie-set cookie, else 403
                               attachment.pdf   plum, PDF-ATTACHMENT-TITLE, Content-Disposition: attachment
                               corrupt.pdf      %PDF- and then nothing PDFium reads
                               protected.pdf    needs the user password "fixture"
                               large.pdf?k=     PDF_TOO_LARGE_BYTES with Content-Length, sent at about
                                                3 MB/s until the client stops reading
                             /_admin/state "pdf" has, per k, requests, bytesSent and whether the
                             response ended
  /pdf/link.html?k=          page-filling #6b4a2e link to /pdf/plain.pdf?k=
  /pdf/frame.html?k=         page-filling iframe of /pdf/plain.pdf?k=; title PDF-FRAME:loaded on its load
  (the five control pages have a #1f6f78 margin on the left for paint checks)
Fake Karakeep (root http://10.0.2.2:8766/keep on the emulator: a host of its own, because a
service's own host is never captured for Hister)
  POST /keep/api/v1/bookmarks, GET /keep/api/v1/bookmarks (newest first), GET .../bookmarks/search?q=,
  GET /keep/api/v1/lists (manual list "reading" plus a smart list), PUT .../lists/<list>/bookmarks/<id>;
  key "fixture-key"
Fake Hister (root http://10.0.2.2:8766/hister on the emulator)
  POST /hister/api/add (needs Origin: hister://), GET /hister/search?q=&format=json; token "fixture-token"
Fake report service (root http://10.0.2.2:8766/reports on the emulator; reporter token
"fixture-reporter-token")
  GET /reports/v1/whoami, POST /reports/v1/reports. A report is checked by the real service's
  rules (services/site-reports/site_reports/validation.py), so a passing device check proves the
  phone's request passes them. A repeated id with the same content answers 200, with other
  content 409 id_conflict. Bodies over 12 MiB answer 413 unread; the shared 2 MiB cap does not
  apply. /_admin/state "reports" holds each report's validated metadata, never its PNG.
Admin (for the checks)
  GET  /_admin/state                    JSON: bookmarks, lists, hister documents, reports, request log, media
  GET  /_admin/report-screenshot?id=<report id>
                                        the PNG a report carried (404 when it had none)
  POST /_admin/editor-report?k=<key>    a page's editor event report, stored under "editor" in the
                                        state (the page's own beforeinput/input payload and DOM)
  POST /_admin/reset                    forget everything
  POST /_admin/fault?service=karakeep|hister|reports&kind=<fault>&count=<n>
       faults: none, store_then_hang, too_many_requests, server_error, unauthorized
"""

import argparse
import base64
import hashlib
import html
import http.server
import json
import math
import pathlib
import re
import struct
import sys
import threading
import time
import urllib.parse

# The report service's own validation, so the fake accepts exactly what the service accepts.
# Appended, not prepended: the standard library keeps precedence over that directory.
SITE_REPORTS_DIR = pathlib.Path(__file__).resolve().parents[2] / "services" / "site-reports"
if str(SITE_REPORTS_DIR) not in sys.path:
    sys.path.append(str(SITE_REPORTS_DIR))
from site_reports import API_VERSION as REPORTS_API_VERSION  # noqa: E402
from site_reports import SERVICE_NAME as REPORTS_SERVICE_NAME  # noqa: E402
from site_reports import validation as report_validation  # noqa: E402

DEFAULT_PORT = 8766
# One RGB pixel (#2e6b4f). Tests verify the downloaded bytes, not a screenshot or re-encoding.
DOWNLOAD_IMAGE = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQy/YHAAGzAOkONZUWAAAAAElFTkSuQmCC"
)
KARAKEEP_KEY = "fixture-key"
SIGN_IN = ("ada", "lovelace")
HISTER_TOKEN = "fixture-token"
REPORTER_TOKEN = "fixture-reporter-token"
# The report service's defaults (REPORTS_MAX_REQUEST_MB, REPORTS_MAX_SCREENSHOT_MB).
MAX_REPORT_BODY_BYTES = 12 * 1024 * 1024
MAX_REPORT_SCREENSHOT_BYTES = 8 * 1024 * 1024
REPORTS_PREFIX = "/reports/v1"
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
# The committed clip (testing/fixtures/media/README.md): checked at start-up, as another encoder
# would make other bytes and the device checks' colours rely on these.
CLIP_PATH = pathlib.Path(__file__).resolve().parent / "media" / "clip.mp4"
CLIP_SHA256 = "ec7875cf658a2ea68bd825088fe8a09d30cc7090fbc5d0561f726aead61488f5"
# Requests whose headers /_admin/state keeps per media file, newest last.
MEDIA_SEEN_LIMIT = 20
STOP_LOAD_TIMEOUT_SECONDS = 60
STOP_HEARTBEAT_MS = 100
# Editor event reports per page key; a check reads the events it asked for, not the whole run.
EDITOR_REPORT_LIMIT = 64
STOP_KEY = re.compile(r"[a-zA-Z0-9]{1,32}\Z")
TONE_HZ = 440
TONE_RATE = 8000
TONE_SECONDS = 3
TONE_AMPLITUDE = 8000
FILE_TARGET = "file:///system/etc/hosts"
RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")
# A check's unique key (`k=`) marks the addresses it looks for in the app's log; the request log
# leaves its value out.
KEY_VALUE = re.compile(r"(?<=[?&]k=)[^&#]*")

lock = threading.Lock()
log_lock = threading.Lock()


def logged_target(target):
    """A request target as the request log shows it: each `k=` value is left out."""
    return KEY_VALUE.sub("<key>", target)


def log_request_line(method, target, now=None):
    """One line per request on stdout (fixtures.log in a device-check run), in logcat's
    `threadtime` time format so it lines up with logcat.txt. Logcat stamps the device's local
    time: the emulator keeps the host's, while a phone's clock or zone may differ."""
    now = time.time() if now is None else now
    stamp = time.strftime("%m-%d %H:%M:%S", time.localtime(now)) + f".{int(now * 1000) % 1000:03d}"
    with log_lock:
        sys.stdout.write(f"{stamp} {method} {logged_target(target)}\n")
        sys.stdout.flush()
def fresh_lists():
    """List id -> bookmark ids in it. Lists that are not here answer 404."""
    return {"reading": []}


def fresh_served():
    """One media file's requests, bytes sent and the newest requests' headers."""
    return {"requests": 0, "bytes": 0, "seen": []}


def fresh_media():
    """Requests for /media/slow.bin that arrived and responses sent to the end; the player's files and redirects."""
    return {"requests": 0, "completed": 0, "clip": fresh_served(), "tone": fresh_served(), "hls": fresh_served(), "redirects": 0}


state = {"bookmarks": {}, "lists": fresh_lists(), "hister": {}, "reports": {}, "log": [], "media": fresh_media(),
         "pdf": {}, "stop_loads": {}, "reused_close": {}, "editor": {}}
faults = {"karakeep": ["none", 0], "hister": ["none", 0], "reports": ["none", 0]}
# Report id -> the PNG it carried; kept out of the JSON state, which holds metadata only.
report_screenshots = {}
once_served = set()  # /once.html keys already answered (a set, so not part of the JSON state)
scroll_load_finished = threading.Event()
stop_load_gates = {}


def stop_load(key):
    """One gate and counters per check: a late response cannot affect the next check."""
    with lock:
        if key not in stop_load_gates:
            counts = {"requested": 0, "finished": 0}
            state["stop_loads"][key] = counts
            stop_load_gates[key] = (threading.Event(), counts)
        return stop_load_gates[key]


# Generated PDFs (deterministic, no external tools). Each page is US Letter with a solid band of
# its colour over the top PDF_BAND of the page, so a screenshot shows which page is on screen.
PDF_PAGE_POINTS = (612, 792)
PDF_BAND = 0.65
# Band colours. None is used by another fixture or the app, and they stay apart by more than the
# device checks' colour tolerance.
PDF_PLUM = 0x7a2e64
PDF_TEAL = 0x2a8a8a
PDF_SAND = 0xa08a3a
PDF_NAVY = 0x24306e
PDF_PAGES = 300
# Over the viewer's 64 MiB cap (owner decision, 29 October 2026).
PDF_TOO_LARGE_BYTES = 65 * 1024 * 1024
PDF_STREAM_CHUNK = 64 * 1024
# Pause after each chunk of large.pdf: about 3 MB/s, so the whole body takes over 20 s. Unpaced,
# `adb reverse` took all 65 MiB within 2 s on the CI emulator, before the check could tell a
# cancelled download from a finished one.
PDF_STREAM_PAUSE_S = 0.02


def pdf_string(text):
    """A PDF literal string for ASCII `text`."""
    escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    return f"({escaped})".encode("ascii")


# The padding string of the PDF standard security handler (ISO 32000-1, 7.6.3.3).
PDF_PASSWORD_PAD = bytes.fromhex("28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a")
PDF_FILE_ID = hashlib.md5(b"combray-fixture").digest()


def rc4(key, data):
    """RC4, which the PDF standard security handler's revision 2 uses (not for anything secret)."""
    box = list(range(256))
    j = 0
    for i in range(256):
        j = (j + box[i] + key[i % len(key)]) % 256
        box[i], box[j] = box[j], box[i]
    i = j = 0
    out = bytearray()
    for byte in data:
        i = (i + 1) % 256
        j = (j + box[i]) % 256
        box[i], box[j] = box[j], box[i]
        out.append(byte ^ box[(box[i] + box[j]) % 256])
    return bytes(out)


def pdf_encryption(user_password):
    """The /Encrypt dictionary of revision 2 (40-bit RC4) for `user_password` (ISO 32000-1,
    algorithms 2 to 4). PDFium checks the password against /U before it decrypts anything, so the
    fixture's content stays unencrypted: without the password nothing is read."""
    padded = (user_password.encode("latin-1") + PDF_PASSWORD_PAD)[:32]
    owner_key = hashlib.md5(padded).digest()[:5]
    owner = rc4(owner_key, padded)
    permissions = -44
    key = hashlib.md5(padded + owner + struct.pack("<i", permissions) + PDF_FILE_ID).digest()[:5]
    user = rc4(key, PDF_PASSWORD_PAD)
    return b"<< /Filter /Standard /V 1 /R 2 /O <%s> /U <%s> /P %d >>" % (
        owner.hex().encode(), user.hex().encode(), permissions)


def pdf_file(pages, title=None, user_password=None):
    """A valid PDF of `pages`, each (band colour, text, links); `title` goes in its Info dictionary,
    and a `user_password` makes it need one.

    A link is (rect, target): rect as fractions of the page (left, top, right, bottom, from the top
    left corner), target ("uri", address) or ("page", 0-based index).
    """
    width, height = PDF_PAGE_POINTS
    band = round(height * PDF_BAND)
    objects = []

    def reserve():
        objects.append(None)
        return len(objects)

    catalog, tree, font = reserve(), reserve(), reserve()
    objects[font - 1] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    kids = [reserve() for _ in pages]
    for number, (color, text, links) in zip(kids, pages):
        red, green, blue = ((color >> shift) & 0xFF for shift in (16, 8, 0))
        stream = (b"%.4f %.4f %.4f rg 0 %d %d %d re f 0 g BT /F1 24 Tf 72 %d Td %s Tj ET"
                  % (red / 255, green / 255, blue / 255, height - band, width, band, height - band - 48,
                     pdf_string(text)))
        contents = reserve()
        objects[contents - 1] = b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)
        annotations = []
        for (left, top, right, bottom), (kind, target) in links:
            if kind == "uri":
                action = b"<< /S /URI /URI %s >>" % pdf_string(target)
            else:
                action = b"<< /S /GoTo /D [%d 0 R /XYZ 0 %d 0] >>" % (kids[target], height)
            annotation = reserve()
            objects[annotation - 1] = (
                b"<< /Type /Annot /Subtype /Link /Rect [%d %d %d %d] /Border [0 0 0] /A %s >>"
                % (left * width, height - bottom * height, right * width, height - top * height, action))
            annotations.append(b"%d 0 R" % annotation)
        objects[number - 1] = (
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Resources << /Font << /F1 %d 0 R >> >> "
            b"/Contents %d 0 R /Annots [%s] >>" % (tree, width, height, font, contents, b" ".join(annotations)))
    objects[catalog - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % tree
    objects[tree - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (
        b" ".join(b"%d 0 R" % kid for kid in kids), len(kids))
    info = b""
    if title is not None:
        objects.append(b"<< /Title %s /Producer (Combray fixtures) >>" % pdf_string(title))
        info = b" /Info %d 0 R" % len(objects)
    if user_password is not None:
        objects.append(pdf_encryption(user_password))
        file_id = PDF_FILE_ID.hex().encode()
        info += b" /Encrypt %d 0 R /ID [<%s> <%s>]" % (len(objects), file_id, file_id)

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n%s\nendobj\n" % (number, body)
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % offset for offset in offsets)
    out += b"trailer\n<< /Size %d /Root %d 0 R%s >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, info, xref)
    return bytes(out)


def pdf_document(text):
    """A minimal valid one-page PDF showing `text` (ASCII) in Helvetica, without a title."""
    return pdf_file([(0xFFFFFF, text, [])])


# The band covers the point a tap or a screenshot checks (35% down a portrait screen).
PDF_LINK_RECT = (0.05, 0.05, 0.95, 0.6)
# /pdf/link.html: a page-filling link on a background no other fixture uses.
PDF_LINK_PAGE = "6b4a2e"
PDF_PAGE_STYLE = ("<style>html,body{margin:0;height:100%%;background:#%s}"
                  ".c{display:block;width:100vw;height:100vh;font-size:40px;color:#fff}</style>")
PDF_DOCUMENT = pdf_document("PDF-FIXTURE-TEXT")
PDF_PLAIN = pdf_file([(PDF_PLUM, "PDF-PLAIN-TEXT", [])], title="PDF-PLAIN-TITLE")
PDF_UNTITLED = pdf_file([(PDF_PLUM, "PDF-UNTITLED-TEXT", [])])
# Page 1 in sand, the others in navy: the screen shows whether the first page is on top.
PDF_MANY_PAGES = pdf_file([(PDF_SAND if index == 0 else PDF_NAVY, f"PDF-PAGE-{index + 1}", [])
                           for index in range(PDF_PAGES)], title="PDF-PAGES-TITLE")
# A link over page 1's band (teal) to page 3 (sand); the others in navy, enough of them for
# page 3 to scroll to the top of the screen.
PDF_PAGE_LINK = pdf_file([(PDF_TEAL, "PDF-LINK-PAGE-1", [(PDF_LINK_RECT, ("page", 2))])] + [
    (PDF_SAND if number == 3 else PDF_NAVY, f"PDF-LINK-PAGE-{number}", []) for number in range(2, 7)
], title="PDF-PAGE-LINK-TITLE")
# Starts like a PDF, then nothing PDFium can read.
PDF_CORRUPT = b"%PDF-1.7\n" + bytes((index * 37 + 11) % 251 for index in range(4096))
PDF_PROTECTED = pdf_file([(PDF_PLUM, "PDF-PROTECTED-TEXT", [])], user_password="fixture")
PDF_COOKIE = pdf_file([(PDF_PLUM, "PDF-COOKIE-TEXT", [])], title="PDF-COOKIE-TITLE")
PDF_ATTACHMENT = pdf_file([(PDF_PLUM, "PDF-ATTACHMENT-TEXT", [])], title="PDF-ATTACHMENT-TITLE")


def pdf_web_link(target):
    """A one-page PDF whose band links to `target`; links PDFs may not follow sit under the band,
    and one lies off the page."""
    ignored = [
        ((0.05, 0.62, 0.3, 0.7), ("uri", "javascript:document.title='PDF-SCRIPT-RAN'")),
        ((0.35, 0.62, 0.6, 0.7), ("uri", "file:///system/etc/hosts")),
        ((0.65, 0.62, 0.95, 0.7), ("uri", "intent://fixture#Intent;scheme=http;end")),
        ((1.1, 0.1, 1.3, 0.2), ("uri", "https://example.test/off-the-page")),
    ]
    return pdf_file([(PDF_TEAL, "PDF-LINK-WEB", [(PDF_LINK_RECT, ("uri", target))] + ignored)],
                    title="PDF-WEB-LINK-TITLE")


def pdf_fixtures(base):
    """Every generated PDF by file name, with links to `base` (for the viewer's host tests)."""
    return {
        "plain.pdf": PDF_PLAIN,
        "untitled.pdf": PDF_UNTITLED,
        "pages.pdf": PDF_MANY_PAGES,
        "page-link.pdf": PDF_PAGE_LINK,
        "web-link.pdf": pdf_web_link(f"{base}/solid/2e6b4f.html"),
        "corrupt.pdf": PDF_CORRUPT,
        "protected.pdf": PDF_PROTECTED,
        "document.pdf": PDF_DOCUMENT,
    }


def tone_wav():
    """A 440 Hz tone as 16-bit mono PCM WAV, built without ffmpeg."""
    frames = b"".join(
        struct.pack("<h", int(TONE_AMPLITUDE * math.sin(2 * math.pi * TONE_HZ * n / TONE_RATE)))
        for n in range(TONE_RATE * TONE_SECONDS)
    )
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(frames), b"WAVE", b"fmt ", 16, 1, 1,
                         TONE_RATE, TONE_RATE * 2, 2, 16, b"data", len(frames))
    return header + frames


TONE_WAV = tone_wav()


# --- fMP4 HLS video, remuxed from the clip -------------------------------------------
#
# clip.mp4 holds 180 samples of one H.264 track, each 512/15360 s long, 90 green and 90
# blue (testing/fixtures/media/README.md). The pages' HLS fixture needs those same
# samples as fragmented MP4: an init segment with an empty sample table, and one media
# segment per half, split at the keyframe on the boundary. Only the boxes around the
# samples are rebuilt; the samples move unchanged, so no encoder is needed. The shapes
# match how sites stream HLS (EXT-X-MAP init segment, styp+moof+mdat segments).

def mp4_boxes(data, start=0, end=None):
    """(type, offset, size, header size) for each box in [start, end)."""
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, typ = struct.unpack(">I4s", data[pos:pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        if size == 0:
            size = end - pos
        yield typ.decode(), pos, size, header
        pos += size


def mp4_find(data, path):
    """The whole-box span (offset, size) of the box `path` names, e.g. "moov.trak.mdia.mdhd"."""
    start, end = 0, len(data)
    names = path.split(".")
    for name in names:
        for typ, pos, size, header in mp4_boxes(data, start, end):
            if typ == name:
                if name == names[-1]:
                    return pos, size
                start, end = pos + header, pos + size
                break
        else:
            raise ValueError(f"{path}: no {name}")


def mp4_whole(data, path):
    pos, size = mp4_find(data, path)
    return data[pos:pos + size]


def mp4_content(data, path):
    pos, size = mp4_find(data, path)
    return data[pos + 8:pos + size]


def mp4_table(data, path, fields, offset=8):
    """The records of `fields` unsigned ints in a versioned full box's table."""
    body = mp4_content(data, path)
    count = struct.unpack(">I", body[4:8])[0]
    return [struct.unpack(">" + "I" * fields, body[offset + i * 4 * fields:offset + (i + 1) * 4 * fields])
            for i in range(count)]


def mp4_box(typ, payload):
    return struct.pack(">I4s", 8 + len(payload), typ) + payload


def remux_fmp4(clip):
    """clip's video track as (init segment, [media segments]) with the samples unchanged."""
    mvhd = mp4_whole(clip, "moov.mvhd")
    tkhd = mp4_whole(clip, "moov.trak.tkhd")
    mdhd = mp4_whole(clip, "moov.trak.mdia.mdhd")
    hdlr = mp4_whole(clip, "moov.trak.mdia.hdlr")
    vmhd = mp4_whole(clip, "moov.trak.mdia.minf.vmhd")
    dinf = mp4_whole(clip, "moov.trak.mdia.minf.dinf")
    stsd = mp4_whole(clip, "moov.trak.mdia.minf.stbl.stsd")

    durations = [d for count, d in mp4_table(clip, "moov.trak.mdia.minf.stbl.stts", 2)
                 for _ in range(count)]
    stss = mp4_content(clip, "moov.trak.mdia.minf.stbl.stss")
    sync = {struct.unpack(">I", stss[8 + i * 4:12 + i * 4])[0] - 1
            for i in range(struct.unpack(">I", stss[4:8])[0])}
    stsz = mp4_content(clip, "moov.trak.mdia.minf.stbl.stsz")
    assert struct.unpack(">I", stsz[4:8])[0] == 0, "per-sample sizes expected"
    sizes = [struct.unpack(">I", stsz[12 + i * 4:16 + i * 4])[0]
             for i in range(struct.unpack(">I", stsz[8:12])[0])]
    stco = mp4_content(clip, "moov.trak.mdia.minf.stbl.stco")
    chunks = [struct.unpack(">I", stco[8 + i * 4:12 + i * 4])[0]
              for i in range(struct.unpack(">I", stco[4:8])[0])]

    # Sample offsets from the chunk layout (stsc + stco).
    offsets = []
    sample = 0
    stsc = mp4_table(clip, "moov.trak.mdia.minf.stbl.stsc", 3)
    for i, (first_chunk, per_chunk, _desc) in enumerate(stsc):
        last = stsc[i + 1][0] - 1 if i + 1 < len(stsc) else len(chunks)
        for chunk in chunks[first_chunk - 1:last]:
            pos = chunk
            for _ in range(per_chunk):
                offsets.append(pos)
                pos += sizes[sample]
                sample += 1
    assert sample == len(sizes) == len(durations), "sample tables disagree"

    def empty_table(t):
        return mp4_box(t, b"\x00\x00\x00\x00" + struct.pack(">I", 0))

    # The init segment's stbl is empty: the samples live in the segments, not the moov.
    stbl = mp4_box(b"stbl", stsd + empty_table(b"stts") + empty_table(b"stsc")
                   + mp4_box(b"stsz", b"\x00\x00\x00\x00" + struct.pack(">II", 0, 0))
                   + empty_table(b"stco"))
    mdia = mp4_box(b"mdia", mdhd + hdlr + mp4_box(b"minf", vmhd + dinf + stbl))
    trex = mp4_box(b"trex", b"\x00\x00\x00\x00" + struct.pack(">IIIII", 1, 1, 0, 0, 0))
    init = mp4_whole(clip, "ftyp") + mp4_box(
        b"moov", mvhd + mp4_box(b"trak", tkhd + mdia) + mp4_box(b"mvex", trex))

    def segment(seq, first, count):
        mfhd = mp4_box(b"mfhd", b"\x00\x00\x00\x00" + struct.pack(">I", seq))
        # default-base-is-moof: trun's data_offset counts from this moof's start.
        tfhd = mp4_box(b"tfhd", struct.pack(">II", 0x020000, 1))
        tfdt = mp4_box(b"tfdt", struct.pack(">I", 0x01000000)
                       + struct.pack(">Q", sum(durations[:first])))
        # trun flags 0x701: data-offset + per-sample duration, size and flags.
        trun_rows = b"".join(
            struct.pack(">III", durations[first + i], sizes[first + i],
                        0x02000000 if first + i in sync else 0x01010000)
            for i in range(count))

        def trun(data_offset):
            return mp4_box(b"trun", struct.pack(">IIi", 0x000701, count, data_offset) + trun_rows)

        moof = mp4_box(b"moof", mfhd + mp4_box(b"traf", tfhd + tfdt + trun(0)))
        moof = mp4_box(b"moof", mfhd + mp4_box(b"traf", tfhd + tfdt + trun(len(moof) + 8)))
        mdat = mp4_box(b"mdat", b"".join(
            clip[offsets[first + i]:offsets[first + i] + sizes[first + i]] for i in range(count)))
        return mp4_box(b"styp", b"iso6" + struct.pack(">I", 0) + b"iso6mp41dash") + moof + mdat

    half = len(sizes) // 2
    assert half in sync, "the second segment must start on a keyframe"
    return init, [segment(1, 0, half), segment(2, half, len(sizes) - half)]


HLS_INIT, HLS_VIDEO_SEGMENTS = remux_fmp4(CLIP_PATH.read_bytes())
HLS_AUDIO_SEGMENTS = [
    (CLIP_PATH.parent / "seg-a0.aac").read_bytes(),
    (CLIP_PATH.parent / "seg-a1.aac").read_bytes(),
]


def hls_master_playlist(key):
    """A variant playlist in X's shape (docs/VIDEO.md): version 6, a video rendition and
    its separate audio playlist, both addressed root-relative."""
    audio = f"/media/hls-audio.m3u8?k={key}"
    return (
        "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n"
        f"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"{audio}\"\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.42E01E,mp4a.40.2\",RESOLUTION=320x180,AUDIO=\"a\"\n"
        f"/media/hls-video.m3u8?k={key}\n"
    )


def hls_media_playlist(entries, init_uri=None):
    """A VOD media playlist for (duration, URI) segment `entries`; `init_uri` is the
    fMP4 init segment (EXT-X-MAP) the segments continue."""
    target = math.ceil(max(duration for duration, _ in entries))
    lines = (f"#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:{target}\n"
             "#EXT-X-PLAYLIST-TYPE:VOD\n")
    if init_uri is not None:
        lines += f"#EXT-X-MAP:URI=\"{init_uri}\"\n"
    lines += "".join(f"#EXTINF:{duration:.3f},\n{uri}\n" for duration, uri in entries)
    return lines + "#EXT-X-ENDLIST\n"


def hls_page(key, src=None):
    """A <video> that asks canPlayType about HLS before attaching its playlist."""
    src = src or f"/media/hls.m3u8?k={key}"
    return page(
        "HLS",
        "<video controls onerror='failed()'></video>"
        "<script>var v = document.querySelector('video');"
        f" if (v.canPlayType('{HLS_CODECS}') === '') {{"
        " document.title = 'HLS:unsupported'; }"
        f" else {{ v.src = '{src}'; }}</script>",
        "<script>var errors = 0; function failed() { errors++; document.title = 'HLS:errors:' + errors; }</script>",
    )


def media_key(query):
    """The `k` parameter, letters and digits only."""
    return "".join(c for c in query.get("k", [""])[0] if c.isascii() and c.isalnum())[:64]


def media_page(title, element, src):
    """One media element that fails in Combray; the title counts its error events."""
    return page(
        title,
        f"<{element} src='{html.escape(src, quote=True)}' controls onerror='failed()'></{element}>",
        f"<script>var errors = 0; function failed() {{ errors++; document.title = '{title}:errors:' + errors; }}</script>",
    )


# The codecs the claim table calls supported; what capability-driven pages ask about (docs/VIDEO.md).
MP4_CODECS = 'video/mp4; codecs="avc1.42E01E, mp4a.40.2"'
HLS_CODECS = 'application/vnd.apple.mpegurl; codecs="avc1.42E01E, mp4a.40.2"'


def canplay_page(key):
    """A <video> that asks canPlayType before attaching its source: no answer, no fetch."""
    return page(
        "CANPLAY",
        "<video controls onerror='failed()'></video>"
        "<script>var v = document.querySelector('video');"
        f" if (v.canPlayType('{MP4_CODECS}') === '') {{"
        " document.title = 'CANPLAY:unsupported'; }"
        f" else {{ v.src = '/media/clip.mp4?k={key}'; }}</script>",
        "<script>var errors = 0; function failed() { errors++; document.title = 'CANPLAY:errors:' + errors; }</script>",
    )


def player_page(key):
    """A site's own player: the <video> sits in an open shadow root, out of hit testing, under an
    overlay with its error line and play button, as custom players draw it. Clicks on the button
    and below the player are counted, so a check knows a tap reached the page."""
    return page(
        "PLAYER",
        "<div id='player'></div><div id='below'></div>"
        "<script>var errors = 0, outside = 0, plays = 0;"
        " function report() { document.title = 'PLAYER:errors:' + errors + ':outside:' + outside + ':plays:' + plays; }"
        " var root = document.getElementById('player').attachShadow({ mode: 'open' });"
        " root.innerHTML = \"<style>:host{display:block;position:relative;height:50vh;background:#000}"
        "video{display:block;width:100%;height:100%;pointer-events:none}"
        ".overlay{position:absolute;top:0;right:0;bottom:0;left:0;display:flex;flex-direction:column;"
        "align-items:center;justify-content:center;color:#fff;font-size:24px}"
        # The error line is out of the flow, so the button stays in the middle of the player.
        "p{position:absolute;top:8px;left:0;right:0;margin:0;text-align:center}"
        "button{font-size:40px;padding:24px 48px}</style>"
        "<video></video><div class='overlay'><p id='error' hidden>Playback failed.</p><button>&#9654;</button></div>\";"
        " var video = root.querySelector('video');"
        " video.addEventListener('error', function () { errors++; root.querySelector('#error').hidden = false; report(); });"
        " root.querySelector('button').addEventListener('click', function () {"
        " plays++; report(); var played = video.play(); if (played) played.catch(function () {}); });"
        " document.getElementById('below').addEventListener('click', function () { outside++; report(); });"
        f" video.src = '/media/clip.mp4?k={key}'; report();</script>",
        "<style>html,body{margin:0}#below{height:150vh;background:#2e6b4f}</style>",
    )


def typed_source_page(key):
    """A <video> whose only <source> has a codec-claiming type; a No skips it before its fetch."""
    return page(
        "TYPED",
        "<video controls onerror='failed()'>"
        f"<source src='/media/clip.mp4?k={key}' type='{MP4_CODECS}' onerror='failed()'>"
        "</video>",
        "<script>var errors = 0; function failed() { errors++; document.title = 'TYPED:errors:' + errors; }</script>",
    )


def page(title, body, head=""):
    return (
        "<!doctype html><html><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width, initial-scale=1'>"
        f"<title>{html.escape(title)}</title>{head}</head><body>{body}</body></html>"
    )


# Pages for adaptive dark mode: (extra head markup, extra CSS) per mode.
ADAPTIVE_MODES = {
    "plain": ("", ""),
    "meta-only": ("<meta name='color-scheme' content='light only'>", ""),
    "css-only": ("", ":root{color-scheme:light only}"),
    "dark": ("", "html{background:#202124;color:#e8eaed}"),
}

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
    f"<p>READER-BODY-ONE <a id='inner' href='/solid/2f6b3c.html'>READER-LINK</a> "
    f"<a href='https://'>READER-REFUSED-LINK</a> {ARTICLE_PARAGRAPH * 3}</p>"
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

# Reports in its title whether the experimental APIs Combray turns on in Servo work: FontFace with
# document.fonts, adoptedStyleSheets and IndexedDB. Pages that call them unconditionally stop with a
# TypeError without them.
APIS_PAGE = page(
    "XP:waiting",
    "<p id='target'>styled</p><script>"
    "function fail(why) { document.title = 'XP:fail:' + why; }"
    "try {"
    " var face = new FontFace('Fixture Sans', 'local(sans-serif)');"
    " document.fonts.add(face);"
    " if (!document.fonts.has(face)) throw new Error('font not added');"
    " var sheet = new CSSStyleSheet();"
    " sheet.insertRule('#target { color: rgb(0, 128, 0); }');"
    " document.adoptedStyleSheets = [sheet];"
    " var color = getComputedStyle(document.getElementById('target')).color;"
    " if (color !== 'rgb(0, 128, 0)') throw new Error('adopted sheet ' + color);"
    " var open = indexedDB.open('fixture', 1);"
    " open.onupgradeneeded = function () { open.result.createObjectStore('store'); };"
    " open.onerror = function () { fail('open ' + open.error); };"
    " open.onsuccess = function () { try {"
    "  var store = open.result.transaction('store', 'readwrite').objectStore('store');"
    "  store.put({value: 7}, 'key');"
    "  var read = store.get('key');"
    "  read.onerror = function () { fail('get ' + read.error); };"
    "  read.onsuccess = function () {"
    "   if (read.result && read.result.value === 7) document.title = 'XP:ok';"
    "   else fail('read ' + JSON.stringify(read.result));"
    "  };"
    " } catch (e) { fail('store ' + e.message); } };"
    "} catch (e) { fail(e.message); }</script>",
)

# Reports in its title whether the page survives closing a message port it has just transferred,
# and whether the transferred port keeps its channel. Servo 0.7.0 panicked the page's script
# thread on that close() and left the page frozen.
PORT_CLOSE_PAGE = page(
    "PC:waiting",
    "<p>ports</p><script>"
    "function fail(why) { document.title = 'PC:fail:' + why; }"
    "try {"
    " var channel = new MessageChannel();"
    " window.addEventListener('message', function (event) {"
    "  var moved = event.ports[0];"
    "  if (!moved) return fail('no port');"
    "  moved.onmessage = function (m) {"
    "   document.title = m.data === 'ping' ? 'PC:ok' : 'PC:fail:data ' + m.data;"
    "  };"
    "  channel.port2.postMessage('ping');"
    " });"
    " window.postMessage('port', '*', [channel.port1]);"
    " channel.port1.close();"
    "} catch (e) { fail(e.message); }</script>",
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
    "  var indexed = box.animate({opacity: new Set([0, 0.5, 1]), offset: [0, 0.2, 1], composite: 'auto',"
    "   unanimatable: [1, 2, 3, 4]}, {duration: 1000, fill: 'forwards'});"
    "  indexed.pause();"
    "  indexed.currentTime = 100;"
    "  var between = parseFloat(getComputedStyle(box).opacity);"
    "  indexed.cancel();"
    "  if (!(Math.abs(between - 0.25) <= 0.01)) fail('property-indexed offsets ' + between);"
    "  new KeyframeEffect(box, {opacity: [0, 1], offset: 0}, 1000);"
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


def names_firefox_without_gecko(user_agent):
    """A site firewall rule: an agent that names Firefox without Gecko/ is taken for a forged one."""
    return "Firefox" in user_agent and "Gecko/" not in user_agent


def agent_page(user_agent):
    """Titled AGENT:same when the page's navigator.userAgent is the User-Agent header it came with."""
    # Escaped for a script element: the header is the client's, not the fixture's.
    header = json.dumps(user_agent).replace("<", "\\u003c")
    return page(
        "AGENT-WAITING",
        "<p>agent</p><script>"
        f"document.title = 'AGENT:' + (navigator.userAgent === {header} ? 'same' : 'differs');</script>",
    )


# A row of an icon that cannot shrink and a long sentence, in an auto grid column: the sentence
# must wrap, so the page stays as wide as the viewport. Servo once sized such a row's min-content
# width to the whole sentence on one line, and the column widened the page. On one line the
# sentence is far wider than any screen (over 2500 CSS px at 16px), so the check means the same
# on a phone, a tablet in landscape or a desktop window.
FIT_WIDTH_SENTENCE = ("This caption is a single long sentence that needs several lines on a phone, "
                      "because no screen is wide enough to show all of it on one line at a readable size, "
                      "not a phone held sideways, not a tablet in landscape and not a desktop monitor either, "
                      "so the text has to wrap however the device is held and whichever screen runs the check, "
                      "and a page that holds it fits its viewport only when the row wraps.")
FIT_WIDTH_PAGE = page(
    "FIT-WAITING",
    "<main><figure><figcaption class='row'><span class='icon'></span>"
    f"<span>{FIT_WIDTH_SENTENCE}</span></figcaption></figure></main><script>"
    "function report() {"
    " document.title = 'FIT:' + document.documentElement.scrollWidth + ':' + innerWidth; }"
    "addEventListener('load', report); addEventListener('resize', report);</script>",
    "<style>html,body{margin:0}main{display:grid;padding:0 16px}figure{margin:0}"
    ".row{display:flex;gap:16px}.icon{flex-shrink:0;width:24px;height:24px;background:#1f6f78}</style>",
)

# Content wider than a phone's screen: a 1080 CSS px row, red on its left half and green on its
# right. Without a viewport meta tag (or `initial-scale`) the page opens zoomed out to its full
# width, as in mobile browsers, so green shows at the screen's right edge; with initial-scale=1 it
# opens at 100%, showing only red, until a pinch zooms out. Built without page(), whose viewport
# meta tag would set the initial scale.
def wide_page(initial_scale):
    meta = "<meta name='viewport' content='width=device-width, initial-scale=1'>" if initial_scale else ""
    return (
        f"<!doctype html><html><head><meta charset='utf-8'>{meta}<title>WIDE</title>"
        "<style>html,body{margin:0}.row{display:flex;width:1080px}"
        ".row div{flex:none;width:540px;height:4000px}</style></head><body>"
        "<div class='row'><div style='background:#b03a2e'></div>"
        "<div style='background:#2e6b4f'></div></div></body></html>"
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
# also where find must not count it (hidden, invisible, a script, a form field). The title
# reports the scroll offset and the selected text: "FIND:<scrollY>:<selection>".
FIND_GAP = "<div style='height:150vh'></div>"
FIND_PAGE = page(
    "FIND",
    "<p>Find fixture: the word is further down.</p>"
    f"{FIND_GAP}<p>First combraneedle here.</p>"
    f"{FIND_GAP}<p>Second CombraNeedle here.</p>"
    f"{FIND_GAP}<p>Third COMBRANEEDLE here.</p>{FIND_GAP}"
    "<div hidden>combraneedle hidden</div><div style='display:none'>combraneedle not shown</div>"
    "<div style='visibility:hidden'>combraneedle <span>not painted</span></div>"
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

def editor_page(prefix, control, setup, editor, extra=""):
    """A rich-text editor that reports its exact InputEvents and DOM to the fixture server.

    The title keeps the `<prefix>:` form the checks expect. When the address carries `k`, every
    event also goes to /_admin/editor-report?k=, so a check can assert the payloads and the DOM
    the engine produced; `extra` adds page-specific facts to each report, and ?cancel=<inputType>
    makes the page cancel that beforeinput, so a check can prove the default action is gone.
    """
    script = (
        "<script>addEventListener('load',function(){"
        + setup
        + "var e=" + editor + ";"
        "var k=(location.search.match(/[?&]k=([^&]*)/)||['',''])[1];"
        "var cancel=(location.search.match(/[?&]cancel=([^&]*)/)||['',''])[1];"
        "var kind='';"
        "function dom(){var kids=[];"
        "for(var i=0;i<e.childNodes.length;i++){var n=e.childNodes[i];"
        "if(n.nodeType===3)kids.push('t:'+n.data);"
        "else if(n.nodeType===1)kids.push(n.nodeName.toLowerCase()+':'+n.innerHTML);"
        "else kids.push(n.nodeName)}"
        "return {html:e.innerHTML,text:e.textContent,children:kids}}"
        "function sel(){var s=window.getSelection();if(!s||s.rangeCount===0)return null;"
        "var f=function(n){if(!n)return 'null';"
        "if(n.nodeType===3)return 't:'+n.data;"
        "if(n.nodeType===1)return n.nodeName.toLowerCase()+'['+n.innerHTML+']';"
        "return n.nodeName};"
        "var root=e.getRootNode();var ranges=s.getComposedRanges({shadowRoots:root.host?[root]:[]});"
        "var r=ranges[0];var backward=s.direction==='backward';"
        "var a=r?(backward?r.endContainer:r.startContainer):s.anchorNode;"
        "var z=r?(backward?r.startContainer:r.endContainer):s.focusNode;"
        "var ao=r?(backward?r.endOffset:r.startOffset):s.anchorOffset;"
        "var zo=r?(backward?r.startOffset:r.endOffset):s.focusOffset;"
        "return {anchor:f(a)+'@'+ao,focus:f(z)+'@'+zo,collapsed:!!s.isCollapsed,text:s.toString()}}"
        "function report(phase,v){"
        "var ev={phase:phase,"
        "inputType:v&&v.inputType!==undefined?v.inputType:null,"
        "data:v&&v.data!==undefined?v.data:null,"
        "dataTransfer:v&&v.dataTransfer?String(v.dataTransfer):null,"
        "isComposing:v?!!v.isComposing:null,"
        "isTrusted:v?!!v.isTrusted:null,composed:v?!!v.composed:null,"
        "cancelable:v?!!v.cancelable:null,"
        "defaultPrevented:v?!!v.defaultPrevented:null,"
        "target:v&&v.target?(v.target.id||v.target.nodeName):null,"
        "targetRanges:v&&v.getTargetRanges?v.getTargetRanges().length:null,"
        "dom:dom(),selection:sel()"
        + extra + "};"
        "if(phase==='beforeinput'&&cancel&&ev.inputType===cancel){v.preventDefault();ev.defaultPrevented=true;}"
        "if(k){fetch('/_admin/editor-report?k='+encodeURIComponent(k),"
        "{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(ev)})"
        ".catch(function(){});}"
        "if(phase==='beforeinput')kind=ev.inputType;"
        "if(phase==='input')document.title='" + prefix + ":input:'+e.textContent+':'+kind;"
        "}"
        "e.onfocus=function(){document.title='" + prefix + ":focused';report('focus',null)};"
        "e.onblur=function(){document.title='" + prefix + ":blurred';report('blur',null)};"
        "e.addEventListener('beforeinput',function(v){report('beforeinput',v)});"
        "e.oninput=function(v){report('input',v)};"
        "report('ready',null);"
        "})</script>"
    )
    return (prefix + ":ready", control, script)


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
    "/shadow-input.html": (
        "SHADOW:ready",
        "<div class='c' id='field'></div>",
        "<script>addEventListener('load',function(){"
        "var outer=document.getElementById('field').attachShadow({mode:'closed'});"
        "var host=document.createElement('div');outer.appendChild(host);"
        "var inner=host.attachShadow({mode:'closed'});"
        "var q=document.createElement('input');q.type='password';"
        "q.style.cssText='box-sizing:border-box;width:100%;height:70vh;font-size:32px';"
        "inner.appendChild(q);var pastes=0;"
        "q.onfocus=function(){document.title='SHADOW:focused'};"
        "q.onblur=function(){document.title='SHADOW:blurred'};"
        "q.onclick=function(){document.title='SHADOW:tapped'};"
        "q.onpaste=function(){pastes++};"
        "q.oninput=function(){document.title='SHADOW:input:'+q.value+':'+pastes};"
        "q.focus()})</script>",
    ),
    # A rich text editor, as comment boxes are: an editable element rather than a form field.
    # The title shows the editor's text and the type of the last input the page received.
    "/contenteditable.html": editor_page(
        "EDITOR",
        "<div class='c' id='editor' contenteditable></div>",
        "",
        "document.getElementById('editor')",
    ),
    # An editing host nested in a non-editable region, as nested widgets are.
    "/nested-editable.html": editor_page(
        "NESTED",
        "<div class='c' id='outer' contenteditable='false'>"
        "<div id='inner' contenteditable='true' style='box-sizing:border-box;height:100%;font-size:40px'>"
        "</div></div>",
        "var outer=document.getElementById('outer');",
        "document.getElementById('inner')",
        ",outerAttribute:outer.getAttribute('contenteditable'),"
        "outerEditable:outer.isContentEditable,innerEditable:e.isContentEditable",
    ),
    # A rich editor inside a closed shadow root, as component-based editors are.
    "/shadow-editor.html": editor_page(
        "SHADOWEDITOR",
        "<div class='c' id='host'></div>",
        "var host=document.getElementById('host');"
        "var root=host.attachShadow({mode:'closed'});"
        "var editorEl=document.createElement('div');editorEl.id='editor';"
        "editorEl.setAttribute('contenteditable','true');"
        "editorEl.style.cssText='box-sizing:border-box;height:100%;font-size:40px';"
        "root.appendChild(editorEl);",
        "editorEl",
        ",shadowRoot:e.getRootNode()!==document,rootMode:e.getRootNode().mode,"
        "hostId:(e.getRootNode().host&&e.getRootNode().host.id)||null,"
        "encapsulated:document.getElementById('editor')===null",
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

    # Logged as it arrives: some requests are never answered (/once.html), and a navigation
    # that reached the server is what a missing page needs ruled in or out.
    def parse_request(self):
        if not super().parse_request():
            return False
        log_request_line(self.command, self.path)
        # One handler serves one connection: /reused-close.html tells a reused connection apart.
        self.requests_on_connection = getattr(self, "requests_on_connection", 0) + 1
        return True

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
        if path == "/image-download.html":
            key = re.sub(r"[^a-zA-Z0-9]", "", query.get("k", ["image"])[0])[:32]
            image_path = "/download-image-redirect.png" if query.get("redirect") == ["1"] else "/download-image.png"
            image_url = image_path + "?" + urllib.parse.urlencode({"k": key, "fail": query.get("fail", ["0"])[0]})
            image = f"<img src='{html.escape(image_url, quote=True)}' style='display:block;width:100vw;height:100vh'>"
            if query.get("linked") == ["1"]:
                image = f"<a href='/solid/3b4f8a.html'>{image}</a>"
            return self.send(200, page("IMAGE-DOWNLOAD", image, "<style>html,body{margin:0;background:#5b2a86}</style>"),
                             headers=[("Set-Cookie", f"{COOKIE_NAME}=image-download-fixture; Path=/")])
        if path == "/download-image-redirect.png":
            return self.send(302, headers=[("Location", "/download-image.png?" + urllib.parse.urlencode(
                {"k": query.get("k", [""])[0], "fail": query.get("fail", ["0"])[0]}))])
        if path == "/download-image.png":
            servo = "Servo/" in self.headers.get("User-Agent", "")
            self.record({"service": "image-download", "key": query.get("k", [""])[0],
                         "has_cookie": bool(self.headers.get("Cookie")),
                         "has_referrer": bool(self.headers.get("Referer")), "servo_agent": servo})
            if query.get("fail") == ["1"] and not servo:
                return self.send(403, "Download refused")
            return self.send(200, DOWNLOAD_IMAGE, "image/png")
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
            # Distinct endpoints and a live title prove root-page jumps, not just button state. The
            # title also follows the viewport's height (innerHeight) as the bottom bar comes and goes.
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
                "addEventListener('scroll',update);addEventListener('resize',update);update();</script>"))
        if path == "/pull-blocked.html":
            return self.send(200, page("PULL-READY", "<p>Page-owned drag</p>",
                "<style>html,body{margin:0;height:100vh}</style>"
                "<script>addEventListener('touchmove',event=>{event.preventDefault();"
                "document.title='PULL-PREVENTED';},{passive:false});</script>"))
        if path == "/pull-slow.html":
            # Outlast the device test's 30 ms moves so Servo coalesces pending input.
            prevent = "event.preventDefault();" if query.get("prevent") == ["1"] else ""
            title = "PULL-SLOW-PREVENTED" if prevent else "PULL-SLOW-ALLOWED"
            return self.send(http.HTTPStatus.OK, page("PULL-SLOW-READY", "<p>Delayed touch handler</p>",
                "<style>html,body{margin:0;height:100vh;background:#2e6b4f}</style>"
                "<script>const TOUCH_DELAY_MS=100;addEventListener('touchmove',event=>{"
                "const until=performance.now()+TOUCH_DELAY_MS;while(performance.now()<until){}"
                f"{prevent}document.title='{title}';"
                "},{passive:false});</script>"))
        if path == "/pull-panel.html":
            # Bound overflow so the return drag reaches top even after a fling.
            return self.send(200, page("PANEL-TOP",
                "<div id='panel' style='height:100vh;overflow:auto'>"
                "<section style='height:100vh;background:#2e6b4f'>TOP</section>"
                "<section style='height:50vh;background:#8a5a2b'>PANEL</section></div>"
                "<script>const panel=document.getElementById('panel');"
                "panel.addEventListener('scroll',()=>{document.title="
                "panel.scrollTop<=1?'PANEL-TOP':'PANEL-MIDDLE';});</script>",
                "<style>html,body{margin:0;height:100vh;overflow:hidden}</style>"))
        if path == "/intersection.html":
            return self.send(200, INTERSECTION_PAGE)
        if path == "/animate.html":
            return self.send(200, ANIMATE_PAGE)
        if path == "/port-close.html":
            return self.send(200, PORT_CLOSE_PAGE)
        if path == "/apis.html":
            return self.send(200, APIS_PAGE)
        if path in {"/stop-loading.html", "/stop-loading.js", "/stop-loading-pending.html"}:
            key = query.get("k", [""])[0]
            if not STOP_KEY.fullmatch(key):
                return self.send(http.HTTPStatus.BAD_REQUEST, "invalid stop key")
            gate, counts = stop_load(key)
            if path == "/stop-loading.html":
                article = ARTICLE_BODY.replace("READER-BODY-ONE", "STOP-COMMITTED READER-BODY-ONE", 1)
                heartbeat = (
                    "<script>window.stopProbe={ran:false,tick:0};setInterval(function(){"
                    "document.title='STOP:'+document.readyState+':'+Number(stopProbe.ran)+':'+(++stopProbe.tick)"
                    f";}},{STOP_HEARTBEAT_MS});</script>"
                )
                return self.send(http.HTTPStatus.OK, page("STOP-READY",
                    article + f"<script src='/stop-loading.js?k={key}'></script>",
                    "<style>html,body{background:#2e6b4f}</style>" + heartbeat))
            if path == "/stop-loading.js":
                body = "stopProbe.ran=true;document.body.style.backgroundColor='#3b4f8a';"
                return self.send_held_stop_resource(gate, counts, body, "text/javascript; charset=utf-8")
            return self.send_held_stop_resource(gate, counts,
                page("STOP-CANCELLED-TARGET", "<p>Cancelled target</p>"), "text/html; charset=utf-8")
        if path == "/slow.html":
            time.sleep(SLOW_PAGE_SECONDS)
            return self.send(200, page("SLOW", "<p>SLOW</p>"))
        if path == "/screen.html":
            return self.send(200, SCREEN_PAGE)
        if path == "/media.html":
            return self.send(200, MEDIA_PAGE)
        if path == "/media/slow.bin":
            return self.send_slow_media()
        if path == "/video.html":
            return self.send(200, media_page("VIDEO", "video", f"/media/clip.mp4?k={media_key(query)}"))
        if path == "/audio.html":
            return self.send(200, media_page("AUDIO", "audio", "/media/tone.wav"))
        if path == "/canplay.html":
            return self.send(200, canplay_page(media_key(query)))
        if path == "/media-player.html":
            return self.send(200, player_page(media_key(query)))
        if path == "/media-source.html":
            return self.send(200, typed_source_page(media_key(query)))
        if path == "/hls.html":
            return self.send(200, hls_page(media_key(query)))
        if path == "/hls-noext.html":
            # A claimed playlist on an extensionless address: the player must still pick HLS.
            key = media_key(query)
            return self.send(200, hls_page(key, f"/media/hls-manifest?k={key}"))
        if path == "/media-redirect.html":
            to = query.get("to", ["clip"])[0]
            return self.send(200, media_page("REDIRECT", "video", f"/media/redirect?to={urllib.parse.quote(to)}&k={media_key(query)}"))
        if path == "/media-local.html":
            return self.send(200, page(
                "LOCAL",
                "<video src='data:video/mp4;base64,AAAAAA==' onerror='failed()'></video>"
                "<video src='file:///sdcard/clip.mp4' onerror='failed()'></video>",
                "<script>var errors = 0; function failed() { errors++; document.title = 'LOCAL:errors:' + errors; }</script>",
            ))
        if path == "/media-data-link.html":
            # Absolute: a data: page has no base address of its own.
            inner = page("DATA-MEDIA", f"<video src='http://localhost:{self.server.server_port}/media/clip.mp4?k={media_key(query)}'></video>")
            target = "data:text/html," + urllib.parse.quote(inner)
            return self.send(200, page("DATA-LINK", f"<a href='{html.escape(target, quote=True)}' style='display:block;height:100vh;"
                                                    "font-size:48px;background:#8a5a2b;color:#fff'>data page</a>",
                                       "<style>html,body{margin:0}</style>"))
        if path == "/media/clip.mp4":
            return self.send_media("clip", CLIP_PATH.read_bytes(), "video/mp4")
        if path == "/media/tone.wav":
            return self.send_media("tone", TONE_WAV, "audio/wav")
        if path == "/media/hls.m3u8" or path == "/media/hls-manifest":
            return self.send_media("hls", hls_master_playlist(media_key(query)).encode(),
                                   "application/vnd.apple.mpegurl")
        if path in ("/media/hls-live.m3u8", "/media/hls-refused.m3u8"):
            key = media_key(query)
            segment = f"/media/hls-video-0.m4s?k={key}" if path == "/media/hls-live.m3u8" else f"/media/redirect?to=file&k={key}"
            playlist = hls_media_playlist([(3.0, segment)], f"/media/hls-init.mp4?k={key}")
            if path == "/media/hls-live.m3u8":
                playlist = playlist.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "").replace("#EXT-X-ENDLIST\n", "")
            return self.send_media("hls", playlist.encode(), "application/vnd.apple.mpegurl")
        if path == "/media/hls-video.m3u8":
            key = media_key(query)
            entries = [(3.0, f"/media/hls-video-{i}.m4s?k={key}") for i in range(len(HLS_VIDEO_SEGMENTS))]
            return self.send_media("hls", hls_media_playlist(entries, f"/media/hls-init.mp4?k={key}").encode(),
                                   "application/vnd.apple.mpegurl")
        if path == "/media/hls-audio.m3u8":
            key = media_key(query)
            entries = [(3.0, f"/media/hls-audio-{i}.aac?k={key}") for i in range(len(HLS_AUDIO_SEGMENTS))]
            return self.send_media("hls", hls_media_playlist(entries).encode(),
                                   "application/vnd.apple.mpegurl")
        if path == "/media/hls-init.mp4":
            return self.send_media("hls", HLS_INIT, "video/mp4")
        for name, data, mime in (
            (f"/media/hls-video-{i}.m4s", segment, "video/mp4")
            for i, segment in enumerate(HLS_VIDEO_SEGMENTS)
        ):
            if path == name:
                return self.send_media("hls", data, mime)
        for name, data in (
            (f"/media/hls-audio-{i}.aac", segment)
            for i, segment in enumerate(HLS_AUDIO_SEGMENTS)
        ):
            if path == name:
                return self.send_media("hls", data, "audio/aac")
        if path == "/media/redirect":
            to = query.get("to", ["clip"])[0]
            with lock:
                state["media"]["redirects"] += 1
            location = FILE_TARGET if to == "file" else f"/media/clip.mp4?k={media_key(query)}"
            return self.send(302, b"", headers=[("Location", location)])
        if path == "/zoom.html":
            return self.send(200, ZOOM_PAGE)
        if path == "/fit-width.html":
            return self.send(200, FIT_WIDTH_PAGE)
        if path == "/agent.html":
            agent = self.headers.get("User-Agent", "")
            if names_firefox_without_gecko(agent):
                return self.send(http.HTTPStatus.FORBIDDEN, "Access denied")
            return self.send(http.HTTPStatus.OK, agent_page(agent))
        if path == "/fixed-scroll.html":
            pending_image = "<img hidden src='/scroll-pending-image'>" if query.get("loading") == ["1"] else ""
            return self.send(http.HTTPStatus.OK, fixed_scroll_page(pending_image))
        if path == "/wide.html":
            return self.send(http.HTTPStatus.OK, wide_page(query.get("initial-scale") == ["1"]))
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
        if path == "/contrast.html":
            mode = query.get("mode", [""])[0]
            colors = {
                "white": "body{background:white;min-height:100vh}",
                "dark": "html{color-scheme:dark;background:Canvas}",
                "meta": "html{background:Canvas}",
                "authored": "html{color:white;background:#3b4f8a}",
            }.get(mode, "")
            marker = {"dark": "#3b4f8a", "meta": "#b03a2e", "authored": "#8a5a2b"}.get(mode, "#2e6b4f")
            metadata = "<meta name='color-scheme' content='dark'>" if mode == "meta" else ""
            # currentColor makes inherited text measurable without font antialiasing.
            return self.send(http.HTTPStatus.OK, page("CONTRAST:waiting",
                "<p>Default text must contrast with the canvas.</p><div id='ink'></div><div id='paint'></div>"
                "<script>const scheme=matchMedia('(prefers-color-scheme:dark)');"
                "function report(){document.title='CONTRAST:'+(scheme.matches?'dark':'light')}"
                "scheme.addEventListener('change',report);report();"
                "const meta=document.querySelector('meta[name=color-scheme]');let step=0;"
                "if(meta)document.getElementById('paint').onclick=function(){step++;"
                "if(step==1)meta.content='light';else if(step==2)meta.content='dark';else meta.remove();"
                "this.style.background=['','#2e6b4f','#3b4f8a','#8a5a2b'][Math.min(step,3)];};</script>",
                metadata + "<style>body{margin:0}#ink,#paint{position:fixed;top:0;bottom:0}"
                "#ink{left:0;width:40vw;background:currentColor}"
                "#paint{left:40vw;width:20vw;background:" + marker + "}" + colors + "</style>"))
        if path == "/adaptive.html":
            mode = query.get("mode", [""])[0]
            if mode not in ADAPTIVE_MODES:
                return self.send(400, page("bad mode", ""))
            head, colors = ADAPTIVE_MODES[mode]
            # A border in currentColor is painted like text (a foreground), unlike a background.
            return self.send(http.HTTPStatus.OK, page("ADAPTIVE:" + mode,
                "<div id='ink'></div><div id='paint'></div>",
                head + "<style>body{margin:0}#ink,#paint{position:fixed;top:0;bottom:0}"
                "#ink{left:0;width:0;border-left:40vw solid currentColor}"
                "#paint{left:40vw;width:20vw;background:#2e6b4f}" + colors + "</style>"))
        if path == "/has-selector.html":
            return self.send(http.HTTPStatus.OK, HAS_SELECTOR_PAGE)
        if path == "/document.pdf":
            return self.send(200, PDF_DOCUMENT, "application/pdf")
        if path == "/document.bin":
            # A PDF Servo does not recognise: it shows its stand-in page for it.
            return self.send(200, PDF_DOCUMENT, "application/octet-stream",
                             headers=[("X-Content-Type-Options", "nosniff")])
        if path.startswith("/pdf/"):
            return self.send_pdf(path[len("/pdf/"):], media_key(query))
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
        if path == "/reused-close.html":
            key = "".join(c for c in query.get("k", [""])[0] if c.isascii() and c.isalnum())[:40]
            if not key:
                return self.send(400, page("REUSED-CLOSE-NEEDS-K", "<p>missing or empty k</p>"))
            reused = self.requests_on_connection > 1
            with lock:
                counts = state["reused_close"].setdefault(key, {"answered": 0, "dropped": 0})
                counts["dropped" if reused else "answered"] += 1
            if not reused:
                return self.send(200, page("FRESH", "<p>FRESH</p>"))
            # Read and dropped: the client cannot tell whether the request was processed.
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
        if path == f"{REPORTS_PREFIX}/whoami":
            if not self.reports_authorized():
                return self.send_json(401, {"error": "unauthorized"})
            self.record({"service": "reports", "method": "GET", "path": "whoami"})
            return self.send_json(200, {"service": REPORTS_SERVICE_NAME, "api": REPORTS_API_VERSION, "role": "reporter"})
        if path == "/_admin/state":
            with lock:
                return self.send_json(200, state)
        if path == "/_admin/report-screenshot":
            with lock:
                png = report_screenshots.get(query.get("id", [""])[0])
            if png is None:
                return self.send_json(404, {"error": "no_screenshot"})
            return self.send(200, png, "image/png")
        return self.send(404, page("not found", ""))

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        path = url.path
        # Before body(), which stops at MAX_BODY_BYTES: a report with a screenshot is larger.
        if path == f"{REPORTS_PREFIX}/reports":
            return self.reports_create()
        if path == "/_admin/editor-report":
            return self.editor_report(self.body(), query)
        raw = self.body()

        if path == "/_admin/reset":
            scroll_load_finished.clear()
            with lock:
                for gate, _ in stop_load_gates.values():
                    gate.set()
                stop_load_gates.clear()
                state["stop_loads"].clear()
                state["reused_close"].clear()
            with lock:
                state["bookmarks"].clear()
                state["lists"].clear()
                state["lists"].update(fresh_lists())
                state["hister"].clear()
                state["reports"].clear()
                report_screenshots.clear()
                state["log"].clear()
                once_served.clear()
                state["media"] = fresh_media()
                state["pdf"].clear()
                state["editor"].clear()
                for value in faults.values():
                    value[0], value[1] = "none", 0
            return self.send_json(200, {"ok": True})
        if path == "/_admin/finish-stop-load":
            key = query.get("k", [""])[0]
            with lock:
                held = stop_load_gates.get(key)
            if held is None:
                return self.send_json(http.HTTPStatus.NOT_FOUND, {"error": "unknown stop key"})
            held[0].set()
            return self.send_json(http.HTTPStatus.OK, {"ok": True})
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

    def editor_report(self, raw, query):
        """Store one page-reported editor event under its key; the state keeps only recent ones."""
        key = "".join(c for c in query.get("k", [""])[0] if c.isascii() and c.isalnum())[:40]
        if not key:
            return self.send_json(http.HTTPStatus.BAD_REQUEST, {"error": "editor report needs k"})
        try:
            event = json.loads(raw)
        except ValueError:
            return self.send_json(http.HTTPStatus.BAD_REQUEST, {"error": "bad editor report"})
        if not isinstance(event, dict):
            return self.send_json(http.HTTPStatus.BAD_REQUEST, {"error": "bad editor report"})
        with lock:
            events = state["editor"].setdefault(key, [])
            events.append(event)
            if len(events) > EDITOR_REPORT_LIMIT:
                del events[:-EDITOR_REPORT_LIMIT]
        return self.send_json(http.HTTPStatus.OK, {"ok": True})

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

    def send_pdf(self, name, key):
        """The /pdf/ routes; the PDFs and the large one's bytes are counted per `key`."""
        fixed = {
            "untitled.pdf": PDF_UNTITLED,
            "pages.pdf": PDF_MANY_PAGES,
            "page-link.pdf": PDF_PAGE_LINK,
            "corrupt.pdf": PDF_CORRUPT,
            "protected.pdf": PDF_PROTECTED,
        }
        if name in ("plain.pdf", "large.pdf"):
            with lock:
                counted = state["pdf"].setdefault(key, {"requests": 0, "bytesSent": 0, "ended": False})
                counted["requests"] += 1
        if name == "plain.pdf":
            return self.send(200, PDF_PLAIN, "application/pdf")
        if name in fixed:
            return self.send(200, fixed[name], "application/pdf")
        if name == "web-link.pdf":
            return self.send(200, pdf_web_link(f"http://{self.headers.get('Host', 'localhost')}/solid/2e6b4f.html"),
                             "application/pdf")
        if name == "attachment.pdf":
            return self.send(200, PDF_ATTACHMENT, "application/pdf",
                             headers=[("Content-Disposition", 'attachment; filename="attachment.pdf"')])
        if name == "cookie.pdf":
            if f"{COOKIE_NAME}=" not in self.headers.get("Cookie", ""):
                return self.send(403, page("PDF-COOKIE-REFUSED", "<p>no cookie</p>"))
            return self.send(200, PDF_COOKIE, "application/pdf")
        if name == "large.pdf":
            return self.send_large_pdf(key)
        if name == "link.html":
            target = html.escape(f"/pdf/plain.pdf?k={key}", quote=True)
            return self.send(200, page("PDF-LINK", f"<a class='c' href='{target}'>PDF</a>",
                                       PDF_PAGE_STYLE % PDF_LINK_PAGE))
        if name == "frame.html":
            source = html.escape(f"/pdf/plain.pdf?k={key}", quote=True)
            return self.send(200, page(
                "PDF-FRAME", f"<iframe src='{source}' onload=\"document.title='PDF-FRAME:loaded'\"></iframe>",
                "<style>html,body{margin:0;height:100%}iframe{display:block;border:0;width:100vw;height:100vh}</style>"))
        return self.send(404, page("not found", ""))

    def send_large_pdf(self, key):
        """PDF_TOO_LARGE_BYTES with a Content-Length, paced, sent until the client stops reading."""
        self.send_response(200)
        self.send_header("Content-Type", "application/pdf")
        self.send_header("Content-Length", str(PDF_TOO_LARGE_BYTES))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        chunk = b"%PDF-1.7\n%" + b" " * (PDF_STREAM_CHUNK - 10)
        sent = 0
        try:
            while sent < PDF_TOO_LARGE_BYTES:
                part = chunk[:PDF_TOO_LARGE_BYTES - sent]
                self.wfile.write(part)
                self.wfile.flush()
                sent += len(part)
                with lock:
                    state["pdf"][key]["bytesSent"] = sent
                time.sleep(PDF_STREAM_PAUSE_S)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        finally:
            with lock:
                state["pdf"][key]["ended"] = True

    def reports_authorized(self):
        return self.headers.get("Authorization") == f"Bearer {REPORTER_TOKEN}"

    def reports_create(self):
        """POST /reports/v1/reports, as the report service answers it (services/site-reports/README.md)."""
        declared = self.headers.get("Content-Length", "")
        if "Transfer-Encoding" in self.headers or not declared.isdigit():
            self.close_connection = True
            return self.send_json(411, {"error": "length_required"})
        length = int(declared)
        if length > MAX_REPORT_BODY_BYTES:
            # Unread: the rest of the connection is body bytes, not a next request.
            self.close_connection = True
            return self.send_json(413, {"error": "too_large"})
        raw = self.rfile.read(length)
        if len(raw) != length:
            self.close_connection = True
            return self.send_json(400, {"error": "bad_request"})

        fault = take_fault("reports")
        if fault == "unauthorized" or not self.reports_authorized():
            return self.send_json(401, {"error": "unauthorized"})
        try:
            report = report_validation.parse_report(raw, max_screenshot_bytes=MAX_REPORT_SCREENSHOT_BYTES)
        except report_validation.ValidationError as error:
            self.record({"service": "reports", "method": "POST", "id": None, "error": error.code})
            return self.send_json(error.status, {"error": error.code, "field": error.field})
        self.record({"service": "reports", "method": "POST", "id": report.id})
        if fault == "too_many_requests":
            return self.send_json(429, {"error": "rate_limited"}, headers=[("Retry-After", "1")])
        if fault == "server_error":
            return self.send_json(503, {"error": "busy"})

        with lock:
            stored = state["reports"].get(report.id)
            created = stored is None
            conflict = not created and report_validation.content_digest(stored) != report.content_sha256
            if created:
                state["reports"][report.id] = report.metadata
                if report.screenshot is not None:
                    report_screenshots[report.id] = report.screenshot
        if conflict:
            return self.send_json(409, {"error": "id_conflict"})
        if fault == "store_then_hang":
            time.sleep(HANG_SECONDS)
        received = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        return self.send_json(201 if created else 200, {"id": report.id, "status": "open", "received_at": received})

    def send_media(self, name, data, content_type):
        """`data` with byte ranges (one range per request: 206, or 416 past the end), recording the request."""
        total = len(data)
        status, start, end = 200, 0, total - 1
        requested = RANGE.match(self.headers.get("Range", ""))
        if requested:
            first, last = requested.groups()
            if first:
                start = int(first)
                end = min(int(last), total - 1) if last else total - 1
            elif last:
                start = max(total - int(last), 0)
            if start >= total or start > end or (not first and not last):
                self.record_media(name, 0)
                return self.send(416, b"", "text/plain", headers=[("Content-Range", f"bytes */{total}"), ("Accept-Ranges", "bytes")])
            status = 206
        body = data[start:end + 1]
        self.record_media(name, len(body))
        headers = [("Accept-Ranges", "bytes")]
        if status == 206:
            headers.append(("Content-Range", f"bytes {start}-{end}/{total}"))
        return self.send(status, body, content_type, headers=headers)

    def record_media(self, name, sent):
        with lock:
            served = state["media"][name]
            served["requests"] += 1
            served["bytes"] += sent
            served["seen"].append({
                "path": self.path,
                "cookie": self.headers.get("Cookie", ""),
                "referer": self.headers.get("Referer", ""),
                "servo_agent": "Servo/" in self.headers.get("User-Agent", ""),
            })
            del served["seen"][:-MEDIA_SEEN_LIMIT]

    def send_held_stop_resource(self, gate, counts, body, content_type):
        with lock:
            counts["requested"] += 1
        if not gate.wait(STOP_LOAD_TIMEOUT_SECONDS):
            return self.send(http.HTTPStatus.GATEWAY_TIMEOUT, "stop fixture gate timed out")
        try:
            self.send(http.HTTPStatus.OK, body, content_type)
        except ConnectionError:
            self.close_connection = True
        finally:
            with lock:
                counts["finished"] += 1

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


def check_clip():
    """Exits when the committed clip is missing or not the one its README describes."""
    try:
        digest = hashlib.sha256(CLIP_PATH.read_bytes()).hexdigest()
    except OSError as error:
        sys.exit(f"!! {CLIP_PATH} cannot be read: {error}")
    if digest != CLIP_SHA256:
        sys.exit(f"!! {CLIP_PATH} has SHA-256 {digest}, expected {CLIP_SHA256} (see testing/fixtures/media/README.md)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--write-pdfs", metavar="DIR", type=pathlib.Path,
                        help="write the generated PDFs to DIR (for the viewer's host tests) and exit")
    args = parser.parse_args()
    if args.write_pdfs:
        args.write_pdfs.mkdir(parents=True, exist_ok=True)
        for name, data in pdf_fixtures(f"http://localhost:{args.port}").items():
            (args.write_pdfs / name).write_bytes(data)
        return
    check_clip()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"serving Combray fixtures on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
