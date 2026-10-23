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
  /private.html              page for exclusion checks
  /select.html               page-filling <select>; title SELECT:<value> after a change
  /dialogs.html              page-filling button: confirm, prompt, alert; title DIALOGS:<confirm>:<prompt>
  /upload.html               page-filling <input type=file accept=.txt>; title FILE:<name>:<text>
  /auth.html                 HTTP Basic sign-in (realm "fixture"): user "ada", password "lovelace" gives
                             title AUTH:ada; otherwise 401 with title AUTH-REQUIRED
  (the three control pages have a #1f6f78 margin on the left for paint checks)
Fake Karakeep (root http://10.0.2.2:8766/keep on the emulator: a host of its own, because a
service's own host is never captured for Hister)
  POST /keep/api/v1/bookmarks, GET /keep/api/v1/bookmarks (newest first), GET .../bookmarks/search?q=,
  GET /keep/api/v1/lists (manual list "reading" plus a smart list), PUT .../lists/<list>/bookmarks/<id>;
  key "fixture-key"
Fake Hister (root http://10.0.2.2:8766/hister on the emulator)
  POST /hister/api/add (needs Origin: hister://), GET /hister/search?q=&format=json; token "fixture-token"
Admin (for the checks)
  GET  /_admin/state                    JSON: bookmarks, lists, hister documents, request log
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
COOKIE_NAME = "combray"
COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
MAX_BODY_BYTES = 2 * 1024 * 1024
FAULTS = {"none", "store_then_hang", "too_many_requests", "server_error", "unauthorized"}

lock = threading.Lock()
def fresh_lists():
    """List id -> bookmark ids in it. Lists that are not here answer 404."""
    return {"reading": []}


state = {"bookmarks": {}, "lists": fresh_lists(), "hister": {}, "log": []}
faults = {"karakeep": ["none", 0], "hister": ["none", 0]}


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
    "<ul><li>READER-ITEM-ONE</li><li>READER-ITEM-TWO</li></ul></article>"
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

# Reports capability probes used during site-compatibility triage.
# The title carries a compact verdict: CAPS:ok or CAPS:fail:<comma-separated names>.
# Worker script at /challenge-caps-worker.js; wasm (served as text/html like Vercel does) at
# /challenge-caps.wasm.
CHALLENGE_CAPS_WORKER = """
self.onmessage = function (e) {
  var port = e.data.port, out = { worker: true };
  try {
    out.fetch = typeof fetch === 'function';
    out.cryptoSubtle = !!(crypto && crypto.subtle && crypto.subtle.digest);
    out.instantiateStreaming = typeof WebAssembly.instantiateStreaming === 'function';
  } catch (err) { out.workerErr = String(err); }
  fetch('/challenge-caps.wasm').then(function (r) {
    return WebAssembly.instantiateStreaming(r);
  }).then(function () { out.wasm = true; }, function (err) {
    out.wasm = String(err);
    return fetch('/challenge-caps.wasm').then(function (r) { return r.arrayBuffer(); })
      .then(function (b) { return WebAssembly.instantiate(b, {}); })
      .then(function () { out.wasmBuffered = true; }, function (e2) { out.wasmBuffered = String(e2); });
  }).finally(function () { port.postMessage(out); });
};
"""

CHALLENGE_CAPS_PAGE = page(
    "CAPS:running",
    "<pre id='out'>probing</pre>"
    "<script>"
    "var out={},names=[];"
    "function rec(n,v){out[n]=v;if(v!==true)names.push(n);}"
    "function paint(){document.getElementById('out').textContent=JSON.stringify(out,null,1);"
    "document.title='CAPS:'+(names.length?'fail:'+names.join(','):'ok');}"
    "try{"
    "rec('ua',navigator.userAgent);names=names.filter(function(n){return n!=='ua'});"
    "var c=document.createElement('canvas');"
    "try{var s=c.getContext('webgl')||c.getContext('experimental-webgl');"
    "if(!s){rec('webgl','null context')}else{"
    "var W=s.getExtension('WEBGL_debug_renderer_info');"
    "rec('webglDebugRendererInfo',!!W);"
    "rec('webgl',W?String(s.getParameter(W.UNMASKED_RENDERER_WEBGL)):'no debug_renderer_info');"
    "names=names.filter(function(n){return n!=='webgl'});}}catch(e){rec('webgl',String(e))}"
    "try{var d=document.createElement('canvas');var x=d.getContext('2d');"
    "x.fillText('cap',4,12);rec('canvas2d',d.toDataURL().length>30);}catch(e){rec('canvas2d',String(e))}"
    "rec('subtle',!!(crypto&&crypto.subtle&&crypto.subtle.digest));"
    "rec('getRandomValues',typeof crypto.getRandomValues==='function');"
    "rec('wasmObj',typeof WebAssembly==='object'||typeof WebAssembly==='function');"
    "rec('workerCtor',typeof Worker==='function');"
    "rec('msgChannel',typeof MessageChannel==='function');"
    "rec('hasFocus',typeof document.hasFocus==='function');"
    "rec('visibility',document.visibilityState);names=names.filter(function(n){return n!=='visibility'});"
    "rec('hidden',document.hidden);names=names.filter(function(n){return n!=='hidden'});"
    "rec('broadcastChannel','BroadcastChannel' in window);"
    "rec('hwConcurrency',typeof navigator.hardwareConcurrency==='number');"
    "rec('deviceMemory',navigator.deviceMemory===undefined?'missing':navigator.deviceMemory);"
    "names=names.filter(function(n){return n!=='deviceMemory'});"
    "rec('maxTouchPoints',navigator.maxTouchPoints===undefined?'missing':navigator.maxTouchPoints);"
    "names=names.filter(function(n){return n!=='maxTouchPoints'});"
    "rec('plugins',!!navigator.plugins?navigator.plugins.length:'missing');"
    "names=names.filter(function(n){return n!=='plugins'});"
    "rec('webdriver',navigator.webdriver);names=names.filter(function(n){return n!=='webdriver'});"
    "rec('chrome',!!window.chrome);names=names.filter(function(n){return n!=='chrome'});"
    "try{rec('tz',Intl.DateTimeFormat().resolvedOptions().timeZone||'empty');"
    "names=names.filter(function(n){return n!=='tz'});}catch(e){rec('tz',String(e))}"
    "try{rec('eval',window.eval('1+1')===2);}catch(e){rec('eval',String(e))}"
    "}catch(e){out.fatal=String(e);names.push('fatal');}"
    "paint();"
    "try{var ch=new MessageChannel();var w=new Worker('/challenge-caps-worker.js');"
    "var done=false;ch.port2.onmessage=function(ev){done=true;"
    "var d=ev.data;out.workerFetch=d.fetch;out.workerSubtle=d.cryptoSubtle;"
    "out.workerStreaming=d.instantiateStreaming;out.workerWasm=d.wasm;"
    "if(d.wasmBuffered!==undefined)out.workerWasmBuffered=d.wasmBuffered;"
    "if(d.workerErr)out.workerErr=d.workerErr;"
    "if(d.fetch!==true)names.push('workerFetch');"
    "if(d.cryptoSubtle!==true)names.push('workerSubtle');"
    "if(d.instantiateStreaming!==true)names.push('workerStreaming');"
    "if(d.wasm!==true&&d.wasmBuffered!==true)names.push('workerWasm');"
    "names.push.apply(names,[]);paint();};"
    "w.postMessage({port:ch.port1},[ch.port1]);"
    "w.onerror=function(e){out.workerError=String(e.message||e);names.push('workerError');paint();};"
    "setTimeout(function(){if(!done){out.workerTimeout=true;names.push('workerTimeout');paint();}},8000);"
    "}catch(e){out.workerThrow=String(e);names.push('workerThrow');paint();}"
    "</script>",
)

# Broad API-surface probe for site-compatibility triage. Each entry tries to exercise the API
# the way a modern app bundle does at boot (not just existence): missing globals, missing methods
# on present interfaces, and methods that throw are all reported. Title: APIS:ok or
# APIS:fail:<comma-separated names>; the JSON detail is in the page body.
API_PROBE_PAGE = page(
    "APIS:running",
    "<pre id='out'>probing</pre>"
    "<script>"
    "var results={},fails=[];"
    "function t(name,fn){try{var r=fn();results[name]=r===true?'ok':String(r);"
    "if(r!==true)fails.push(name);}catch(e){results[name]='THROW:'+e;fails.push(name);}}"
    "function paint(){document.getElementById('out').textContent=JSON.stringify(results,null,1);"
    "document.title='APIS:'+(fails.length?'fail':'ok');"
    "try{fetch('/api-probe-report',{method:'POST',body:JSON.stringify(results)}).catch(function(){});}catch(e){}}"
    "t('structuredClone',function(){return typeof structuredClone==='function'?structuredClone({a:1}).a===1:'missing'});"
    "t('sendBeacon',function(){return typeof navigator.sendBeacon==='function'});"
    "t('scrollRestoration',function(){return 'scrollRestoration' in history});"
    "t('randomUUID',function(){return typeof crypto.randomUUID==='function'});"
    "t('cryptoSubtle',function(){return !!(crypto.subtle&&crypto.subtle.digest)});"
    "t('documentFonts',function(){return typeof document.fonts!=='undefined'});"
    "t('fontFaceCtor',function(){return typeof FontFace==='function'});"
    "t('fontFaceSetLoad',function(){return typeof document.fonts!=='undefined'&&typeof document.fonts.load==='function'});"
    "t('matchMediaAE',function(){var m=matchMedia('(min-width:1px)');return typeof m.addEventListener==='function'});"
    "t('matchMediaAL',function(){var m=matchMedia('(min-width:1px)');return typeof m.addListener==='function'});"
    "t('resizeObserver',function(){return typeof ResizeObserver==='function'});"
    "t('visualViewport',function(){return typeof visualViewport!=='undefined'});"
    "t('navigationAPI',function(){return typeof navigation!=='undefined'});"
    "t('schedulerPostTask',function(){return typeof scheduler!=='undefined'&&typeof scheduler.postTask==='function'});"
    "t('cookieStore',function(){return typeof cookieStore!=='undefined'});"
    "t('trustedTypes',function(){return typeof trustedTypes!=='undefined'});"
    "t('customElements',function(){return typeof customElements!=='undefined'});"
    "t('attachShadow',function(){return typeof Element.prototype.attachShadow==='function'});"
    "t('elementAnimate',function(){return typeof Element.prototype.animate==='function'});"
    "t('indexedDBOpen',function(){return typeof indexedDB!=='undefined'&&typeof indexedDB.open==='function'});"
    "t('localStorage',function(){localStorage.setItem('k','1');var v=localStorage.getItem('k')==='1';localStorage.removeItem('k');return v});"
    "t('sessionStorage',function(){sessionStorage.setItem('k','1');var v=sessionStorage.getItem('k')==='1';sessionStorage.removeItem('k');return v});"
    "t('caches',function(){return typeof caches!=='undefined'});"
    "t('serviceWorker',function(){return 'serviceWorker' in navigator});"
    "t('sharedWorker',function(){return typeof SharedWorker==='function'});"
    "t('createImageBitmap',function(){return typeof createImageBitmap==='function'});"
    "t('offscreenCanvas',function(){return typeof OffscreenCanvas==='function'});"
    "t('webgl2',function(){return typeof WebGL2RenderingContext==='function'});"
    "t('readableStream',function(){return typeof ReadableStream==='function'});"
    "t('textDecoderStream',function(){return typeof TextDecoderStream==='function'});"
    "t('compressionStream',function(){return typeof CompressionStream==='function'});"
    "t('abortSignalTimeout',function(){return typeof AbortSignal!=='undefined'&&typeof AbortSignal.timeout==='function'});"
    "t('abortSignalAny',function(){return typeof AbortSignal!=='undefined'&&typeof AbortSignal.any==='function'});"
    "t('urlCanParse',function(){return typeof URL.canParse==='function'});"
    "t('intlSegmenter',function(){return typeof Intl!=='undefined'&&typeof Intl.Segmenter==='function'});"
    "t('intlListFormat',function(){return typeof Intl!=='undefined'&&typeof Intl.ListFormat==='function'});"
    "t('intlRelativeTime',function(){return typeof Intl!=='undefined'&&typeof Intl.RelativeTimeFormat==='function'});"
    "t('performanceObserver',function(){return typeof PerformanceObserver==='function'});"
    "t('performanceMark',function(){return typeof performance!=='undefined'&&typeof performance.mark==='function'});"
    "t('reportError',function(){return typeof reportError==='function'});"
    "t('requestIdle',function(){return typeof requestIdleCallback==='function'});"
    "t('notification',function(){return typeof Notification==='function'});"
    "t('mediaSource',function(){return typeof MediaSource==='function'});"
    "t('mediaRecorder',function(){return typeof MediaRecorder==='function'});"
    "t('rtcPeer',function(){return typeof RTCPeerConnection==='function'});"
    "t('audioContext',function(){return typeof AudioContext==='function'||typeof webkitAudioContext==='function'});"
    "t('speechSynth',function(){return typeof speechSynthesis!=='undefined'});"
    "t('wakeLock',function(){return typeof navigator.wakeLock!=='undefined'});"
    "t('userAgentData',function(){return typeof navigator.userAgentData!=='undefined'});"
    "t('connection',function(){return typeof navigator.connection!=='undefined'});"
    "t('clipboard',function(){return typeof navigator.clipboard!=='undefined'});"
    "t('share',function(){return typeof navigator.share!=='undefined'});"
    "t('vibrate',function(){return typeof navigator.vibrate==='function'});"
    "t('getGamepads',function(){return typeof navigator.getGamepads==='function'});"
    "t('permissions',function(){return typeof navigator.permissions!=='undefined'&&typeof navigator.permissions.query==='function'});"
    "t('credentials',function(){return typeof navigator.credentials!=='undefined'});"
    "t('locks',function(){return typeof navigator.locks!=='undefined'});"
    "t('storage',function(){return typeof navigator.storage!=='undefined'});"
    "t('mediaDevices',function(){return typeof navigator.mediaDevices!=='undefined'});"
    "t('mediaSession',function(){return typeof navigator.mediaSession!=='undefined'});"
    "t('xr',function(){return typeof navigator.xr!=='undefined'});"
    "t('gpu',function(){return typeof navigator.gpu!=='undefined'});"
    "t('presentation',function(){return typeof navigator.presentation!=='undefined'});"
    "t('usbSerialBt',function(){return typeof navigator.usb!=='undefined'||typeof navigator.serial!=='undefined'||typeof navigator.bluetooth!=='undefined'});"
    "t('documentEvaluate',function(){return typeof document.evaluate==='function'});"
    "t('caretRange',function(){return typeof document.caretRangeFromPoint==='function'||typeof document.caretPositionFromPoint==='function'});"
    "t('execCommand',function(){return typeof document.execCommand==='function'});"
    "t('getSelection',function(){return typeof window.getSelection==='function'});"
    "t('scrollIntoViewOpts',function(){var d=document.createElement('div');document.body.appendChild(d);d.scrollIntoView({block:'center',behavior:'instant'});return true});"
    "t('scrollToOpts',function(){window.scrollTo({top:0,left:0,behavior:'instant'});return true});"
    "t('cssSupports',function(){return typeof CSS!=='undefined'&&typeof CSS.supports==='function'&&CSS.supports('display','block')});"
    "t('cssEscape',function(){return typeof CSS!=='undefined'&&typeof CSS.escape==='function'});"
    "t('cssHighlights',function(){return typeof CSS!=='undefined'&&typeof CSS.highlights!=='undefined'});"
    "t('domParserHtml',function(){var d=new DOMParser().parseFromString('<p>x</p>','text/html');return d.querySelector('p').textContent==='x'});"
    "t('domParserSvg',function(){var d=new DOMParser().parseFromString('<svg/>','image/svg+xml');return !!d.documentElement});"
    "t('xmlSerializer',function(){return typeof XMLSerializer==='function'});"
    "t('treeWalker',function(){return typeof document.createTreeWalker==='function'});"
    "t('rangeRect',function(){var r=document.createRange();r.selectNode(document.body);return typeof r.getBoundingClientRect==='function'});"
    "t('elemFromPoint',function(){return typeof document.elementFromPoint==='function'});"
    "t('getComputedStylePs',function(){return typeof getComputedStyle==='function'&&!!getComputedStyle(document.body,'::after')});"
    "t('fileReader',function(){return typeof FileReader==='function'});"
    "t('formData',function(){var f=new FormData();f.append('a','1');return f.get('a')==='1'});"
    "t('blobText',function(){return typeof Blob.prototype.text==='function'});"
    "t('arrayBufferAsync',function(){return typeof Blob.prototype.arrayBuffer==='function'});"
    "t('imageDecode',function(){return typeof Image!=='undefined'&&typeof new Image().decode==='function'});"
    "t('videoCanPlay',function(){var v=document.createElement('video');return typeof v.canPlayType==='function'});"
    "t('audioTag',function(){return typeof Audio==='function'});"
    "t('fullscreen',function(){return typeof document.documentElement.requestFullscreen==='function'});"
    "t('pointerEvents',function(){return typeof PointerEvent==='function'});"
    "t('touchEvents',function(){return typeof TouchEvent==='function'});"
    "t('inputEvent',function(){return typeof InputEvent==='function'});"
    "t('toggleEvent',function(){return typeof ToggleEvent==='function'});"
    "t('submitEvent',function(){return typeof SubmitEvent==='function'});"
    "t('popover',function(){return typeof Element.prototype.showPopover==='function'});"
    "t('dialog',function(){return typeof HTMLDialogElement==='function'});"
    "t('lazyLoadableScript',function(){return typeof HTMLScriptElement!=='undefined'&&typeof HTMLScriptElement.supports==='function'});"
    "t('importMap',function(){return typeof HTMLScriptElement!=='undefined'&&typeof HTMLScriptElement.supports==='function'&&HTMLScriptElement.supports('importmap')});"
    "t('errorCapture',function(){return typeof Error.captureStackTrace==='function'||true});"
    "t('errorCause',function(){return new Error('x',{cause:'y'}).cause==='y'});"
    "t('objectHasOwn',function(){return typeof Object.hasOwn==='function'});"
    "t('arrayAt',function(){return typeof Array.prototype.at==='function'});"
    "t('arrayFindLast',function(){return typeof Array.prototype.findLast==='function'});"
    "t('stringReplaceAll',function(){return typeof String.prototype.replaceAll==='function'});"
    "t('promiseAny',function(){return typeof Promise.any==='function'});"
    "t('objectGroupBy',function(){return typeof Object.groupBy==='function'});"
    "t('mapGroupBy',function(){return typeof Map.groupBy==='function'});"
    "t('promiseWithResolvers',function(){return typeof Promise.withResolvers==='function'});"
    "t('setMethods',function(){return typeof Set.prototype.intersection==='function'});"
    "t('iteratorHelpers',function(){return typeof Iterator!=='undefined'&&typeof Iterator.from==='function'});"
    "t('arrayFromAsync',function(){return typeof Array.fromAsync==='function'});"
    "t('regexpV',function(){try{return /\\p{L}/v.test('a')}catch(e){return 'missing'}});"
    "t('supArrayProto',function(){return typeof Array.prototype.toSorted==='function'});"
    "paint();"
    "</script>",
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
        if path == "/intersection.html":
            return self.send(200, INTERSECTION_PAGE)
        if path == "/animate.html":
            return self.send(200, ANIMATE_PAGE)
        if path == "/empty.html":
            # Nothing to read: reader view finds no article.
            return self.send(200, page("EMPTY", ""))
        if path == "/private.html":
            return self.send(200, page("PRIVATE", "<p>PRIVATE-PAGE-TEXT</p>"))
        if path == "/challenge-caps.html":
            return self.send(200, CHALLENGE_CAPS_PAGE)
        if path == "/api-probe.html":
            return self.send(200, API_PROBE_PAGE)
        if path == "/challenge-caps-worker.js":
            return self.send(200, CHALLENGE_CAPS_WORKER, "application/javascript")
        if path == "/challenge-caps.wasm":
            # Deliberately text/html, like Vercel serves challenge.v2.wasm: streaming compilation
            # must reject it and the worker has to fall back to buffering.
            return self.send(200, b"\x00asm\x01\x00\x00\x00", "text/html; charset=utf-8")
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
                "history": [],
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
            with lock:
                state["bookmarks"].clear()
                state["lists"].clear()
                state["lists"].update(fresh_lists())
                state["hister"].clear()
                state["log"].clear()
                for value in faults.values():
                    value[0], value[1] = "none", 0
            return self.send_json(200, {"ok": True})
        if path == "/_admin/fault":
            service = query.get("service", [""])[0]
            kind = query.get("kind", ["none"])[0]
            if service not in faults or kind not in FAULTS:
                return self.send_json(400, {"error": "bad fault"})
            with lock:
                faults[service] = [kind, int(query.get("count", ["1"])[0])]
            return self.send_json(200, {"ok": True})

        if path == "/api-probe-report":
            self.record({"service": "api-probe", "results": json.loads(raw or b"{}")})
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
