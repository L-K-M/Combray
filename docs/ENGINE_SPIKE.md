# M0 engine spike: Servo on Android

**Date:** 18 October 2026. **Scope:** milestone M0 of the [implementation plan](IMPLEMENTATION_PLAN.md#m0--prove-servo-on-android). This report records what was built, how, and what ran on Android. Build commands are in [BUILDING.md](BUILDING.md).

## Summary

| Capability (M0) | Verdict | Evidence |
| --- | --- | --- |
| Servo builds and runs on Android | **Go** (x86_64 emulator). ARM64: builds and packages; **not run** (no device) | Upstream servoshell passed mach's smoke procedure (`scripts/m0/smoke-upstream.sh`, [log](engine-spike/upstream-smoke-logcat.txt)); the harness ran every check in section 5 except text selection |
| Two independent views: switching, navigation, back/forward, close | **Go, one open defect** | All view checks pass; a reproducible stale frame appears when other views load or close while a large page is visible ([section 6](#6-open-defect-stale-frame)) |
| Input: touch, scroll, keys, IME | **Go** for tap, scroll, keys, IME commit; selection and pinch zoom not run | Section 5 |
| Surface lifecycle | **Go** | Resize, Home and return, rotation (Activity and surface recreated) keep tabs and documents |
| Bounded, attributed text capture | **Go** | Document-guarded capture never attributed text to the wrong document in any check, including navigation and close during capture and same-URL reloads |
| Profile persistence | **Blocked for process death** in M0; addressed after M0 by patch 0004 ([section 13](#13-after-m0-engine-changes-for-the-app)) | Cookies survive a clean engine shutdown but not a killed process; localStorage survives both |
| Script interruption | **Not available** in Servo v0.6.0 | Deadlines abandon results; the script keeps running |

The engine gate in the plan is met on the emulator: independent views and correctly identified capture pass on real Servo. Two problems must be solved or accepted before M1 relies on them: the stale frame and cookie loss on process death.

## 1. Environment

| Item | Value |
| --- | --- |
| Build host | Linux x86_64 container (Debian 12, kernel 7.0), 16 cores, 125 GiB RAM, shared with other workloads (load average 4–38 during the work) |
| Device | Android Emulator 37.1.11, AVD `combray-m0-api30-x86_64`: system image `android-30;default;x86_64` r10 (Android 11, API 30), 6 vCPUs, 6 GiB RAM, 720×1440 at 320 dpi |
| Acceleration | **None.** `/dev/kvm` is absent, so the emulator ran under software CPU emulation (`-accel off`). Boot took 7.6–17.5 minutes |
| Graphics | `-gpu swiftshader_indirect`: `OpenGL ES 3.0 SwiftShader 4.0.0.1`, EGL 1.4 |
| ABI | Runtime checks: x86_64 only. The arm64-v8a harness also builds (134 MB APK, AArch64 library with all 18 JNI exports) but was never run: no arm64 device or emulator was available |
| Toolchain | Rust 1.97.1, NDK r29 (29.0.14206865), SDK platform 37 and build-tools 36.0.0, JDK 21.0.12, Gradle 9.5.1, AGP 9.2.1; host C/C++ through zig 0.17 `cc` |
| Builds (SHA-256) | Upstream servoshell `servoapp.apk` x86_64 release: `552f76cb7def59914bd09287d7b59ca6a71117627a57e9671fab7eb9dd5876ae`; harness x86_64 (final run): `a0280b667f82c8047bfa3abec4472b348f6cdbb45a59b47b6cc0d66e8507bae7`; harness arm64-v8a (same sources): `854feff276acec6e1d9105bebcb85125baa3c1ac167c68ce90e1b050cd6e4640`. Debug-signed local builds; hashes identify them in this report only |

Software emulation is slow and timing-sensitive: Android's own framework timed out and restarted `system_server` twice while a 160 MB APK installed under heavy host load. Absolute timings below are from this environment and say nothing about phone performance.

## 2. Servo selection and integration decision

**Pinned:** Servo **v0.6.0** (git `c78d2c206f80a1c8b67eefa97f773bba513205d3`, release of 29 September 2026), built from the release's source tarball (SHA-256 `1d828f99…e0c6`, full value in [BUILDING.md](BUILDING.md#pinned-inputs)) with three small patches.

- **Why v0.6.0:** it is the base of Servo's current long-term-support line (SpiderMonkey ESR 153); the LTS policy covers the `servo` library crate, which is what Combray embeds, and not servoshell. v0.7.0 (5 October) is newer but will be superseded monthly. Moving to a later 0.6.x LTS update means re-applying those patches.
- **Why not upstream's Android shell:** servoshell's JNI layer drives one active WebView at a time, reports no tab or document identity, and has no JavaScript evaluation entry point (source inspection, confirmed by the research verification). Extending it would mean carrying a fork of a demo shell outside LTS scope.
- **Chosen arrangement:** Combray's own Rust `cdylib` (`native/`) depends on `components/servo` from the pinned tree by path, uses servoshell's CI-tested Cargo feature set, and owns its JNI surface. A Kotlin harness (`spike/android/`) drives it. Servo itself is unmodified except for:
  - `0001` (build only): the release tarball's `vendor/` directory cannot build the mozjs glue crates, because their build scripts run `cbindgen` → `cargo metadata` against each crate's own `Cargo.lock` (`encoding_rs` 0.8.41 is not vendored). The patch drops the vendor redirection; Cargo then downloads the same locked versions from crates.io and checks their checksums.
  - `0002` (embedder API): document identity and a crash fix, described in [section 4](#4-servo-patch-0002-document-identity).
  - `0003` (diagnostics): log WebRender render errors instead of discarding them.

`native/Cargo.lock` starts from Servo's release lockfile, so every shared dependency stays at the release's versions.

## 3. Engine bridge design

```text
 Kotlin (any thread)                  engine thread (owns Servo, all WebViews, GL context)
 ───────────────────                  ──────────────────────────────────────────────────────
 NativeEngine.createTab(tab, url) ─┐   loop:
 navigate / select / close / input ├─▶   handle queued Commands
 capture(tab, document, id, …)     │     Servo::spin_event_loop()
 surfaceCreated (queued)           │     drain delegate queue → attribute → EngineEvent
 surfaceDestroyed (waits)          ┘     finish / expire captures
 EngineListener.onEngineEvent(json) ◀─   paint selected view, present
       (tab, generation, document)
```

- **Ownership:** `Servo`, `WebView` and the rendering context are `Rc`-based and stay on one engine thread. JNI calls only enqueue commands. Servo's event-loop waker is another sender on the same channel. Delegates copy each notification into a queue; the engine processes the queue after the spin, so no callback re-enters Servo.
- **Views:** one `WindowRenderingContext` bound to the visible `SurfaceView`; every tab is a separate `WebViewBuilder::build()` (a new constellation browsing context, not a cloned handle). Selecting shows and focuses one view and hides the others; only the selected view is painted. Hidden views keep running (measured in the results).
- **Identity:** events carry the application's tab ID, an engine-wide **generation** per native view (recreating a tab yields a new generation; events from an older generation are dropped), and the **document ID** (Servo's top-level pipeline ID). A new document ID means a new document even at the same URL. Reports from a document that was replaced are marked `stale`; reports that overtake their document's commit (Servo delivers them on a different channel) are held until the commit.
- **Surface lifecycle:** `surfaceDestroyed` first advances a surface epoch (painting stops at once), then waits for the engine to call `take_window()` and release its `ANativeWindow` reference. A new surface is bound with `set_window()`; Servo, tabs and documents survive.
- **Panics:** a panic inside a surface operation (Servo and surfman `expect` on some surface errors) becomes an error and the engine continues. Any other panic on the engine thread aborts the process, so Android reports a crash and the app restarts with session recovery, rather than leaving a silently dead engine. Panics on Servo's own threads, and those Servo marks as handled, are left to Servo.
- **Capture:** the engine asks Servo to run a bounded extraction script **in a named document**. Servo refuses if that document is no longer active. After the result returns, the engine re-checks that the document is still the tab's current one; otherwise the text is discarded (`stale_document`). The page-reported URL is only corroboration. Text is cut to 256 KiB of UTF-8 in the page and again natively, on a character boundary; title 4 KiB, URL 8 KiB; at most 50 000 nodes are visited.

## 4. Servo patch 0002: document identity

Source inspection of v0.6.0 (research verified by a second model, about 30 claims checked) showed that an embedder cannot attribute content to a document:

- `WebView::evaluate_javascript` runs in whatever document is active when the constellation handles the request. The result does not say which document produced it.
- No delegate notification carries a document identity. Title, load-status and status reports from an old document can still reach the embedder after a new document is active. Script-to-embedder messages bypass the constellation, so their order relative to history changes is not guaranteed.
- `GlobalScope::evaluate_js_on_global` asserts `can_run_script()`. A navigation between routing and execution (or, for WebDriver and user scripts, an iframe deactivation or window close) makes the script thread panic, which closes every WebView on that script thread.

Patch 0002 (131 added and 19 removed lines in 12 files; additive for existing embedders):

| Change | Purpose |
| --- | --- |
| `pub use servo_base::id::PipelineId`; `WebView::active_document_id()` set from each history change | Embedders learn the current top-level document, including after same-URL reloads |
| `PipelineId` in `HistoryChanged`, `NotifyLoadStatusChanged`, `ChangePageTitle` | Each report names its document |
| `WebViewDelegate::notify_document_load_status_changed` / `notify_document_title_changed` (default no-op) | Every report, with its document, without deduplication |
| `WebView::evaluate_javascript_in_document(document, …)`: constellation refuses with `DocumentNotFound` unless `document` is active | A result can only come from the named document |
| `evaluate_js_on_global` returns `DocumentNotFound` instead of asserting when script cannot run | Removes the crash for every caller: embedder evaluation, WebDriver `ExecuteScript`, user scripts |

Patch 0003 logs WebRender render errors, which `Painter::render` discarded (an upstream TODO); it was added to diagnose a stale-frame problem (section 6). The changes look suitable for upstream discussion; they have not been proposed upstream yet.

## 5. Checks and results

All checks ran against real Servo in the harness on the emulator described in section 1, with synthetic pages from `spike/fixtures/server.py` (no personal data, no live services). `scripts/m0/run-checks.sh` runs the in-app `core` scenario, then drives lifecycle phases from the host, then the stale-frame reproduction. The results below are from the final run (run9, 18 October 2026, 19:48–20:15 UTC) on the final sources; per-phase reports, a summary, device details and screenshots are in [engine-spike/](engine-spike/).

<!-- CHECK-TABLE-START -->
| Phase | Check | What it establishes | Result |
| --- | --- | --- | --- |
| core | `views-two-independent-webviews` | Two tabs are distinct core WebViews with their own documents and generations | passed |
| core | `engine-renders-on-android` | Servo paints the selected tab into the Android surface (pixels match the page) | passed |
| core | `views-switching` | Selecting a tab shows it (surface pixels match its page); no paint events for hidden tabs (by design) | passed |
| core | `views-independent-navigation` | Navigating one tab leaves the other's document and URL unchanged; new page on screen | passed |
| core | `views-back-forward` | Back and forward in one tab return cached documents without touching the other tab | passed |
| core | `views-same-url-tabs-independent` | Same URL in two tabs: separate JS globals, nonces and capture attribution | passed |
| core | `memory-baseline` | Process PSS with two and ten small tabs (measurement) | passed |
| core | `navigation-taxonomy` | Fragment, pushState, replaceState and failed load produce the expected events; embedder fragment load is recorded | passed |
| core | `input-touch-tap` | A touch tap clicks a page button | passed |
| core | `input-keys-and-ime-visibility` | Tapping a field requests the IME; key events type into it | passed |
| core | `input-ime-commit-unicode` | IME commit inserts multibyte text (é😀中) | passed |
| core | `input-touch-scroll` | A touch swipe scrolls the page | passed |
| core | `input-selection` | Text selection | not run |
| core | `surface-resize` | Resizing the surface resizes the page viewport and back | passed |
| core | `capture-js-rendered-text` | Capture includes text inserted by script after load | passed |
| core | `capture-redirect-attribution` | A redirect commits once, with the final URL; capture is attributed to it | passed |
| core | `capture-same-url-reload` | Reload creates a new document; captures naming the old one are refused by Servo | passed |
| core | `capture-navigation-during-capture` | A capture racing a cross-site navigation runs in the old document and is discarded as stale (not run if no race occurs) | passed |
| core | `capture-close-during-capture` | Closing a tab during capture settles it as tab_closed at close time, with no text | passed |
| core | `capture-stale-title-ignored` | An old page's late title reports are marked stale; the new page keeps its title | passed |
| core | `capture-shadow-dom` | Open shadow DOM and slotted text are captured; hidden shadow text is not | passed |
| core | `capture-bounds-multibyte` | A 1,000-byte cap cuts multibyte text on a character boundary; hash matches | passed |
| core | `capture-bounds-large` | A 1.6 MB page is cut to the 256 KiB budget | passed |
| core | `capture-empty-document` | An empty body reports empty, not captured | passed |
| core | `capture-hostile-page` | Pages that break built-ins, return huge strings or claim huge child lists yield an error or bounded text, never other documents' text | passed |
| core | `capture-timeout-abandons-result` | A deadline reports timed_out once; the late result is ignored | passed |
| core | `hidden-view-behaviour` | Timer rate in a hidden tab, normal and throttled (measurement) | passed |
| core | `views-close-one-keeps-other` | Closing one tab leaves the other working and on screen; no events from the closed tab | passed |
| core | `display-follows-navigation` | After each scripted navigation of the selected tab that changes the swatch color, the surface shows the new page | **failed** |
| resume-check | `surface-background-foreground` | Home and return: surface released and rebound; tab and document unchanged; pixels show the page | passed |
| rotation-check | `surface-rotation-recreation` | Rotation recreates Activity and surface; tab and document unchanged; landscape viewport | passed |
| persist-set-clean | `persist-set` | Sets a cookie (echoed by the server) and a localStorage value (setup) | passed |
| shutdown | `engine-clean-shutdown` | Servo shuts down cleanly and writes its profile | passed |
| persist-check-clean-exit | `session-last-url-recovery-clean-exit` | After a clean exit, open tabs are restored at the URL committed last before it | passed |
| persist-check-clean-exit | `profile-cookie-persists-clean-exit` | A cookie set before a clean shutdown is sent after restart | passed |
| persist-check-clean-exit | `profile-localstorage-persists-clean-exit` | localStorage set before a clean shutdown is present after restart | passed |
| persist-set-kill | `persist-set` | Sets a cookie (echoed by the server) and a localStorage value (setup) | passed |
| persist-check-after-kill | `session-last-url-recovery-after-kill` | After the process is killed, open tabs are restored at the URL committed last before it | passed |
| persist-check-after-kill | `profile-cookie-persists-after-kill` | A cookie set before the process is killed is sent after restart | **failed** |
| persist-check-after-kill | `profile-localstorage-persists-after-kill` | localStorage set before the process is killed is present after restart | passed |
| diag-tail2-stale-frame-variants | `dwell-only` | Stale-frame variant: keep a large page visible for 30 s, then navigate | passed |
| diag-tail2-stale-frame-variants | `dwell-timed-out-capture` | Stale-frame variant: timed-out capture on the large page, then navigate | passed |
| diag-tail2-stale-frame-variants | `dwell-reselect` | Stale-frame variant: reselect the tab showing the large page, then navigate | passed |
| diag-tail2-stale-frame-variants | `dwell-throttled-timer-tab-closed` | Stale-frame variant: hidden timer tab created, throttled and closed, then navigate | passed |
| diag-tail2-stale-frame-variants | `dwell-eight-hidden-tabs` | Stale-frame reproduction: create and close eight hidden tabs while a large page is shown, then navigate | **failed** |
| screen-check-stale-frame-after-home | `screen-matches-selected-tab-after-home` | After the reproduction, Home and return: does the surface show the tab's page? | **failed** |
| screen-check-stale-frame-after-rotation | `screen-matches-selected-tab-after-rotation` | After the reproduction, rotation: does the surface show the tab's page? | passed |

Totals: 42 passed, 4 failed, 1 not run.
<!-- CHECK-TABLE-END -->

| Tab 1 selected | Tab 2 selected | After rotation |
| --- | --- | --- |
| ![Tab 1 showing fixture page A](engine-spike/tab1-a.png) | ![Tab 2 showing fixture page B](engine-spike/tab2-b.png) | ![Landscape after Activity recreation](engine-spike/rotation-landscape.png) |

What the checks establish, beyond pass or fail:

- **Independence.** Each tab is a separate `WebViewBuilder::build()`: distinct `WebViewId`s, documents and generations. With the same URL in two tabs, a global set in one tab is `undefined` in the other, and simultaneous captures return each tab's own per-load nonce.
- **Switching paints the right tab.** After selection the harness reads the surface back (`PixelCopy`) and compares it with the selected page's swatch color. Hidden tabs produce no paint events, by design: only the selected view is painted. The same pixel comparison follows each scripted navigation of the selected tab whose swatch color differs from what was on screen (`display-follows-navigation`) and the return from Home.
- **Capture identity.** Across a same-URL reload the document ID changes. A capture naming the old document is refused by Servo itself (`document_not_found`, tested with the engine's local shortcut disabled). An unguarded evaluation in the same situation silently returns the new document's nonce, which is exactly the misattribution the patch prevents. A capture racing a cross-site navigation ran in the old document and was discarded as `stale_document`. Closing a tab settles its pending capture as `tab_closed`. Late results after a deadline are reported and dropped.
- **Stale titles.** The old page sets `document.title` in `pagehide`/`unload`. Its reports arrive after the new document committed and are attributed `stale`. Upstream's unattributed `notify_page_title_changed` received the stale title too, and names no document, so only arrival order decides which title an embedder relying on it shows. In run 9 the stale title arrived last (`null`, empty, `M0-C`, `M0-STALE-FROM-A`), leaving the old page's title on the new page; in run 8 it arrived before `M0-C`.
- **Bounds.** A 1.6 MB page is cut to 261,996 bytes at a character boundary (`truncated_in_page`); a page whose text getter returns 5 MiB is cut to 262,144 bytes; a 1,000-byte cap on multibyte text yields 998 valid UTF-8 bytes; an empty body reports `empty`; a page that breaks `getComputedStyle` yields `script_error` rather than partial text; a page that claims a billion children per node yields `script_error` within 0.3 s instead of exhausting memory (the traversal never queues more children than its node budget can visit). Open shadow roots and slots are captured; `hidden` content inside them is not.

## 6. Open defect: stale frame

**Symptom.** After certain activity in *other* views while the visible view shows a long page (`large.html`, 1.6 MB of text), later navigations of the visible view reach Servo but not the screen. History, titles, load events and captures all report the new document; the surface keeps showing the old page. In the minimal reproduction the screen does not recover after a second navigation, nor after Home and return, which releases and rebinds the surface at the same size. It does recover after rotation, which recreates the Activity and a differently sized surface (runs 7 to 9).

**Reproduction** (`diag-tail2:dwell-eight-hidden-tabs`, failing in runs 5 to 9): open a tab, select it, navigate to `large.html`; create eight hidden tabs on a small page and close them; navigate the visible tab to `b.html`. The variants that only dwell on the page, reselect it, or run a timed-out capture pass. In the full scenario, a hidden tab navigating, being throttled and closing while `large.html` was visible also triggered it; there the screen caught up later (after 15–45 seconds in runs 5 and 6), which is why `views-close-one-keeps-other` passes while `display-follows-navigation` fails on the same navigation.

| After Home and return | After rotation |
| --- | --- |
| ![Tab header at c.html while the surface still shows the large page](engine-spike/stale-frame.png) | ![After rotation the surface shows page C](engine-spike/stale-frame-recovered-after-rotation.png) |

**What is known.**

- Temporary logging in `components/paint/painter.rs` (not in the patch set) showed the paint layer's bookkeeping is correct: after the navigation, the root display list contains exactly one visible WebView whose root pipeline is the new document.
- WebRender reported no render error (patch 0003 logs them).
- The problem needs the large page; the same operations with small pages are fine.
- It was seen only with SwiftShader under software emulation. No GPU device was available.

**Next steps.** Run the reproduction on an arm64 phone. Test whether a resize alone clears it, since rotation does and a same-size surface rebind does not; that would also be a cheap workaround. If it persists, capture WebRender state (`WebView::capture_webrender`, debug flags) and report upstream with the harness reproduction. Candidate workarounds to evaluate, not yet tested: one `OffscreenRenderingContext` per tab composited into the window context, or rebuilding the shared painter after view churn.

## 7. Navigation event taxonomy

Observed through the patched delegate on the device (`navigation-taxonomy`, other checks) and from source inspection where noted:

| Navigation | Engine event | Document ID |
| --- | --- | --- |
| Embedder `load` of a new URL | `navigation_requested` (with the caller's request ID), then `document_committed` | New |
| Redirect | One `document_committed` with the final URL | New |
| Same-URL reload | `document_committed`, same URL | New |
| Back/forward to a cached document | `document_committed` | The **earlier** ID again (bfcache) |
| Page-initiated fragment change | `same_document_navigation` | Unchanged |
| `history.pushState` | `same_document_navigation` | Unchanged |
| `history.replaceState`, same URL | `history_updated` | Unchanged |
| Embedder `load` of a URL differing only in its fragment | `document_committed` | **New**: the embedder path reloads instead of scrolling |
| Network failure (connection refused) | `document_committed` for Servo's error page ("Error loading page"), load `complete` | New; indistinguishable from success except by title (source: no load-failure notification exists) |
| Load status, title | Tagged with the reporting document; `stale` after replacement; held until commit when they overtake it | Not applicable |

Requests and commits are separate events. Servo does not tie a commit to the embedder's request, so Combray cannot correlate them beyond ordering.

## 8. Measurements

Emulator under software emulation; useful for relative comparisons only.

| Measurement | Value |
| --- | --- |
| Process PSS, two tabs with small pages | 141 MiB |
| Process PSS, ten tabs with small pages | 156 MiB |
| Process PSS after the full `core` scenario (about 15 cached documents, two 1.6 MB pages) | 840 MiB in the final run, mostly anonymous memory Android cannot attribute (jemalloc, SpiderMonkey) |
| Hidden tab, page timer at 10 Hz | 9.4 ticks per second; with `set_throttled(true)`: 1 per second |
| Clean engine shutdown (persists cookies) | 80 s in the final run; 15–157 s in earlier runs on this host (logs not kept) |
| Capture of a 1.6 MB page | 1.1–6.0 s; 20,000-node page 10–20 s (runs 8 and 9, under varying host load) |
| Emulator boot / harness APK install | 7.6–17.5 min / 1–7 min (142 MB APK, 131 MB native library), measured during this work (logs not kept) |
| Upstream servoshell APK / library | 160 MB / 180 MB unstripped (x86_64 release) |

## 9. Security and process model

- **One process.** v0.6.0 runs everything in the app process on Android (`Opts::multiprocess` defaults to false, and servoshell's Android port does not support content processes). There is no content sandbox beyond Android's app sandbox. Web content shares the address space with the native bridge.
- **No page-to-native bridge.** Page scripts cannot call the engine; the only JavaScript Combray runs is the capture script and the harness's test evaluations, both issued natively.
- **Hostile pages.** A page can redefine any built-in the capture script uses. That changes only its own reported text; it cannot attach text to another document, because document identity comes from the engine. It can make the script run forever: there is no interruption (section 10).
- **Permissions** are denied; popups (`request_create_new`) are refused; dialogs, select and file pickers fall back to Servo's default response. (The app presents dialogs, select and file pickers since M4; see section 13.)
- The harness's network security configuration is a statement of intent only: Servo has its own network stack and does not consult Android's policy.

## 10. Limitations and checks not run

- **Not run:** physical devices, arm64 at runtime, real GPUs, Android versions other than 11, text selection, pinch zoom, external `VIEW` intents, downloads, permissions UI, Kagi or any live site, login flows, TLS sites (fixtures are plain HTTP on loopback).
- **Script interruption:** Servo's interrupt callback only serves shutdown. A capture deadline abandons the result; the page script keeps running and can hang its script thread and every tab sharing it.
- **Cookies** are written only on clean shutdown (`components/net/resource_thread.rs`, `CoreResourceMsg::Exit`). Android may kill a backgrounded app without warning, so logins set since the last clean shutdown are lost, as the after-kill check shows. A clean shutdown took 15–157 seconds here. M1 needs an upstream change (flush the cookie jar on change or on request) or must accept the loss. *After M0:* patch 0004 adds the write on request ([section 13](#13-after-m0-engine-changes-for-the-app)); cookies changed in the last five minutes of foreground use can still be lost to a kill.
- **Session recovery** is URL-level only: after a kill the harness restores each tab's last committed URL. Back/forward lists, scroll positions and form state are lost; Servo has no session export API.
- **Capture eligibility** filters `hidden`, `aria-hidden`, `inert`, editable content, form controls, `display: none`, `visibility: hidden/collapse` and `content-visibility: hidden`. It still includes text hidden by zero size, clipping, opacity or off-screen positioning, and it cannot read closed shadow roots or cross-origin frames.
- **The harness is not the product.** It has no bottom chrome, persistence model, settings or integrations; its manual UI exists for debugging. The application ID `ch.lkmc.combray.enginespike` is for the harness only.
- **Build features** mirror servoshell's (including WebGPU, WebXR, Bluetooth and test bindings) because that combination is CI-tested upstream; trimming them is an M1 decision.

## 11. Reproduce

```sh
scripts/m0/fetch-servo.sh
scripts/m0/build-harness.sh
scripts/m0/emulator.sh
scripts/m0/run-checks.sh emulator-5560
```

[BUILDING.md](BUILDING.md) lists prerequisites and the problems met. Single scenarios run with `scripts/m0/run-checks.sh <serial> core` or `diag-tail2:dwell-eight-hidden-tabs`.

## 12. Next steps

1. Run the harness on an arm64 phone (the owner's device if possible) and record the same checks there.
2. Narrow the stale-frame defect on a real GPU; report it upstream with the reproduction.
3. Report cookie persistence and embedder-script interruption upstream; decide whether Combray carries a cookie-flush patch.
4. Offer patch 0002 (document identity and the crash fix) and patch 0003 upstream.
5. Done: M1 started from `native/` and kept the engine thread, identity model and capture path ([section 13](#13-after-m0-engine-changes-for-the-app)).
6. Offer patch 0004 upstream with the cookie-loss reproduction.

## 13. After M0: engine changes for the app

The app (M1–M3) uses the same bridge, thread model and capture path. Changes since the M0 report:

| Change | Why | Where |
| --- | --- | --- |
| Patch 0004: `CoreResourceMsg::PersistState` and `SiteDataManager::persist_to_disk` write the cookie jar, HSTS list and HTTP auth cache on request; profile files are written to a temporary file and renamed, and a failed write is logged instead of panicking | Servo wrote cookies only at a clean exit, which Android rarely allows, so logins were lost (section 10). A kill during an in-place write could truncate a profile file | `native/servo/patches/0004-*` (4 files, 64 insertions, 20 deletions) |
| Servo preference `dom_intersection_observer_enabled` on | Off by default in Servo 0.6.0 (servoshell enables it only with its experimental features). Next.js sites call it on start-up; without it anthropic.com's script failed with `IntersectionObserver is not defined` and the site replaced the page with its error screen. Servo's implementation has open TODOs for clipping and visibility | `native/src/engine.rs`; `BrowsingTest#pagesCanObserveWhatIsOnScreen` |
| Every view runs `animations.js` before a document's own scripts (Servo `UserContentManager`). It adds stubs for `Element.prototype.animate`, `Element.prototype.getAnimations` and `document.getAnimations`, whose first use installs web-animations-js 2.3.2 (vendored next-lite build, with `finished` promises and `effect`) and adds the `effect.getComputedTiming()` it lacks | Servo 0.6.0 has no `Element.animate()`; its Web Animations interfaces behind `dom_web_animations_enabled` are stubs without `play`, `pause`, `cancel` or `finished`. docs.mistral.ai's marquee calls it unconditionally, so the site showed its error screen. The polyfill wraps `requestAnimationFrame`, `getComputedStyle` and animated elements' `style`, hence the lazy install; it steps aside for a complete native implementation. The constructors (`Animation`, `KeyframeEffect`) and `document.timeline` still appear only after that first use. Known polyfill bug: `pause()` then `play()` within one frame leaves a later `cancel()` without effect | `native/src/animations.js`, `native/src/animations.rs`, `native/src/engine.rs`; `BrowsingTest#pagesCanAnimateElements` |
| `persistProfile` command and `profile_persisted {ok}` event | The app persists when it leaves the foreground and every five minutes while in use; each write briefly holds the engine thread | `native/src/engine.rs`, `CombrayApplication.kt` |
| `captureArticle(tab, document, id, maxBytes, timeoutMs)` | Reader view: the capture path with `reader.js` and Readability.js instead of the text script; the result's text is the article as JSON. A separate entry point keeps `capture`'s JNI signature, which the M0 harness also binds | `native/src/reader.js`, `native/src/capture.rs`, `native/src/jni_api.rs` |
| `selectNone` command | The app's home screen shows no page: no view is painted under it | `native/src/engine.rs` |
| Page navigations decided by scheme; `external_link {url}` event | Servo allowed every page navigation: a tapped `mailto:` or `tel:` link replaced the page with its error page, and pages could open `file:` URLs. Web schemes load; app links are denied in Servo and handed to Android, only within 5 s of the user's tap or key press in the visible tab. A tap is one pointer that stayed within 10 dp of where it went down on both axes, Servo's own scroll threshold; the end of a scroll, fling or pinch does not count. A window a page opened for such a link (`target=_blank`, `window.open('tel:...')`) that has shown nothing but its initial `about:blank` is closed with `tab_closed`, then the link is reported for its opener; it would otherwise stay a blank tab. Other schemes are denied and logged by scheme only. The app launches only these schemes, drops an `intent:` link's flags, component and selector, and refuses one naming Combray's own package | `native/src/navigation.rs`, `native/src/activation.rs`, `native/src/engine.rs` (`page_navigation`), `ExternalLinks.kt` |
| Windows only after a tap; `popup_blocked` event | Servo 0.6.0 opens every `window.open()` and `target=_blank` window with or without a user gesture, and each new focused tab could open the next. The engine opens a page's window only within 5 s of the user's tap or key press in the visible tab, one window per tap, and reports refusals; the app says so once per document. Opening the window uses the tap up in the opener, so the window's first navigation (only that one, within the same 5 s) may still use it: a tapped `target=_blank` app link still opens its app | `native/src/engine.rs` (`open_page_tab`, `page_navigation`), `native/src/activation.rs` (`OpeningTap`), `BrowserCoordinator.kt` |
| `setDarkAppearance` command | Servo starts every view with a light theme and changes it only on `WebView::notify_theme_change`; without it `prefers-color-scheme: dark` never matched. The engine applies the app's light or dark theme to every view as it is created and to all views on a change; documents loaded later inherit it | `native/src/engine.rs`, `BrowserCoordinator.setAppearance`, `BrowserScreen.kt` |
| Capture time budget and touch deferral | Capture held the page's script thread for 1-6 s on a 1.6 MB page (section 8) right after load, when the user starts scrolling. The walk now stops after 400 ms for the page on screen (for hidden ones, the result deadline less 0.5 s, 2.5 s at most; none left reads nothing) and returns what it read, marked truncated (`time_budget_exhausted`); a capture of the page on screen waits while a finger is down and for 1.5 s after (10 s at most; a cancel ends the gesture, and a finger silent for 10 s counts as lifted); computed styles are read once per element. Limits: the clock is the page's, so a page faking a slow one removes the time bound (one reading NaN stops the walk), and work within one node is not clocked; the node budget still applies. Interruption in Servo (section 10) remains the fix | `native/src/capture.js`, `native/src/capture.rs`, `native/src/engine.rs` |
| `tab_opened {view, opener}` for `request_create_new`, only from the focused view | Page-opened windows (`target=_blank`, `window.open`) become focused tabs instead of being refused; background tabs cannot open windows | `native/src/delegate.rs`, `engine.rs` |
| `tab_closed` for `window.close()` (`notify_closed`) | A page closing its own window closes the logical tab like a user close | `native/src/delegate.rs` |
| `ime_shown {kind}` from Servo's `InputMethodType` | The keyboard matches the field: password fields disable suggestions and personalized learning | `native/src/delegate.rs`, `EngineSurfaceView.kt` |
| `ime_shown {document, show_keyboard}`, `imeDismissed()` and a tap check for focused fields | Servo allows the keyboard only once the page has had user activation (`InputMethodControl::allow_virtual_keyboard`), asks for it only when a field gains focus and hides it only on blur. A field a page focused on load covered the page with the keyboard; a keyboard the user closed could not be reopened by tapping the still-focused field; it stayed up after a form sent with Enter, and a hidden tab's field could take the keyboard over. Now the keyboard opens only when Servo allows it. A one-finger tap (Servo's 10 dp pan threshold; a gesture that had a second finger is no tap until every finger lifts) on the selected tab's focused field without a keyboard opens it: the engine asks the page with `elementFromPoint` in the current document, and the answer counts only for the field it asked about. When the user closes the keyboard, the app sends `ImeEvent::Dismissed`, as servoshell does, so the field gives up focus and the next tap focuses it again. The surface reads the keyboard's visibility from the window's insets on each window layout or inset dispatch and counts a closing it did not ask for as the user's, also when the keyboard was already on screen when the field asked for it; it read Compose's insets before, which are read only on a frame, and in the first device run the user's closing never reached the page (likely cause: under the test's manual clock the keyboard opened and closed between two frames). A refused `showSoftInput` counts as a dismissal. Each tab keeps its own field; a new document drops it, and `ime_hidden` comes only from the selected tab | `native/src/keyboard.rs`, `engine.rs`, `delegate.rs`, `EngineSurfaceView.kt`, `PageKeyboardTracker.kt`, `BrowserCoordinator.kt` |
| Log filter: Servo `error` only, Combray `info` | Servo's warnings often name page URLs | `native/src/jni_api.rs` |
| `control_shown {control, kind}` / `control_hidden` and `respondToControl(control, json)` for `<select>` pickers, `alert`/`confirm`/`prompt` and `<input type=file>` | The engine dropped them, so Servo answered with defaults: dropdowns could not change, `confirm()` returned false and uploads were impossible. The engine keeps each Servo handle until the app answers or the page withdraws it; page strings are bounded (4,000 characters, 2,000 options) | `native/src/controls.rs`, `engine.rs` |
| `contextClick(x, y)` and `link_menu {link, image}` | A long-press becomes a secondary click, so Servo fires `contextmenu` and, unless the page cancels it, offers its context menu; the app acts on the link or image address itself (open in a new tab through the address policy, copy, share) and dismisses Servo's menu. Menus without a link or image (editing actions) are dropped: the app has no clipboard bridge. The hold is timed from when the app handled the press, not from the event's timestamp (as Android's `GestureDetector` does), so after a main-thread stall a tap's queued lift still cancels it | `native/src/engine.rs`, `EngineSurfaceView.kt` |
| `sign_in {origin, proxy}` and `credentials` answers | Servo's `request_authentication` (navigations only) becomes a page control naming the server's origin; the app's dialog answers with a username and password or cancels, and a dropped request loads the server's 401 page. Credentials are never stored by Combray; Servo keeps accepted ones for the session. Parse errors and `Debug` output leave out the password | `native/src/controls.rs`, `native/src/engine.rs`, `PageControlDialog.kt` |
| `screen_geometry` in `TabDelegate`: screen, available screen and window are the view's size, the window at the origin | Servo 0.6.0 answers 0 for `screen.width`, `screen.height`, `availWidth`, `availHeight`, `outerWidth` and `outerHeight` when the delegate gives no geometry (its documentation promises the rendering context's size, but the code returns zeros). Pages size images and layouts from these, and bot checks treat a 0×0 screen as headless. Like servoshell's Android port, the screen is the page area, not the whole display: it leaves out the status bar and Combray's bottom bar, and shrinks while the keyboard is open. Reporting the display itself would need its size from Android through JNI | `native/src/delegate.rs`; `BrowsingTest#pagesSeeAScreenAtLeastAsWideAsTheirWindow` |
| `load_web_resource` cancels loads whose fetch destination is `audio`, `video` or `track`, and `media_blocked {document}` reports it once per document | Combray's Servo has no media backend (Servo 0.6.0 offers GStreamer, which is not built for Android), so `servo_media_dummy` accepts media data and never plays it. Servo stops a media fetch only when the player has enough data, which the dummy never reports, so every `<video>` and `<audio>` file downloaded in full; by Servo's source the element also held back the page's load event. A cancelled load is a network error: the element fires `error` and the page can show its fallback. Script fetches (`fetch()`, XHR) have the empty destination and continue. The decision is synchronous in the delegate (it only answers on the fetch's channel); a load names its view but not its document, so the engine attributes it to the tab's committed document | `native/src/media.rs`, `delegate.rs`, `engine.rs`; `BrowsingTest#mediaIsCancelledBeforeDownloadingAndThePageIsTold` |
| `control_shown {kind: "leave_page"}`, answered `accept` (Leave) or `dismiss` (Stay) | Servo's `request_unload` (a page's `beforeunload` asks to stay) was dropped, which Servo answers with leave, so unsaved form text was lost without a question. Servo does not check user activation, so the engine asks only where browsers do: on the selected tab, after a touch Up, key press or IME commit reached its current document; otherwise it allows at once. The page's script thread, shared with same-site tabs, blocks until the answer, so an unanswered question is answered Stay after 30 seconds (proposed default), as is one whose tab leaves the screen or whose answer does not fit; one whose tab closes or commits another document is dropped | `native/src/controls.rs`, `native/src/engine.rs`, `PageControlDialog.kt` |
| `setPageZoom(zoom)` for every view | More › Text size: only the display density reached Servo, so Android's font size never changed page text, and pinch zoom only magnifies. Servo 0.6.0's `WebView::set_page_zoom` changes the page's `devicePixelRatio` and lays it out again; Servo keeps it per view across navigations and clamps it to 10–1000%. The engine keeps one zoom, applies it to the open views and to each new view right after `build()` (Servo builds a view at 100%, so its first document may lay out once more), and refuses values that are not a zoom factor | `native/src/page_zoom.rs`, `engine.rs`, `jni_api.rs`; device check `TextSizeTest` (passed on #87's branch at `d4098c9`; not re-run after its paint fix or on the integrated tree) |
| `find(tab, generation, document, request, query, index)`, `clearFind(tab, generation, document)` and `find_result {document, request, status, count, index, capped}` | Find in page: Servo 0.6.0 has no embedder find API and no `window.find`. `find.js` runs in the document the app saw, through `evaluate_javascript_in_document`, and only while that view still shows it: a request for a replaced document is answered `stale_document` without running, so a page that committed before the app heard of it is neither selected nor scrolled, and clearing skips it. The script walks its rendered text nodes (at most 50 000, 999 matches and one second), selects one match with a DOM `Range` and scrolls its element to the middle of the view. The query enters as a JSON string literal, never as source. The page's realm runs it, so a page can see the query and misreport its own matches: the result is reduced to bounded integers natively, and one from a replaced document is reported as `stale_document`. Clearing restores the page's earlier selection unless the page changed it since | `native/src/find.js`, `native/src/find.rs`, `engine.rs` |
| `sign_in {origin, proxy}` and `credentials` answers | Servo's `request_authentication` (navigations only) becomes a page control naming the server's origin; the app's dialog answers with a username and password or cancels, and a dropped request loads the server's 401 page. Combray stores no credentials. Servo keeps a 401 answer only in the page's URL (next row), and a 407 (proxy) answer in its auth cache (limitations below). Parse errors and `Debug` output leave out the password | `native/src/controls.rs`, `native/src/engine.rs`, `PageControlDialog.kt` |
| Every URL the engine reports leaves out `user:password@`: history events (`document_committed` and the rest), `navigation_requested`, `page_navigation_requested`, a capture's `content.url` and `observed_url`, `link_menu` addresses, and reader-view links. Before Servo starts, the engine deletes `auth_cache.json` (and patch 0004's `.tmp` copy) from the profile | After an HTTP sign-in, Servo 0.6.0 writes the username and password into the request URL (`http_loader.rs`, step 14.3) instead of an auth-cache entry, so the committed document, its history entries and its relative links carry them. The app stored that URL in Recent, showed it, shared it and reopened it after a restart. Servo's own URL keeps the credentials, so the page and its links stay signed in. Without the deletion, proxy sign-ins saved by an earlier run would be sent again without asking | `native/src/userinfo.rs`, `native/src/profile.rs`, `events.rs`, `controls.rs`, `reader.js`, `engine.rs`; `HostNames.withoutCredentials` before the app stores a URL; `PageControlsTest#aSignInFromTheDialogReachesTheServer` |

Evidence on the emulator (`scripts/check-app.sh`, [BUILDING.md](BUILDING.md#emulator-and-checks)): after the app went to the background, the cookie jar on disk contained a cookie set moments earlier (`ProcessDeathTest#prepare`, passed), and after a forced stop and relaunch Servo sent that cookie again (`ProcessDeathTest#verify`, passed in run `run9`).

Limitations found while building the app:

- **Servo error lines can contain page URLs.** The log filter keeps Servo's `error` level because engine failures must stay diagnosable; some of those lines name the page. Combray's own log lines carry no page text or addresses.
- **Screen density is read once.** The HiDPI factor passed to Servo at startup is not updated when the density changes in the same process (display size setting, moving between screens); pages then render at the old scale until the app restarts.
- **No audio or video.** Without a media backend nothing plays; the engine cancels media element and text-track loads instead of downloading them (table above), and the app says so once per page. Media a page downloads by script is not cancelled and does not play either. A real backend (servo-media on Android's MediaCodec) would be new engine work.
- **Leave-page questions cover page navigations only.** Servo 0.6.0 runs `beforeunload` for navigations the page starts (links, forms, `location`), for reloads and for `window.close()`. The constellation loads embedder URLs (`LoadUrl`) and traverses history without it, so a typed address, a link from another app, Back/Forward and closing a tab still discard unsaved form text without asking. Covering them needs a Servo change.
- **A refused or handed-off link still stops the page's loading.** Servo's `navigate()` queues `abort_a_document_and_its_descendants` on the script thread before it asks the embedder (`components/script/navigation.rs`, step 24.4, lines 546-565 of the patched tree), and `NavigationRequest::deny()` does not undo it. The page stays on screen, but its in-flight fetches, EventSources and parser are aborted. If the link was tapped before the load event, the page never reports load complete: the address bar can keep showing it loading, and capture skips it. The fix needs a Servo patch: skip the abort for schemes the embedder will not load, as Chromium does for external protocols, or queue it only once the embedder allows the navigation.
- **The tap check ignores pinch-zoom panning.** Servo 0.6.0 exposes the pinch-zoom factor but not the zoomed area's offset, so on a pinch-zoomed page a tap on a field focused before the user touched the page can miss it (no keyboard; tapping elsewhere and then the field works) or count a nearby tap.
- **Profile writes block the engine thread** for the duration of three small file writes; the five-minute interval keeps that rare.
- **An HTTP sign-in lasts only on its page.** Servo 0.6.0 keeps a 401 answer in the document URL, which the app never sees, not in its auth cache. Links on the signed-in page inherit it; anything else asks again: the same address typed, opened from Recent, restored after a kill or opened from the link menu in a new tab. A script request that does not inherit the URL (`fetch(location.origin + …)`) gets the 401 without a prompt, because Servo asks only for navigations. The fix is a Servo patch that stores the answer as an auth-cache entry for its origin; it must ship together with the next item's patch change, or it would write 401 passwords to disk.
- **Proxy sign-ins reach the disk.** Servo stores the answer to a 407, which any server can send (Servo does not check that a proxy is configured), in its auth cache with the password in plain text, and sends it again to that origin as `Authorization` without asking. Patch 0004 writes the cache to `auth_cache.json` with the cookies (upstream wrote it only at a clean exit). The engine deletes the file before Servo starts, so a later run does not read the sign-in back, but the file stays on disk from the next profile write until then. Planned patch change: leave `auth_cache.json` out of `persist_state` in patch 0004, which also covers the exit write, so the auth cache lives only in memory. Not done here because it means preparing the Servo tree again.
- **Pages behind an HTTP sign-in can be captured.** Before the address lost its credentials, Combray skipped such pages for Hister and refused a Karakeep save as sensitive. Now they are treated like a page signed in with a cookie: captured only when Hister capture is on and the host is not excluded.
- **Emulator compositor.** After about 2.5 hours of headless running, the reference emulator's screenshots turned all black, the launcher included, while the app kept working. This was an emulator problem, not an engine one; `check-app.sh` now detects it before running checks.

## 14. Sol review validation, 21 October 2026

Source baseline: `e5cf8ed`, Servo v0.6.0 at the revision in section 2, patches 0001–0004. The Sol fixes change logical browser/outbox behavior, not the native pin or patch set. Host: Linux x86_64, JDK 21.0.12.1, Rust 1.97.1, NDK 29.0.14206865, SDK platform 37. The existing Zig 0.17 host wrappers described in [BUILDING.md](BUILDING.md) were used.

Native build, executed from the review worktree:

```sh
ANDROID_HOME=/home/paseo/android-sdk ANDROID_SDK_ROOT=/home/paseo/android-sdk \
COMBRAY_HOST_CC=/home/paseo/.paseo/worktrees/14fivrxx/subtle-mayfly/servo-checkout/hostbin/host-cc \
COMBRAY_HOST_CXX=/home/paseo/.paseo/worktrees/14fivrxx/subtle-mayfly/servo-checkout/hostbin/host-c++ \
CARGO_BUILD_JOBS=4 scripts/build-native.sh
```

Passed in 16m11s; staged x86_64 `libcombray_engine.so` (130,888,544 bytes) and `libc++_shared.so` (9,015,544 bytes). Native host tests: 20 passed; `cargo fmt --check` passed. The first host-test attempt lacked the Servo path dependency; it passed after the build script prepared the verified source. JVM tests, lint and debug/release assembly passed separately on each Sol fix branch. These are build/check times on a shared host, not phone responsiveness measurements.

Device: dedicated `combray-sol-audit` AVD, `emulator-5562`, Android 11/API 30 x86_64, 720×1440 at 320dpi, SwiftShader, no KVM. The emulator was created through `scripts/m0/emulator.sh` with `COMBRAY_AVD=combray-sol-audit` and `COMBRAY_EMULATOR_PORT=5562`; its installed emulator reported 37.2.12. The fixture server used host port 8768 because 8766 was occupied; the dedicated device mapped `tcp:8766` to `tcp:8768`. The default `check-app.sh` has a fixed fixture port and omits Reader, so Reader was selected explicitly through instrumentation instead:

```sh
python3 -I testing/fixtures/server.py --port 8768
./gradlew assembleDebug assembleDebugAndroidTest
# After pushing and installing both APKs on this dedicated emulator:
adb -s emulator-5562 reverse tcp:8766 tcp:8768
adb -s emulator-5562 shell am instrument -w -r \
  -e class ch.lkmc.combray.ReaderTest \
  ch.lkmc.combray.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The installed app contained Reader fix `2ae2b8b`; the second run used the test helper change later committed in `6b6d489`. Both complete runs passed 2/3 checks: unavailable-article handling and article-link navigation passed. Article extraction, markers and displayed text assertions passed, but the following system Back injection failed with `SecurityException: Injecting to another application requires INJECT_EVENTS permission`. The existing `Device.dismissSystemDialogs()` was added immediately before Back; it did not clear the interference reliably on this emulator.

A read-only REA/adb screenshot showed “System UI isn't responding” covering the screen: 720×1440 PNG, SHA-256 `19884e2324b5e7ea5188ab40e2b012c152a5bc90d4a21b66797aa9f6d067e9c8`, Evidence `ev_9ec17de27963221eb3ed27bb7bada937afc55e82c0f5ac113a6ea96225207ac0`. Restarting System UI on this disposable emulator was attempted; the next run timed out and was stopped. **The device gate did not pass.** No physical phone, live service, personal history, Kagi login, dark/landscape sweep, or performance benchmark was exercised in this pass.

## 15. Directional scroll controls

22 October 2026. Servo revision and patches remain those in [BUILDING.md](BUILDING.md#pinned-inputs). No upstream files changed. The bridge adds `scrollToEdge(tab, generation, document, edge)` in `commands.rs`, `jni_api.rs` and `engine.rs`.

The coordinator and engine reject stale view generations, replaced documents and hidden tabs. Servo's existing `evaluate_javascript_in_document` guard checks the document again when executing the jump. The fixed script scrolls `document.scrollingElement`, preserves horizontal position and overrides CSS smooth scrolling with `behavior: 'instant'`. Servo's `notify_scroll_event` instead hit-tests a point and can scroll a nested panel.

Servo 0.6.0 has no scroll-position delegate callback. The page button follows single-finger vertical movement beyond Android's touch slop; reader view follows its native list position. Page scroll limits are not observed, so a gesture at an edge or on an unscrollable element can still offer a button. Scripted page scrolling does not change its direction. A bottom jump reaches the currently loaded content, not future infinite-scroll entries. Page overrides of DOM scrolling APIs can prevent the jump; embedder scripts keep the interruption limitation in [section 10](#10-limitations-and-checks-not-run).

On the API-30 emulator, a programmatic root jump painted the bottom while `scrollTop` still reported its old value: 408, with `innerHeight` 535 and `scrollHeight` 3749 (`scroll-final`). Polling did not refresh it. The first title-based endpoint checks therefore timed out; endpoint verification now uses the viewport-high colored sections on screen. Touch scrolling still updates the fixture's position title. This is an observed Servo DOM-metrics limitation, not proof that all scroll-dependent site scripts work.

Verification on Linux x86_64, JDK 21, Rust 1.97.1 and NDK r29:

- Passed: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` with `ANDROID_HOME=/home/paseo/android-sdk`. Gesture checks cover reversal, jitter, horizontal movement, taps and cancellation; coordinator checks cover same-address reloads, generation mismatch, hidden/closed/crashed tabs and reader view.
- Passed: `cargo fmt --check` and `cargo test --lib` in `native/` (20 host checks).
- Passed: `scripts/build-native.sh` for `x86_64-linux-android` (release), using `ANDROID_SDK_ROOT=/home/paseo/android-sdk`, the host compiler wrappers described in [BUILDING.md](BUILDING.md#host-prerequisites), and `CARGO_BUILD_JOBS=4`. The local worktree reused the prepared Servo tree through a symlink; its source paths caused a fresh Servo rebuild. The updated native library was then packaged by Gradle.
- Passed on `emulator-5560` (Android 11/API 30, x86_64, software emulation/SwiftShader): `ReaderScrollTest`, `AccessibilityTest#scrollButtonsHaveLabeledUncrowdedTouchTargets`, and the existing `BrowsingTest#pagesCanObserveWhatIsOnScreen` baseline (`scroll-baseline`).
- Passed on the same emulator: both `ScrollTest` checks, rendered top/bottom jumps and same-address reload reset (`scroll-verified`, 113.7 s). The app APK SHA-256 was `a1e671e0d105eb49dda9a40a7445a0dc0f91f480c27aca806dc1b25d66f2d94a`.

Local reports, logcat, device identity and APK digests are retained under `build/device-checks/scroll-edge-controls-20261022/` in the original checkout. Earlier run `scroll-painted` was interrupted by another APK install at 04:46 UTC (`installPackageLI` killed the instrumented app): one check crashed, one did not run. This was not an engine crash; `scroll-verified` ran with the device reserved.

Reproduce the focused device checks after `scripts/build-native.sh` and `./gradlew assembleDebug assembleDebugAndroidTest`:

```sh
scripts/check-app.sh emulator-5560 ch.lkmc.combray.ScrollTest ch.lkmc.combray.ReaderScrollTest 'ch.lkmc.combray.AccessibilityTest#scrollButtonsHaveLabeledUncrowdedTouchTargets'
```

`ScrollTest` uses only `testing/fixtures/server.py`'s long page: real touch input selects each action, then screen-color checks establish that Servo painted the corresponding viewport-high endpoint section. Position titles corroborate touch scrolling, not programmatic jumps. It also checks same-address reload reset. `ReaderScrollTest` checks both ends and left placement in an RTL article. The accessibility check verifies labels and uncrowded 48dp targets at large font scale. Physical-device and live-site checks are not run.

## 16. Kimi article compatibility (23 October 2026)

The owner's reported `https://www.kimi.ai/blog/kimi-k3-1-pro` failure reproduced
on Android 11/API 30, x86_64 `emulator-5580`, SwiftShader, without KVM. The
engine remained Servo v0.6.0 at the revision in section 2, Rust 1.97.1 and NDK
r29. Temporary console diagnostics in the M0 harness exposed this exception:

```text
SyntaxError: ':scope > section > h1, .markdown :is(h2, h3):has(> strong), .markdown > p:has(> strong)' is an invalid selector
```

The article's startup calls `querySelectorAll` with that selector list. Stylo
0.21.0's `servo/selector_parser.rs::parse_has` reads
`layout.css.has-selector.enabled`, which defaults to false. Servo exposes no
mapping for it. The exception reaches Next.js, which replaces the article with
its client-side error screen. REA process capture
`ev_d9a380b76cf730585f68d2e8da0ea47d3c51ad8d11b4de0b5a51119ea9892070`
retains the exception and the resulting page text.

The synthetic `/has-selector.html` fixture reproduced that exact rejection.
A trial exposing and enabling the global Stylo switch let the live article
load, but failed the fixture's dynamic stylesheet check: adding `active` to a
child did not update its parent's `:has(> strong.active)` style. This trial is
not sufficient for enabling stylesheet support. Local evidence is under
`build/kimi-debug/` (ignored); `before` records the selector rejection and
`after2` records the stale style.

Patch `0007` instead adapts the parser only for `querySelector`,
`querySelectorAll`, `matches` (including its WebKit alias) and `closest`.
Stylo's native matching computes these results on demand, with selector flags
and invalidation disabled by the existing DOM API paths. All other parser
hooks delegate to Stylo. No global preference or dependency version changes.
Recheck the adapter's delegation against `selectors::Parser` on engine updates.

Modified covered files: `components/script/dom_selector_parser.rs` (new),
`components/script/lib.rs`, `components/script/dom/element/element.rs`, and
`components/script/layout_dom/servo_dangerous_style_node.rs`.

**Limit:** stylesheet `:has()` rules remain ignored and
`CSS.supports('selector(:has(*))')` remains false. Six of the page's 16 fetched
stylesheets use `:has()`, including header, background and paragraph-spacing
rules. Fixing startup does not establish visual parity. Full CSS support needs
Servo's relative-selector invalidation wired for DOM, attribute and state
changes; the global switch alone is unsafe.

Validation used JDK 21 and the host's Zig 0.17 compiler wrappers with
`-fno-sanitize=undefined`, as in BUILDING.md. Commands from the repository root
(`COMBRAY_HOST_CC`/`COMBRAY_HOST_CXX` name those local wrappers):

```sh
ANDROID_SDK_ROOT=/home/paseo/android-sdk \
  COMBRAY_HOST_CC="$PWD/build/kimi-debug/host-cc" \
  COMBRAY_HOST_CXX="$PWD/build/kimi-debug/host-c++" \
  CARGO_BUILD_JOBS=8 scripts/build-native.sh
ANDROID_HOME=/home/paseo/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
ANDROID_SDK_ROOT=/home/paseo/android-sdk COMBRAY_RESULTS=build/kimi-debug/final \
  scripts/check-app.sh emulator-5580 ch.lkmc.combray.BrowsingTest ch.lkmc.combray.ReaderTest
```

The final x86_64 native build passed (131,007,968-byte engine library), as did
22 native host tests, Rust formatting, the new parser's formatting, 32 script
regressions, JVM unit tests, lint and APK assembly. The device run passed
13/16 Browsing checks, including `pagesCanUseRelationalSelectors`, and all five
Reader checks. The selector check covers the exact Kimi query, `matches`,
`closest`, class mutations, other pseudo-classes, invalid selectors, and safe
stylesheet rejection. The three failures were `aSharedLinkOpensInANewTab`,
`backFromAnotherAppsLinkLeavesTheAppAndKeepsThePageInHistoryOnly`, and
`anExternalLinkOpensInANewTab`: all timed out in `ActivityScenario.close`,
waiting for `DESTROYED` after `PAUSED`. The broader UI run is not a pass.

The final patch also passed a fresh live Kimi load in the M0 harness: after
load completion and a 15-second settling interval, the document contained
“Introducing Kimi K3.1 Pro” and article paragraphs instead of “Application
error”, with `CSS.supports('selector(:has(*))')` false. Temporary console and
harness diagnostics are excluded from the product. The validated x86_64 engine
SHA-256 is `133daa3dd19bb0461a5d232b9de2b19e1be86334857d03612ba4e9fec2507c73`;
the product debug APK is
`2a88a0855472c5cb8945db250f83338d03c1dce95379553b8514708b4f30bab9`.
No physical phone, live integration service, login or full CSS conformance was
tested. All source changes remain downstream.
