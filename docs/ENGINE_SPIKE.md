# M0 engine spike: Servo on Android

**Date:** 18 October 2026. **Scope:** milestone M0 of the [implementation plan](IMPLEMENTATION_PLAN.md#m0--prove-servo-on-android). This report records what was built, how, and what ran on Android. Build commands are in [BUILDING.md](BUILDING.md).

The numbered M0 sections retain their original release-and-patch evidence. Current source maintenance is recorded in [Downstream engine maintenance](#downstream-engine-maintenance-23-october-2026).

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

**M0 input:** Servo **v0.6.0** (git `c78d2c206f80a1c8b67eefa97f773bba513205d3`, release of 29 September 2026), built from `servo-v0.6.0-src-vendored.tar.gz` (SHA-256 `1d828f9956fcb0fccfd5af02e4c75bfb948344f04ba7af1765faff78d4aec0e6`) with three small patches. The current downstream pin is in [BUILDING.md](BUILDING.md#pinned-inputs).

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

Patch 0003 logs WebRender render errors, which `Painter::render` discarded (an upstream TODO); it was added to diagnose a stale-frame problem (section 6). These changes stay downstream. The owner prohibits upstream contributions and contact.

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

**Next steps.** Run the reproduction on an arm64 phone. Test whether a resize alone clears it, since rotation does and a same-size surface rebind does not; that would also be a cheap workaround. If it persists, capture WebRender state (`WebView::capture_webrender`, debug flags) and keep the reproduction downstream. Candidate workarounds to evaluate, not yet tested: one `OffscreenRenderingContext` per tab composited into the window context, or rebuilding the shared painter after view churn.

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
2. Narrow the stale-frame defect on a real GPU; keep the reproduction downstream.
3. Investigate embedder-script interruption downstream. Cookie persistence is addressed by the change formerly carried as patch 0004.
4. Maintain document identity, the crash fix and render diagnostics downstream. Never offer them upstream.
5. Done: M1 started from `native/` and kept the engine thread, identity model and capture path ([section 13](#13-after-m0-engine-changes-for-the-app)).
6. Keep the profile-persistence change and cookie-loss reproduction downstream.

## 13. After M0: engine changes for the app

The app (M1–M3) uses the same bridge, thread model and capture path. Changes since the M0 report:

| Change | Why | Where |
| --- | --- | --- |
| Patch 0004: `CoreResourceMsg::PersistState` and `SiteDataManager::persist_to_disk` write the cookie jar, HSTS list and HTTP auth cache on request; profile files are written to a temporary file and renamed, and a failed write is logged instead of panicking | Servo wrote cookies only at a clean exit, which Android rarely allows, so logins were lost (section 10). A kill during an in-place write could truncate a profile file | `native/servo/patches/0004-*` (4 files, 64 insertions, 20 deletions) |
| Servo preference `dom_intersection_observer_enabled` on | Off by default in Servo 0.6.0 (servoshell enables it only with its experimental features). Next.js sites call it on start-up; without it anthropic.com's script failed with `IntersectionObserver is not defined` and the site replaced the page with its error screen. Servo's implementation has open TODOs for clipping and visibility | `native/src/engine.rs`; `BrowsingTest#pagesCanObserveWhatIsOnScreen` |
| Servo preferences `dom_fontface_enabled`, `dom_indexeddb_enabled` and `dom_adoptedstylesheet_enabled` on | Experimental and off by default in Servo 0.7 (servoshell enables them only with its experimental features). Pages that construct `FontFace`, call `document.fonts.add` or `load`, assign `adoptedStyleSheets` or open IndexedDB unconditionally stopped with a `TypeError`. IndexedDB data is stored in the profile directory. `dom_web_animations_enabled` stays off: Servo 0.7's `Animation` still lacks `play`, `cancel` and `finished`, and with it on `animations.js` would step aside for that stub | `native/src/engine.rs`; `BrowsingTest#pagesCanUseTheEnabledExperimentalApis` |
| Every view runs `animations.js` before a document's own scripts (Servo `UserContentManager`). It adds stubs for `Element.prototype.animate`, `Element.prototype.getAnimations` and `document.getAnimations`, whose first use installs web-animations-js 2.3.2 (vendored next-lite build, with `finished` promises and `effect`) and adds the `effect.getComputedTiming()` it lacks | Servo 0.6.0 has no `Element.animate()`; its Web Animations interfaces behind `dom_web_animations_enabled` are stubs without `play`, `pause`, `cancel` or `finished`. docs.mistral.ai's marquee calls it unconditionally, so the site showed its error screen. The polyfill wraps `requestAnimationFrame`, `getComputedStyle` and animated elements' `style`, hence the lazy install; it steps aside for a complete native implementation. The constructors (`Animation`, `KeyframeEffect`) and `document.timeline` still appear only after that first use. Property-indexed keyframes (`{opacity: [0, 1], offset: [0, 0.4]}`) are converted to a keyframe sequence first, as the spec processes them: 2.3.2 gives every keyframe the whole `offset`, `easing` and `composite` list and throws. Known polyfill bug: `pause()` then `play()` within one frame leaves a later `cancel()` without effect | `native/src/animations.js`, `native/src/animations.rs`, `native/src/engine.rs`; `BrowsingTest#pagesCanAnimateElements` |
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
| `load_web_resource` cancels loads whose fetch destination is `audio`, `video` or `track`, and `media_blocked {document, kind, url, referrer}` reports them per page; `engine_ready {user_agent}` | Combray's Servo has no media backend (Servo 0.7.0 offers GStreamer, which is not built for Android), so `servo_media_dummy` accepts media data and never plays it. Servo stops a media fetch only when the player has enough data, which the dummy never reports, so every `<video>` and `<audio>` file downloaded in full; by Servo's source the element also held back the page's load event. A cancelled load is a network error: the element fires `error` and the page can show its fallback. Script fetches (`fetch()`, XHR) have the empty destination and continue. The decision is synchronous in the delegate (it only answers on the fetch's channel); a load names its view but not its document, so the engine attributes it to the tab's committed document. For the app's player (VIDEO.md, slice C1) the report carries the fetch destination as `kind`, the load's `url` when it is http(s), without credentials and at most 8 KiB (never for `track`; else null), and the origin of the request's referrer when http(s) (else null; Servo applied the page's referrer policy before the delegate sees the load). A page is the committed document at its URL without the fragment: a new document, or a same-document move to another address, starts a new one. Per page the engine reports each of the 32 most recent URLs once and media without a reportable URL once; a token bucket per tab (burst 8, 1 a second), kept across a document's address changes and restarted when another document commits, drops reports beyond it, and a dropped report is not sent later. `engine_ready` adds `user_agent`, Servo's `Preferences::user_agent` read before Servo starts | `native/src/media.rs`, `delegate.rs`, `engine.rs`, `events.rs`; `BrowsingTest#mediaIsCancelledBeforeDownloadingAndThePageIsTold` |
| `control_shown {kind: "leave_page"}`, answered `accept` (Leave) or `dismiss` (Stay) | Servo's `request_unload` (a page's `beforeunload` asks to stay) was dropped, which Servo answers with leave, so unsaved form text was lost without a question. Servo does not check user activation, so the engine asks only where browsers do: on the selected tab, after a touch Up, key press or IME commit reached its current document; otherwise it allows at once. The page's script thread, shared with same-site tabs, blocks until the answer, so an unanswered question is answered Stay after 30 seconds (proposed default), as is one whose tab leaves the screen or whose answer does not fit; one whose tab closes or commits another document is dropped | `native/src/controls.rs`, `native/src/engine.rs`, `PageControlDialog.kt` |
| `setPageZoom(zoom)` for every view | More › Text size: only the display density reached Servo, so Android's font size never changed page text, and pinch zoom only magnifies. Servo 0.6.0's `WebView::set_page_zoom` changes the page's `devicePixelRatio` and lays it out again; Servo keeps it per view across navigations and clamps it to 10–1000%. The engine keeps one zoom, applies it to the open views and to each new view right after `build()` (Servo builds a view at 100%, so its first document may lay out once more), and refuses values that are not a zoom factor | `native/src/page_zoom.rs`, `engine.rs`, `jni_api.rs`; device check `TextSizeTest` (passed on #87's branch at `d4098c9`; not re-run after its paint fix or on the integrated tree) |
| `find(tab, generation, document, request, query, index)`, `clearFind(tab, generation, document)` and `find_result {document, request, status, count, index, capped}` | Find in page: Servo 0.6.0 has no embedder find API and no `window.find`. `find.js` runs in the document the app saw, through `evaluate_javascript_in_document`, and only while that view still shows it: a request for a replaced document is answered `stale_document` without running, so a page that committed before the app heard of it is neither selected nor scrolled, and clearing skips it. The script walks its rendered text nodes (at most 50 000, 999 matches and one second), selects one match with a DOM `Range` and scrolls its element to the middle of the view. The query enters as a JSON string literal, never as source. The page's realm runs it, so a page can see the query and misreport its own matches: the result is reduced to bounded integers natively, and one from a replaced document is reported as `stale_document`. Clearing restores the page's earlier selection unless the page changed it since | `native/src/find.js`, `native/src/find.rs`, `engine.rs` |
| `sign_in {origin, proxy}` and `credentials` answers | Servo's `request_authentication` (navigations only) becomes a page control naming the server's origin; the app's dialog answers with a username and password or cancels, and a dropped request loads the server's 401 page. Combray stores no credentials. Servo keeps a 401 answer only in the page's URL (next row), and a 407 (proxy) answer in its auth cache (limitations below). Parse errors and `Debug` output leave out the password | `native/src/controls.rs`, `native/src/engine.rs`, `PageControlDialog.kt` |
| Every URL the engine reports leaves out `user:password@`, except a link for another app (`external_link`), which is never `http(s)` and opens as the page wrote it: history events (`document_committed` and the rest), `navigation_requested`, `page_navigation_requested`, a capture's `content.url` and `observed_url`, `link_menu` addresses, and reader-view links. Before Servo starts, the engine deletes `auth_cache.json` (and patch 0004's `.tmp` copy) from the profile | After an HTTP sign-in, Servo 0.6.0 writes the username and password into the request URL (`http_loader.rs`, step 14.3) instead of an auth-cache entry, so the committed document, its history entries and its relative links carry them. The app stored that URL in Recent, showed it, shared it and reopened it after a restart. Servo's own URL keeps the credentials, so the page and its links stay signed in. Without the deletion, proxy sign-ins saved by an earlier run would be sent again without asking | `native/src/userinfo.rs`, `native/src/profile.rs`, `events.rs`, `controls.rs`, `reader.js`, `engine.rs`; `HostNames.withoutCredentials` before the app stores a URL; `PageControlsTest#aSignInFromTheDialogReachesTheServer` |

Evidence on the emulator (`scripts/check-app.sh`, [BUILDING.md](BUILDING.md#emulator-and-checks)): after the app went to the background, the cookie jar on disk contained a cookie set moments earlier (`ProcessDeathTest#prepare`, passed), and after a forced stop and relaunch Servo sent that cookie again (`ProcessDeathTest#verify`, passed in run `run9`).

Limitations found while building the app:

- **Servo error lines can contain page URLs.** The log filter keeps Servo's `error` level because engine failures must stay diagnosable; some of those lines name the page. Combray's own log lines carry no page text or addresses.
- **Screen density is read once.** The HiDPI factor passed to Servo at startup is not updated when the density changes in the same process (display size setting, moving between screens); pages then render at the old scale until the app restarts.
- **No audio or video.** Without a media backend nothing plays; the engine cancels media element and text-track loads instead of downloading them (table above), and the app says so on the page. A load names no document, and Servo delivers loads, document starts and commits on three channels taken in no fixed order, so a new document's first loads can arrive before its start and its commit. The engine holds a load that arrives after the app asked for a page (a new tab, an address, a reload) or after a new document reported its start, and before a commit, and gives it to the document that commits (`media::PendingMedia`; a request that commits nothing within 30 s, or a start within 5 s, gives its loads back to the committed page). The app also holds, while a page it asked for is pending, every report with a referrer, gives those from the new page's origin to that page when it commits, and gives them back to the page on screen when the wait ends without a commit. What stays wrong: a load that overtakes both the start and the commit of a page the page itself opened (a link), or a `pushState`, counts for the page before it, and a late one from the replaced document or the previous route counts for the new page. A page that sends more reports than the budget allows loses the rest, and a `pushState` route's report can be lost to an earlier route's flood. Media a page downloads by script is not cancelled and does not play either. A real backend (servo-media on Android's MediaCodec) would be new engine work.
- **Leave-page questions cover page navigations only.** Servo 0.6.0 runs `beforeunload` for navigations the page starts (links, forms, `location`), for reloads and for `window.close()`. The constellation loads embedder URLs (`LoadUrl`) and traverses history without it, so a typed address, a link from another app, Back/Forward and closing a tab still discard unsaved form text without asking. Covering them needs a Servo change.
- **A refused or handed-off link still stops the page's loading.** Servo's `navigate()` queues `abort_a_document_and_its_descendants` on the script thread before it asks the embedder (`components/script/navigation.rs`, step 24.4, lines 546-565 of the patched tree), and `NavigationRequest::deny()` does not undo it. The page stays on screen, but its in-flight fetches, EventSources and parser are aborted. If the link was tapped before the load event, the page never reports load complete: the address bar can keep showing it loading, and capture skips it. The fix needs a Servo patch: skip the abort for schemes the embedder will not load, as Chromium does for external protocols, or queue it only once the embedder allows the navigation.
- **The tap check ignores pinch-zoom panning.** Servo 0.6.0 exposes the pinch-zoom factor but not the zoomed area's offset, so on a pinch-zoomed page a tap on a field focused before the user touched the page can miss it (no keyboard; tapping elsewhere and then the field works) or count a nearby tap. Since §46 the same holds on a page that opens at its `initial-scale` inside a wider layout viewport, once the user has scrolled.
- **Profile writes block the engine thread** for the duration of three small file writes; the five-minute interval keeps that rare.
- **An HTTP sign-in lasts only on its page.** Servo 0.6.0 keeps a 401 answer in the document URL, which the app never sees, not in its auth cache. Links on the signed-in page inherit it; anything else asks again: the same address typed, opened from Recent, restored after a kill or opened from the link menu in a new tab. A script request that does not inherit the URL (`fetch(location.origin + …)`) gets the 401 without a prompt, because Servo asks only for navigations. The fix is a Servo patch that stores the answer as an auth-cache entry for its origin; it must ship together with the next item's patch change, or it would write 401 passwords to disk.
- **Proxy sign-ins reach the disk.** Servo stores the answer to a 407, which any server can send (Servo does not check that a proxy is configured), in its auth cache with the password in plain text, and sends it again to that origin as `Authorization` without asking. Patch 0004 writes the cache to `auth_cache.json` with the cookies (upstream wrote it only at a clean exit). The engine deletes the file before Servo starts, so a later run does not read the sign-in back, but the file stays on disk from the next profile write until then. Planned patch change: leave `auth_cache.json` out of `persist_state` in patch 0004, which also covers the exit write, so the auth cache lives only in memory. Not done here because it means preparing the Servo tree again.
- **Pages behind an HTTP sign-in can be captured.** Before the address lost its credentials, Combray skipped such pages for Hister and refused a Karakeep save as sensitive. Now they are treated like a page signed in with a cookie: captured only when Hister capture is on and the host is not excluded.
- **Emulator compositor.** After about 2.5 hours of headless running, the reference emulator's screenshots turned all black, the launcher included, while the app kept working. This was an emulator problem, not an engine one; `check-app.sh` now detects it before running checks.

### Reddit loading and fixed-layer scrolling (24 October 2026)

The owner reported a partially rendered Reddit feed that kept loading and
would not scroll. The screenshot's directional button shows that Combray
received the swipe. Loading does not disable input in `EngineSurfaceView`.
Reddit answered HTTP 403 from this host, including a mobile User-Agent;
the live feed and the cause of its unfinished load remain unverified.

A native regression confirmed a separate relevant defect: fixed
descendants are siblings of the viewport scroll node. When an empty fixed
scroll box cannot consume a swipe, following spatial parents never reaches
the viewport. The recovered proposal in Combray PR #116 identified this
case but had no native or device proof. The smaller downstream fix in
[Servo PR #5](https://github.com/L-K-M/servo/pull/5) chains only input scrolls
to the same document's viewport, retaining its input sensitivity. Script
scrolling stays unchanged. No site-specific script or timeout marks a
stalled page as loaded.

Candidate engine: `1a96dd8d50b891e71ff2c5b74df1627d8bdcbe9c`, based on
`38633e6cfd3e5d78651ba81698b6e8e30644231f`. The base also contains reviewed
downstream PR #3's loopback-only WebDriver listener; Combray does not enable
that listener. No dependency, toolchain or license changed. Modified
engine files: `components/shared/paint/display_list.rs` and
`components/shared/paint/tests/compositor.rs`.

Host evidence (Linux x86_64, Rust 1.97.1):

- The new compositor regression failed before the fix: expected viewport
  offset `(0, 40)`, got `None`. All six compositor tests pass afterward,
  covering empty/scrollable fixed boxes, page edges, script scrolling,
  locked viewports and document isolation.
- GLM review found that touch pans and flings use `ScrollType::Touch`,
  separately from wheel input. Its regression also failed before correction;
  both paths now run the same assertions.
- The initial broad paint test lacked `libwayland-client.so`. With the
  existing host graphics libraries and `xvfb-run -a`, the full paint API
  suite passed: two rendering tests and six compositor tests.
- The original pinned engine built for x86_64 in 7m30s with
  `scripts/build-native.sh`. Baseline APK SHA-256:
  `31397733dc16b76debd3abdb492fb507b49f903d9b87896f8459675475e2d8ac`.
- Kotlin unit tests, lint and debug/test APK assembly passed. The bridge's
  88 host tests, all 48 script tests, docs and whitespace checks passed.
- The candidate x86_64 Android engine built in 8m25s (NDK r29, two build
  jobs). Debug/test APK assembly and ELF packaging checks passed. APK:
  `0a9f663c07ee8be48a0146dccaa750ad48a0f340ff1268084d5931896dda2678`;
  engine library:
  `8a7becb842bcc91b0cfca4ab95b44e69544b83957684700628adb25641607f9b`.

`FixedLayerScrollTest` uses a synthetic transparent fixed drawer over red
and green page sections. It checks the painted viewport after a real swipe,
both after load and while an image response is held by the fixture server.
The latter must keep reporting loading until the test explicitly releases
the response.

Android baseline (`emulator-5560`, Android 11/API 30, x86_64, software
emulation/SwiftShader): the saved pre-fix APK failed at the expected pixel
assertion after the swipe, `b03a2e` instead of `2e6b4f` (164.213 s).
`build/device-checks/reddit-red/` holds the failure and a screenshot showing
the direction button over the unmoved page. A copy of `check-app.sh` used
the saved baseline APK paths; its fixture, install and check logic were
unchanged.

The candidate passed all six selected checks in
`build/device-checks/reddit-fixed/`: both fixed-layer checks (68.603 s),
all three `ScrollTest` checks (139.028 s), and the existing cancelled-media
check. Thus the page scrolls before an image finishes, loading remains
truthful, and releasing that response finishes the load. Root jumps,
same-address reload reset and the overlaid snackbar still work.

```sh
# Host engine checkout, with the existing host graphics libraries on LD_LIBRARY_PATH:
xvfb-run -a cargo +1.97.1 test --locked -p servo-paint-api

# Combray, with the host compiler wrappers documented in BUILDING.md:
CARGO_BUILD_JOBS=2 scripts/build-native.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
COMBRAY_RESULTS=build/device-checks/reddit-fixed scripts/check-app.sh emulator-5560 \
  ch.lkmc.combray.FixedLayerScrollTest ch.lkmc.combray.ScrollTest \
  'ch.lkmc.combray.BrowsingTest#mediaIsCancelledBeforeDownloadingAndThePageIsTold'
```

Next Reddit evidence requires a network that serves its feed. Its unfinished
load remains open; these fixture results do not establish live-site support.
ARM64 runtime was not tested. The companion PR supplies the CI ABI builds
and full emulator suite before merge.

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

### Scroll-button idle fade, 28 October 2026

The shared page/Reader button fades out over 200 ms after two seconds without
scroll movement. Each movement renews the timeout, including unchanged
direction; scrolling again reveals the button. Android's recommended
accessibility timeout can extend the delay. Page activity uses the existing
touch-intent callback, so page flings and scripted scrolling remain unobserved;
Reader activity includes its list's fling movement. The engine pin is unchanged:
`294580813ceb0ee579601781d2454a655dd26c9a`. No native or upstream files changed.

`ScrollEdgeButtonTest.theButtonDisappearsAfterScrollingStops` failed before
the fix because the button remained in the semantics tree after three idle
seconds. All four Compose/Robolectric regressions now pass, covering idle
removal, same-direction renewal, reappearance/clicks and an extended
accessibility timeout:

```sh
ANDROID_HOME=/home/paseo/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests ch.lkmc.combray.ui.ScrollEdgeButtonTest
```

Local Linux x86_64/JDK 21 checks passed: `testDebugUnitTest lintDebug
assembleDebug assembleDebugAndroidTest`, documentation links, whitespace and
all 89 native host tests (`cargo test --lib`). The focused device classes
above now also cover idle hiding, repeated scrolling and both directions.
They compile; no device was connected. `scripts/build-native.sh` prepared
the pinned source and began compilation but hit the caller's two-minute
deadline. Native builds and emulator validation run through the PR's Engine
workflow; local APK assembly did not include a native engine.

Initial [Engine CI](https://github.com/L-K-M/Combray/actions/runs/40714581326)
passed both native ABI builds and 130/131 API-30 x86_64 emulator checks,
including both new idle-fade checks, Reader endpoints and accessibility.
The existing page endpoint check failed when its top button was no longer
displayed after an engine wait. `ScrollTest` drives a frame/layout after
each raw swipe. Its endpoint and idle checks freeze Compose's clock, with
the latter advancing timeout steps explicitly. Reload and snackbar checks
keep automatic advancement for their recomposition and transitions (the
snackbar check only until its snackbar shows, since
[section 30](#30-device-check-timing-races-30-october-2026)).
An intermediate [run](https://github.com/L-K-M/Combray/actions/runs/40722224056)
passed the endpoint check but exposed those two dependencies when freezing
the whole class; clock control is now scoped to the checks that need it.
The follow-up run is tracked in [PR #147](https://github.com/L-K-M/Combray/pull/147).

## Downstream engine maintenance, 23 October 2026

Owner-approved migration to [L-K-M/servo](https://github.com/L-K-M/servo/tree/combray/0.6), an independent downstream repository preserving upstream Git history. `servo/servo` is source pulls only. No contributions, reports, comments, fork API calls or outreach are permitted. The rule is in both repositories' `AGENTS.md` and global agent instructions.

Base: `c78d2c206f80a1c8b67eefa97f773bba513205d3` (v0.6.0). Downstream pin: `97be6cb1fb50b6fe82f677dd30c901102a3fa94a`. Engine changes are imported unchanged as separate commits:

| Former patch | Downstream commit | Scope |
| --- | --- | --- |
| 0001, crates.io workaround | Not needed | Git source already uses crates.io; only the release tarball added vendor redirection |
| 0002, document identity | `f860cdf98ec` | 12 files: constellation, identified embedder messages, script document/global handling, guarded JavaScript evaluation and WebView delegates |
| 0003, render diagnostics | `70b288de85e` | `components/paint/painter.rs` |
| 0004, profile persistence | `718bb542539` | `components/net/resource_thread.rs`, `components/servo/site_data_manager.rs`, `components/shared/base/lib.rs`, `components/shared/net/lib.rs` |
| 0007, DOM relational queries | `97be6cb1fb5` | Four files in `components/script/`; all 15 Parser hooks preserved, only `parse_has` overridden. Stylesheet `:has()` and `CSS.supports` remain disabled. Imported unchanged in [downstream PR #1](https://github.com/L-K-M/servo/pull/1), from the candidate in [Combray PR #119](https://github.com/L-K-M/Combray/pull/119) |

Copied upstream workflows, `CODEOWNERS` and Dependabot configuration were removed before the first downstream push. The source checkout is now `servo-checkout/servo`, selected by a full SHA in `scripts/m0/servo-pin.sh`. No patch files are applied. The old release extraction and its build cache are preserved. Initially, preparation refused dirty source; the owner later selected the [disposable-checkout policy](#19-disposable-build-checkout-recovery-24-october-2026). The baseline mode fetches the original upstream SHA from our downstream repository.

Migration checks: all 14 source-preparation regressions pass, including exact SHA despite a moving branch, no SDK, offline reuse, mode switching with cache preserved, local edits and detached commits, pin updates, deleted Python environments, failed preparation retry, unavailable commits and inherited Git state. Review found the detached-commit and stale-environment cases; regression tests failed before their fixes and passed afterward. The complete script suite passes 44 tests. Documentation, shellcheck, Rust formatting and all 22 Rust host tests pass. `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease` passed locally (JDK 21); this first Gradle run had no staged engine.

The x86_64 native build of the candidate passed in 6m31s, reusing the previous target cache, with Rust 1.97.1, NDK r29 and four Cargo jobs. Host compiler wrappers call the reference Zig C/C++ wrappers with `-fno-sanitize=undefined`. The downstream `components/` tree is byte-identical to the tested release extraction with patches 0002, 0003, 0004 and final 0007 (`git diff --no-index --stat` returned no differences).

```sh
ANDROID_SDK_ROOT=/home/paseo/android-sdk \
COMBRAY_HOST_CC="$PWD/build/host-cc" COMBRAY_HOST_CXX="$PWD/build/host-cxx" \
CARGO_TARGET_DIR=/home/paseo/workspace/Combray/servo-checkout/servo-v0.6.0/target \
CARGO_BUILD_JOBS=4 scripts/build-native.sh
```

Staged x86_64 `libcombray_engine.so`: 131,035,584 bytes, SHA-256 `5fe65a66532b33211ed7e2ff30428ae014fc9b728fd93515b3b4e3925ea0ef26`.

The ARM64 native build passed in 7m12s with the same source and tools, `COMBRAY_ABI=arm64` and `CARGO_TARGET_DIR=/home/paseo/workspace/Combray/servo-checkout/target-arm64`. Its staged engine is 121,892,944 bytes, SHA-256 `443c56cde7ee12c7a85957645b3b20cbe5830712e387ab727eb79330597fe257`. After merging Combray #119, the complete Gradle test/lint/debug/test-APK/release command passed again with this ARM64 engine staged. `verify-apk.py --expected-arch` passed for both ABIs. The ARM64 debug APK is `548721dc689b99200fe787cade7d942c70f435219bd698538794c17b6fe85aed`.

The migrated x86_64 APK (`f4decaabe15ae39ddaf58b078a97f86e038fb884cc7b2ce7ef42887a4031444b`) passed 11 selected checks on Android 11/API 30 `emulator-5580`, SwiftShader without KVM: all five Reader and three Hister cases, navigation with Recent, same-address independent tabs and surface rotation. This run preceded the merge of #119's extra fixture case; it used the final imported engine code. The new selector regression's evidence remains in section 16. Local output is in `build/device-checks/servo-downstream/` (ignored).

```sh
ANDROID_SDK_ROOT=/home/paseo/android-sdk \
COMBRAY_RESULTS="$PWD/build/device-checks/servo-downstream" \
scripts/check-app.sh emulator-5580 ch.lkmc.combray.ReaderTest ch.lkmc.combray.HisterTest \
  'ch.lkmc.combray.BrowsingTest#navigatingShowsTheNewPageAndKeepsTheOldOneInRecent' \
  'ch.lkmc.combray.BrowsingTest#twoTabsAtTheSameAddressStayIndependent' \
  'ch.lkmc.combray.BrowsingTest#rotationRecreatesTheSurfaceButKeepsTabsAndThePage'
```

Hosted [Engine CI](https://github.com/L-K-M/Combray/actions/runs/40028430170) passed at Combray `e956163`: host tests, both native ABI builds, both APK architecture checks and all **57 device checks**, including selector queries, external intents, Reader, saves, Hister and both process-death phases. [Migration PR #120](https://github.com/L-K-M/Combray/pull/120) records CI and review; the stopping condition was reached with no confirmed important review findings, and remaining minor suggestions were deferred. [Downstream PR #1](https://github.com/L-K-M/servo/pull/1) merged the unchanged adapter, preserving the pinned commit. ARM64 runtime, physical devices and macOS builds were not run. Maintenance and rollback: [BUILDING.md](BUILDING.md#updating-servo).

The first Engine CI attempt passed host tests but both Android builds stopped before compilation: the hosted image exported `ANDROID_NDK_ROOT` pointing at r27. The workflow now clears that inherited override, allowing `env.sh` to select and install pinned r29. This does not change explicit local NDK overrides.

The next attempt passed host tests, both native builds and both APK architecture checks, but fresh emulator creation failed because avdmanager and the emulator selected different Android preference homes. The workflow now aligns `ANDROID_USER_HOME`, `ANDROID_EMULATOR_HOME` and `ANDROID_AVD_HOME`. A real SDK probe created the expected `.ini` and `config.ini` under an isolated home, then deleted that test AVD before retrying CI.

That retry booted the hosted KVM emulator and ran all 57 device checks: 54 passed, including the selector regression and both process-death phases. The three external-intent cases failed as in section 16. Logcat explicitly said lifecycle callbacks were ignored because the current intent did not match the launch intent. AndroidX `ActivityScenario` 1.7.0 checks that match on every callback; `MainActivity.onNewIntent` legitimately changes the activity intent. Its observer therefore missed resume, stop and destroy, leaving stale `PAUSED` state.

`BrowsingTest.deliverIncomingPage` now sends the real Android intent, waits for its page, then restores the scenario launch intent on the main thread, including on assertion failure. This makes Back and teardown observable without skipping the functional assertions. The three previously failing checks pass locally in `build/device-checks/intent-harness-fixed/`. The product APK remains byte-identical (`f4decaab…`); this correction is in the test driver. JVM tests, lint and both debug APK assemblies passed after the correction, followed by the successful full hosted run linked above.

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

## 17. Tab-counter preference checks (24 October 2026)

Settings › Display › Show tab count is off by default. The choice persists;
the Recent button's TalkBack label keeps the count either way.

Validation used Linux, JDK 21, Rust 1.97.1, NDK r29 and the API-30 x86_64
`emulator-5580`. The unchanged engine pin was
`97be6cb1fb50b6fe82f677dd30c901102a3fa94a`. No engine source changed.

```sh
ANDROID_SDK_ROOT=/home/paseo/android-sdk \
  COMBRAY_HOST_CC="$PWD/build/tab-counter-host-cc" \
  COMBRAY_HOST_CXX="$PWD/build/tab-counter-host-c++" \
  CARGO_BUILD_JOBS=8 scripts/build-native.sh
ANDROID_HOME=/home/paseo/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
ANDROID_SDK_ROOT=/home/paseo/android-sdk scripts/check-app.sh emulator-5580 \
  ch.lkmc.combray.TabCounterTest \
  ch.lkmc.combray.AccessibilityTest#theBottomBarOnAPageIsAccessible
```

The local compiler wrappers call the host's Zig-backed `cc`/`c++` with
`-fno-sanitize=undefined`, as in section 16. Native compilation passed in
6m08s. All 466 JVM tests, lint, APK assembly, 88 Rust host tests, Rust
formatting, documentation and whitespace checks passed. Both device checks
passed in `build/device-checks/20261024T094619Z/`.

The device check verifies hidden/shown counts, live updates and activity
recreation through the settings service. An earlier test tapping through
Settings hit the known software-emulator focus timeout (`keyDispatchingTimedOut`,
run `20261024T093930Z`, also after ahead-of-time compilation). The Settings
switch itself and a physical phone remain unverified. The first device
attempt used a stale local library missing `setPageZoom`; rebuilding resolved
that prerequisite.

## 18. New-tab launchpad checks (24 October 2026)

The [new-tab design](design/new-tab.md) uses Compose for Home and its editor,
with the existing Servo coordinator for navigation and exact-tab selection.
The engine pin remains `97be6cb1fb50b6fe82f677dd30c901102a3fa94a`; no engine
source changed. Validation used Linux, JDK 21, Rust 1.97.1, NDK r29 and the
API-30 x86_64 `emulator-5580`, with synthetic pages and fake services only.

Commands:

```sh
COMBRAY_HOST_CC="$PWD/build/tab-counter-host-cc" \
  COMBRAY_HOST_CXX="$PWD/build/tab-counter-host-c++" scripts/build-native.sh
./gradlew --no-daemon --max-workers=1 testDebugUnitTest lintDebug \
  assembleDebug assembleDebugAndroidTest assembleRelease
ANDROID_HOME=/home/paseo/android-sdk scripts/check-app.sh emulator-5580 \
  ch.lkmc.combray.AddressSuggestionsUiTest \
  ch.lkmc.combray.AccessibilityTest ch.lkmc.combray.HomeTest \
  ch.lkmc.combray.BrowsingTest ch.lkmc.combray.TabCounterTest
```

The native build, all JVM tests, lint, debug/test/release assembly and 88 Rust
host tests passed locally. An initial lint analysis crashed while several
Gradle invocations shared the checkout; a fresh single-use daemon with one
worker passed without disabling any detector. The schema 4→5 migration is
additive and tested, including existing tab, visit and outbox preservation.
The `www.`/bare-host Forget regressions were observed failing before the fix.

Initial emulator evidence is in `build/device-checks/new-tab-1/`:
`AddressSuggestionsUiTest` passed all three layout/click checks, and
`AccessibilityTest` passed all 24 then-current checks, including the new
dock and Find bar. The first Home run exposed a test selector matching both
modal and background + buttons; it now scopes the modal controls explicitly.
The next check hit the documented software-emulator window-focus timeout,
so an interrupted class run is not counted as a pass.

Follow-up runs use separate instrumentation invocations for individual Home
scenarios. They exposed two additional issues: a same-URL test helper could
mistake the previously loaded tab for the newly requested one, and a close
notice covered a Home destination. The helper now waits for the new tab's
identity/title. `aCloseNoticeDoesNotCoverHomeLinks` independently reproduced
the overlap before the fix (`new-tab-overlap-red`); Home now reserves the
notice's measured height, like the existing scroll control. Raw follow-up
evidence is in `build/device-checks/new-tab-2/` and `new-tab-3/`.

The first accelerated CI run on PR #123 passed 102 of 105 device checks;
the three failures were in the editor. A count-only diagnostic showed Room
emitting two pins while the dialog retained its initial view. Editor state
and its collectors now belong to the dialog's own composition. The existing
edit/reorder/unpin/hide/restore/add check then passed locally, without the
diagnostic. Footer controls are clicked directly in tests: they are outside
the scroll region and cannot use `performScrollTo`.

Review regressions independently reproduced an input moving 160dp when its
match list grew, automatic tiles reordering after backgrounding, and a pin
save being cancelled by editor dismissal while SQLite's writer was held.
The corrected viewport fills the space above the anchored field, automatic
order is retained outside the stopped collector, and accepted local writes
finish after their UI scope ends. Each regression was observed failing
before its fix and passing afterwards. Core tests also cover duplicate-URL
replacement in both ordering directions and a missing replacement reference.

`build/device-checks/new-tab-5/` records the visibility and isolated automatic
link passes. `new-tab-6/` contains the focused red/green runs, successful
editor operations, cancellation-safe save, and draft/target preservation
through rotation and a fresh editor session. These manual fixture runs used
`adb shell cmd package compile -m speed -f ch.lkmc.combray.debug` before
instrumentation to reduce software-emulator startup cost. The known 5-second
window-focus timeout still occurred on some attempts and is not counted as
a pass. Temporary probes and a test-dispatcher experiment were removed.

[PR #123](https://github.com/L-K-M/Combray/pull/123) records the final local
results and latest CI proof, including both native ABIs and KVM emulator
fixtures. Read its latest commit's results rather than treating any earlier
interrupted run as complete. Physical-phone and live-service validation
remain unperformed; the owner's data and services were not used.

## 19. Disposable build-checkout recovery (24 October 2026)

The owner reported `scripts/build.sh --debug --install` stopping after a
successful Servo fetch with `^\` / `zsh: quit` (SIGQUIT). Retrying reported
local changes. An interrupted first checkout can leave partial source, which
the previous preparation script refused. The owner clarified that
`servo-checkout/servo` is disposable build input, not an engine worktree.

Preparation now announces the checkout, requests Git progress, force-checks
out the exact pin and removes untracked leftovers. Downloaded objects and
ignored compilation/Python caches remain reusable. Source edits and local
commits are discarded as build inputs; engine development belongs in a
separate downstream checkout. The pin remains
`97be6cb1fb50b6fe82f677dd30c901102a3fa94a`; no engine source change is retained.

Linux validation fetched and prepared that exact commit. A tracked edit and
an untracked probe were removed by a subsequent preparation, while an ignored
`target/` probe remained; another run reused the Python environment. Fixture
regressions cover a partial first checkout with the remote unavailable,
discarded edits/commits, cache reuse and unchanged clean-file timestamps,
plus cleanup remaining confined to the managed checkout. Six checks failed
before the behavioral fix; all 18 source checks and all 48 script checks
passed afterward.

```sh
scripts/m0/fetch-servo.sh
scripts/build.sh --debug --install --check
python3 -I -m unittest discover -s scripts/tests
shellcheck scripts/*.sh scripts/m0/servo-pin.sh scripts/m0/env.sh scripts/m0/fetch-servo.sh
python3 scripts/check_docs.py
git diff --check
```

Shell syntax, shellcheck, documentation and whitespace checks passed.
No macOS native build or device installation was run for this change.

## Inline Home search (24 October 2026)

The owner corrected New tab's entry behavior: focus Home's existing field,
without an extra sheet. Home and page-address editing share the draft,
local suggestions and address policy. New tab resets the field and reuses
the unused blank. Incoming search text starts selected there and waits for
submission. Close clears focus and hides the keyboard, keeping Home and the
draft. A page's address editor still uses its sheet.

`HomeSearchTest.newTabFocusesTheExistingHomeField` failed before the fix on
API-30 x86_64 `emulator-5560`: no focused editable field was displayed
(`build/inline-search-red.txt`, one failure, 29.348 seconds). The coordinated
Close regression independently failed before its change; all five
`AddressSuggestionsUiTest` checks then passed on the same emulator
(`build/new-tab-close-green.txt`, 105.886 seconds), including dismissal,
48dp targets and the narrow, large-font layout.

The initial native x86_64 build and focused device checks used downstream
pin `97be6cb1fb50b6fe82f677dd30c901102a3fa94a`. This host needs the existing
`build/tab-counter-host-cc` and `build/tab-counter-host-c++` Zig wrappers
with `-fno-sanitize=undefined`; default clang failed on missing host headers.
The successful log is `build/inline-search-native.log` (4m44s).

```sh
COMBRAY_HOST_CC="$PWD/build/tab-counter-host-cc" \
COMBRAY_HOST_CXX="$PWD/build/tab-counter-host-c++" \
CARGO_BUILD_JOBS=6 scripts/build-native.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
scripts/check-app.sh emulator-5560 ch.lkmc.combray.HomeSearchTest ch.lkmc.combray.HomeTest
python3 scripts/check_docs.py
git diff --check
```

The focused Home checks cover inline focus, repeated +, Close, rotation,
incoming prefill selection, Recent's New tab and navigation in the original
blank. Pin-editor field lookups are scoped to their dialog because Home's
field now remains composed behind it.

Local verification passed all four `HomeSearchTest` checks, five
`AddressSuggestionsUiTest` checks and three real-Servo Home checks: draft
rotation/submission in the blank, a local suggestion opening in that blank,
and page-address editing retaining its original tab. The first attempt
passed 11 of 12; its empty-field assertion also counted the placeholder.
Reading `EditableText` corrected the assertion, and the entire four-check
Home search class passed on rerun (162.315 seconds), against the same app
APK. Raw evidence: `build/device-checks/inline-search-green/` and
`build/inline-search-home-green.txt`. JVM tests, lint and debug/test assembly
passed again after that correction; documentation and whitespace checks pass.

Review found a consumed shared-text prefill could seed a later Home after its
blank tab closed. The new fixture-free regression failed with the old shared
text instead of an empty field (`build/inline-search-review-red.txt`, 88.826
seconds). Ending editing now clears that launch seed, keeping the current
field's separate draft; Continue and quick-link navigation do the same.
All five Home search checks and the three real-Servo checks passed afterward.
One layout row-tap callback assertion failed once; the unchanged full layout
class then passed all five checks (93.271 seconds). Together these verify 13
focused checks. Evidence: `build/device-checks/inline-search-reviewed/` and
`build/inline-search-layout-rerun.txt`. The static text-field description was
removed in favor of Material's hint/text semantics; spoken TalkBack output
was not checked.

Final integration includes the merged notice styling and fixed-layer scroll
changes (#128 and #129). The current Servo pin is
`1a96dd8d50b891e71ff2c5b74df1627d8bdcbe9c`; its local x86_64 rebuild passed
(3m31s), followed by JVM tests, lint and debug/test assembly (30 seconds).
Logs: `build/inline-search-current-native.log` and
`build/inline-search-current-build.log`. The focused device runs above used
the earlier pin; current-pin runtime proof comes from the final Engine CI.

Final CI/review results are tracked in [PR #127](https://github.com/L-K-M/Combray/pull/127).
Physical-phone and live-service checks remain unperformed.

## 20. Close-tab successor (24 October 2026)

Closing the focused tab could select an unused Home tab while another page
remained open. Successor selection now skips blank tabs in Recent order,
falling back to Home only when no page remains. The selection stays in the
close transaction; background closes keep focus, and Undo keeps its existing
behavior.

The new coordinator regression failed before the fix by selecting the blank
tab instead of the next page. Afterward all 135 coordinator tests passed,
including the remaining-blank fallback, background close, Undo and last-tab
checks. The full JVM tests, lint, debug/test APKs and unsigned release build
passed on Linux x86_64 with JDK 21 and the pinned toolchains. Native formatting
and all 88 host tests passed. The x86_64 native build and APK ABI check passed
against unchanged Servo `97be6cb1fb50b6fe82f677dd30c901102a3fa94a`.

```sh
ANDROID_HOME=/home/paseo/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_docs.py
git diff --check
# From native/:
cargo fmt --check
cargo test --lib
# From the repository root, using this host's existing compiler wrappers:
ANDROID_HOME=/home/paseo/android-sdk \
  COMBRAY_HOST_CC=/home/paseo/workspace/Combray/build/tab-counter-host-cc \
  COMBRAY_HOST_CXX=/home/paseo/workspace/Combray/build/tab-counter-host-c++ \
  CARGO_BUILD_JOBS=4 scripts/build-native.sh
ANDROID_HOME=/home/paseo/android-sdk ./gradlew assembleDebug assembleDebugAndroidTest
python3 -I scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk --expected-arch x86_64
```

`BrowsingTest.closingTheActiveTabShowsTheNextPageInsteadOfAnUnusedHomeTab`
checks real Servo: close the green page with Home interposed in Recent,
then verify the red page's tab identity and painted pixels. The test APK
compiled; local execution was deferred because the shared emulator was
reserved. The Engine PR checks run this regression before merge. No physical
device or live services were used.

## 21. Close-notice appearance (24 October 2026)

The owner's dark-mode screenshot showed a light "Tab closed" card on a
white strip. Material's default snackbar uses inverse colors. Home's outer
notice padding also left the native engine surface uncovered.

Browser and Recent now share `NoticeHost`, using `surfaceContainerHigh`,
`onSurface` and `primary` for the card, text and action. Home paints its
background before reserving notice space. Existing contrast tests cover
these color pairs in both schemes. No engine source or pin changed.

`NoticeThemeTest` closes a synthetic page in forced Dark and Light, samples
the painted notice and Home backdrop, then checks Undo's exact tab identity.
The archived pre-fix APK failed both checks on `emulator-5560` (API 30,
x86_64): a `29302c` notice in Light, `e7e2d9` in Dark, and a white
(`ffffff`) backdrop in both. The fixed APK passed both pixel/Undo checks
and the existing Home-spacing regression. [PR #128](https://github.com/L-K-M/Combray/pull/128)
records latest device, CI and review results.

Initial attempts on a separate fresh AVD (port 5582) failed before app
installation: black screenshots and repeated Android `system_server`
restarts (`Lost network stack`). These were infrastructure failures.

The supplied engine library came from the concurrent successful same-pin
build in the shared checkout: Rust 1.97.1, NDK r29, downstream Servo
`97be6cb1fb50b6fe82f677dd30c901102a3fa94a`, using local Zig host wrappers
(`build/inline-search-native.log`, 4m44s). Plain host `clang` lacked C
headers; the notice task's corrected build was interrupted when leaving
the shared checkout. Initial PR CI also built both native ABIs.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
scripts/check-app.sh emulator-5560 ch.lkmc.combray.NoticeThemeTest 'ch.lkmc.combray.HomeTest#aCloseNoticeDoesNotCoverHomeLinks'
python3 scripts/check_docs.py
git diff --check
```

JVM tests, lint, debug/test/release APK assembly, documentation and whitespace
checks passed locally. Evidence: `build/device-checks/notice-theme-*`
and `build/notice-theme/`. The PR records latest CI and review results.
Physical-phone validation remains unverified.

## 22. Default page contrast (25 October 2026)

The owner's screenshot of
`https://gopher.muffinlabs.com/sdf.org/users/nervtoji/phlog/001.txt`
showed pale text on white. The response is HTML; its CSS gives the article
neither a text color nor a background. Servo's UA stylesheet uses
`CanvasText`, which Stylo 0.21.0 resolves to `#e8e8e8` under a dark
preference even without a page color scheme. The canvas stays white:
about 1.23:1 contrast.

The downstream fix supplies a light root `color-scheme` presentational
hint, or the document's explicit metadata scheme. Author CSS overrides
the hint. This keeps `prefers-color-scheme` working for sites with dark
styles and applies to every document, including blank frames, without
injecting page scripts. Engine source changes:

- `components/script/dom/element/element.rs`: root color-scheme hint.
- `components/script/dom/document/document.rs`: metadata scheme for layout
  and root rematching when it changes.
- `components/script/dom/html/document_metadata/htmlmetaelement.rs`:
  pass the GC guard for that rematching.

The separate development checkout is used for engine edits;
`servo-checkout/servo` remains disposable pinned build input.
Downstream review: [Servo PR #6](https://github.com/L-K-M/servo/pull/6).

`PageContrastTest` uses a `currentColor` swatch and the actual canvas to
measure inherited text without font antialiasing, with a separate paint
marker. It covers default and authored-white pages, light/dark changes,
reload, a new tab, author CSS, dark metadata and metadata changes after
paint. A title from `matchMedia` checks that the dark preference survives.

Before the fix, the authored-white regression failed with `text color
e8e8e8` on API-30 x86_64 `emulator-5560`, SwiftShader, software emulation:
`build/device-checks/page-contrast-red-2/`. The first attempt was obscured
by Android's System UI ANR dialog; restarting System UI, as documented in
[BUILDING.md](BUILDING.md), allowed the actual contrast failure to run.

The initial candidate `b7687abc34c34ebb0684dd056de9f99a3e19bf36`
passed default colors, theme changes, reload, new tabs and author styles,
but failed the new live-metadata regression with stale `e8e8e8` text after
a switch to Light (`build/device-checks/page-contrast-green/`, 3/4 passed).
Restyling without rematching reused the previous root hint. Candidate
`546db2b2db14104de3eb3d7c612a2100ad38f728` explicitly rematches the root
and makes it the dirty root before layout.

```sh
scripts/build-native.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
scripts/check-app.sh emulator-5560 ch.lkmc.combray.PageContrastTest
python3 scripts/check_docs.py
git diff --check
```

Build host: Linux, JDK 21, Rust 1.97.1, NDK 29.0.14206865, local Zig
wrappers `build/tab-counter-host-cc` and `build/tab-counter-host-c++`.
Host Rust tests (88), Kotlin tests, lint and device-test compilation pass.
The final x86_64 native candidate built in 3m07s. Final pixel checks,
both-ABI CI and review results are recorded in
[PR #130](https://github.com/L-K-M/Combray/pull/130), which gates the pin.
The Servo-wide `cargo +1.97.1 fmt --check` reports existing formatting
drift across the imported tree; no unrelated engine files were reformatted.
Existing engine limits remain: metadata accepts exact `light` or `dark`
only, and a page opting into dark must paint its dark canvas explicitly.

## 23. Servo v0.7.0 pin (26 October 2026)

The nightly maintenance task merged upstream v0.7.0 into the new downstream
line `combray/0.7` ([Servo PR #7](https://github.com/L-K-M/servo/pull/7)),
validated there with servoshell builds, the downstream regression tests,
Servo's selector and timer WPT directories and a site check against the
previous line. This moves the pin to its tip
`9c3dd2cd5e774b4c694ae877e8051586ba83857b` (upstream base v0.7.0,
`aac43a3f31a259f04a574f5ec4e959c943ad7cc7`). All downstream engine changes
from `combray/0.6` remain; the line adds patched TLS and crypto crates
(cargo-deny advisories in v0.7.0's lockfile) and a backport of upstream
`3ae0049380f`, which roots pending timer callbacks.

Bridge changes for the v0.7 embedder API:

- `WebView::focus()` is gone; system focus is per view
  (`set_focused`) and every view starts focused. Only the selected view
  keeps it; new, hidden and deselected views lose it.
- `WebView::set_throttled()` is gone; Servo throttles hidden views
  itself. `setThrottled` now hides a view and drops its focus; lifting it
  shows and focuses the view again only when it is the selected one.

`native/Cargo.lock` is Servo's v0.7 lockfile reduced to this crate's
graph, with no version changes; `log` and `rustls` exact pins follow it
(0.4.34, 0.23.46). The notices were regenerated with cargo-about 0.9.2;
the NDK did not change, so its LLVM section is the same text.

Host checks on macOS: `cargo fmt --check` and `cargo test --lib` (88) in
`native/`, `python3 scripts/check_docs.py`, `git diff --check`, the notices
test. This host has no Android SDK: both native ABI builds, packaging and
the emulator checks are the Engine workflow's evidence, recorded in the pin
PR.

## 24. Downstream fixes on the v0.7 line (27 October 2026)

The pin moves to `combray/0.7` tip
`6e493693b81baf47d93535d8683b4e7e83962641` (upstream base still v0.7.0,
`aac43a3f31a259f04a574f5ec4e959c943ad7cc7`). The nightly maintenance task
landed these downstream changes there, each with a regression test,
servoshell build, the related WPT directories, a site-check regression
sweep and an independent review:

- [Servo PR #8](https://github.com/L-K-M/servo/pull/8): the parsed foreign
  attribute `xmlns` on `<svg>` and `<math>` has a null prefix again (its
  name was `:xmlns`).
- [Servo PR #9](https://github.com/L-K-M/servo/pull/9): `console.table` and
  `console.dirxml` exist and log their arguments.
- [Servo PR #10](https://github.com/L-K-M/servo/pull/10): backport of
  upstream's zstd 0.14.3, zstd-safe 8.2.0 and zstd-sys 2.1.2 updates
  (RUSTSEC-2026-0391, -0398, -0403, -0410).
- [Servo PR #11](https://github.com/L-K-M/servo/pull/11):
  `performance.measure()` converts every PerformanceTiming name instead of
  throwing `OperationError` for `fetchStart` and others.

No embedder API changed. `native/Cargo.lock` takes the same three zstd
versions as Servo's lockfile; nothing else changes. The notices were
regenerated for the new lockfile and pin; the NDK did not change, so its
LLVM section is the same text.

This host has no Android SDK: both native ABI builds, packaging and the
emulator checks are the Engine workflow's evidence, recorded in the pin
PR.

## 25. Flex layout reuse backport (28 October 2026)

The pin moves to `combray/0.7` tip
`724524f0c2b6fe10c8f5d5ad3d9e40227460095d` (upstream base still v0.7.0).
[Servo PR #12](https://github.com/L-K-M/servo/pull/12) backports upstream
`b0c20e94ae2` (lay out flex items once when their cross size is known
upfront) and `f07470d3911` (cache the default content block size in column
flex layout). In v0.7.0 each level of nested flex containers laid out its
items again without reusing the result, so layout time doubled per level:
a synthetic chain of 20 nested column flex containers took about 11 s to
lay out and 40 levels never finished, which froze apps built from deeply
nested flex containers. After the backport 60 levels take about 1 ms. The
PR adds a WPT regression test (hangs before, passes after); css-flexbox
WPT, a servoshell site-check sweep and an independent review found no
regressions.

No embedder API or dependency changed: Servo's lockfile is the same, so
`native/Cargo.lock` does not change. The notices were regenerated for the
new pin.

This host has no Android SDK: both native ABI builds, packaging and the
emulator checks are the Engine workflow's evidence, recorded in the pin
PR.

## 26. SVG path length methods (28 October 2026)

The pin moves to `combray/0.7` tip
`294580813ceb0ee579601781d2454a655dd26c9a` (upstream base still v0.7.0).
[Servo PR #13](https://github.com/L-K-M/servo/pull/13) implements
`SVGGeometryElement.getTotalLength()` and `getPointAtLength()`, which
Servo (and upstream) lacked, so scripts that measure a shape's outline
threw `TypeError`. The path comes from the element's geometry attributes
(path data through Stylo's SVG path parser; `rect`, `circle`, `ellipse`,
`line`, `polyline`, `polygon`); percentages, font-relative units and the
CSS `d` property are not supported yet. The PR adds a WPT regression test
(0/10 before, 10/10 after); 139 upstream `svg/` subtests now pass, with no
regressions in that directory or in a servoshell site-check sweep.

The `script` crate now depends on `kurbo`, which Servo already locked, so
`native/Cargo.lock` only adds that dependency edge; no crate version
changes. The notices were regenerated for the new pin.

This host has no Android SDK: both native ABI builds, packaging and the
emulator checks are the Engine workflow's evidence, recorded in the pin
PR.

## 27. PDF probe in Servo 0.7 (27 October 2026)

Phase 0b of [PDF.md](PDF.md#phases) measured pdf.js 6.4.299 (modern and
legacy builds) and PDFium compiled to WebAssembly (`@embedpdf/pdfium`
2.15.1) inside Servo. It ran as a draft PR that was never merged and is
now closed, [L-K-M/Combray#142](https://github.com/L-K-M/Combray/pull/142),
commits `de501ae` to `e959209`. Its harness (`testing/fixtures/pdfprobe/`
and `PdfProbeTest`) exists only in those commits. Each engine rendered the
corpus in a Servo document under the planned viewer CSP
(`'wasm-unsafe-eval'`, no eval), with one module worker per document, and
the device check sampled the app's memory (`Debug.MemoryInfo`) at
checkpoints. Each engine ran in its own app process.

- Environment: Engine CI on the API 30 x86_64 emulator (SwiftShader GPU),
  with Combray's real Servo build from `combray/0.7` at
  `6e493693b81baf47d93535d8683b4e7e83962641`, the pin at the time.
- Corpus: three generated files (`fixture.pdf` in non-embedded Helvetica,
  `cjk.pdf` in a non-embedded Japanese CID font through UniJIS-UCS2-H, and
  `pages-300.pdf`) and nine mozilla/pdf.js test files pinned at `ea6cf5c`
  by sha256: tracemonkey, TAMReview, ArabicCIDTrueType, jp2k-resetprob,
  jpx_smaskindata, bitmap-symbol, function_based_shading,
  axial_shading_many_inputs and issue19971.
- [Engine run 40500206133](https://github.com/L-K-M/Combray/actions/runs/40500206133)
  at `382e31b`: both pdf.js builds under the strict CSP, the legacy build
  without a CSP, and PDFium-wasm.
- [Engine run 40506187604](https://github.com/L-K-M/Combray/actions/runs/40506187604)
  at `9806129`: adds memory samples after `TestUtils.gc()`, a
  ReadableStream async-iteration polyfill, and a sample after the tab
  navigates to another page. Servo may keep the left document in session
  history, so that sample is not a full teardown.
- [Engine run 40514996703](https://github.com/L-K-M/Combray/actions/runs/40514996703)
  at `e959209`: each engine reads the corpus twice, pdf.js runs once more
  with FontFace off (glyphs drawn as paths), and the page draws Japanese
  with canvas text.
- Commands, on the probe commits: `python3 -I
  testing/fixtures/pdfprobe/fetch.py` downloads the engines and corpus
  against pinned hashes; `scripts/check-app.sh <serial>` with the
  `PdfProbeTest` methods runs them (run 3:
  `ch.lkmc.combray.PdfProbeTest#pdfjsLegacyTwice`,
  `#pdfjsLegacyWithoutFontFaceTwice` and `#pdfiumWasmTwice`); and
  `python3 -I testing/fixtures/pdfprobe/summarize.py
  build/device-checks/<run>/pdfprobe` writes the summary. Engine CI ran
  only these methods on the probe branch.

`TestUtils.gc()` needs the `dom_testutils_enabled` preference. Only the
probe branch set it (`native/src/engine.rs` there); main does not.

pdf.js draws the corpus correctly in Servo, in a real module worker under
the strict CSP: embedded Type1, CID TrueType right to left, a JPEG photo,
JPEG 2000 and JBIG2 through its wasm decoders, and the shadings. Its ink
ratios are close to the same build in local headless Chromium (for
example 0.0024 against 0.0026 for `fixture.pdf`, 0.0689 against 0.0717
for `bitmap-symbol`), except for `cjk.pdf`. PDFium-wasm draws nothing for `cjk.pdf` without a font of
its own (ink 0).

Engine findings:

- **ReadableStream is not async iterable.** Servo's `ReadableStream.webidl`
  comments out `async iterable`, so in run 1 pdf.js text extraction failed
  for every document with `TypeError: readableStream is not iterable`. A
  small polyfill in the page fixes it (runs 2 and 3): 950,904 characters
  from the 300-page document.
- **No OffscreenCanvas or ImageDecoder, and no FontFace in workers.**
  Neither the page nor a module worker had `OffscreenCanvas` or
  `ImageDecoder`, and workers had no `FontFace`. Servo has no
  ImageDecoder interface and exposes FontFace to windows only.
  OffscreenCanvas is behind `dom_offscreen_canvas_enabled`, which is off by
  default and which Combray does not set; the probe did not try it on.
  pdf.js falls back to decoding images in JavaScript. FontFace from bytes
  works in the page, and canvas text uses the loaded face.
- **Canvas text has no CJK fallback.** `fillText` of Japanese in
  `48px sans-serif` draws empty boxes on the emulator image (the
  `canvas-cjk` snapshot), so pdf.js also draws `cjk.pdf`'s non-embedded
  Japanese font as empty boxes.
- **pdf.js memory grows on a second pass.** Run 3, app PSS after
  `TestUtils.gc()`, legacy build: 755.0 MiB after the first corpus pass and
  1,224.6 MiB after the second, +469.6 MiB, of which libc malloc +311.6 MiB
  (355.2 to 666.8 MiB). Closing three open documents released little
  (1,491.3 to 1,478.9 MiB), and after leaving the page PSS was 1,378.5 MiB
  against a 174.1 MiB baseline, with libc malloc at 776.3 MiB. With FontFace
  off the second pass still kept +464.3 MiB (libc malloc +319.1 MiB), so web
  fonts do not explain it. Where the memory is kept is not localized:
  most of the growth is libc malloc, while Servo's Rust code, its canvas
  included, allocates through jemalloc (unnamed mappings). SpiderMonkey's
  malloc-backed buffers in the page or in pdf.js's workers, and pdf.js's
  own retention, are not ruled out; `TestUtils.gc()` ran only on the page.
  Whether canvas-heavy sites hit it is not measured. Figures are MiB
  (`Debug.MemoryInfo` kB divided by 1024).
- **PDFium-wasm memory is stable.** Same run: 525.6 MiB after the first
  pass and 496.3 MiB after the second (-29.3 MiB; libc malloc 122.3 to
  120.7 MiB). Three open documents at 558.2 MiB dropped to 384.7 MiB after
  closing, and to 335.9 MiB after leaving the page (baseline 171.9 MiB).

Speed on the emulator, across the three runs: the median time for 50
pages of the 300-page document was 76 to 99 ms per page for pdf.js with
FontFace on (63 ms with it off) and 50 to 57 ms for PDFium-wasm. pdf.js
opened a document in 160 to 270 ms, PDFium-wasm in 523 to 918 ms, because
each document starts its own worker and compiles the 4.6 MB module again;
starting one instance alone took 510 to 660 ms.

The owner chose PDFium-wasm on 28 October 2026 ([PDF.md](PDF.md#owner-decisions)).

Phase 0a came first: [L-K-M/Combray#141](https://github.com/L-K-M/Combray/pull/141)
(merged) keeps the one-line page Servo commits for a file it cannot show
out of Hister and Reader (`native/src/stand_in.js`). Failure first: on
the checks-only commit `d928753`,
[Engine run 40483083824](https://github.com/L-K-M/Combray/actions/runs/40483083824)
attempt 2 failed `HisterTest.aFileServoCannotShowIsNotSentAsItsStandInPage`
(the fake Hister received the PDF's address; assertion at
`HisterTest.kt:96`) and `ReaderTest.aFileServoCannotShowHasNoArticle`
(reader view was Ready with one paragraph, "Unknown content type
(application/pdf)."). The other 127 device checks passed. On the final
head `739d66e` all 129 device checks passed.

Not verified: no run on an arm64 phone, so speed, memory and canvas CJK
text on a real device are unknown. The after-leave samples may include a
document Servo keeps in session history. The leak's cause, and whether
canvas-heavy sites hit it, are open.

## 28. Media report for the player (28 October 2026)

Slice C1 PR 1 of [VIDEO.md](VIDEO.md#plan): `media_blocked` now carries
the load's `kind`, its `url` when reportable and its referrer's origin,
reported per page within a token bucket per tab (section 13). The Servo
pin does not change.

Host checks, run on Linux x86_64 with no emulator:

- `cargo fmt --check` in `native/`: clean.
- `cargo test --lib` in `native/`: 99 passed, 0 failed, including the
  `media::` tests for `reportable_url`, the page key, per-page dedup and
  the budget.
- `./gradlew testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest`: passed; 520 unit tests, 0 failed. Lint
  reports only warnings that predate this change.

`engine.rs`, `delegate.rs` and the JNI code are Android-only, so this
host did not compile them. [Engine run 40731746689](https://github.com/L-K-M/Combray/actions/runs/40731746689)
at `77796d7` built the native engine for x86_64 and arm64 and passed all
129 emulator fixture checks on the API 30 x86_64 emulator, including
`BrowsingTest#mediaIsCancelledBeforeDownloadingAndThePageIsTold`, which
now expects two media entries for its fixture page. Review changes after
that run (local names in `history_changed`, an empty authority refused
in `reportable_url`, tests) then passed [Engine run 40741266445](https://github.com/L-K-M/Combray/actions/runs/40741266445)
at `b9b57bc`: both native builds and all emulator fixture checks.

## 29. Media player, local checks (28 October 2026)

Slice C1 PR 2 of [VIDEO.md](VIDEO.md#plan): the media player and its
network rules, all in Kotlin. No native or engine change: the
`media_blocked` and `engine_ready` shapes of section 28 stay, and the
Servo pin does not change.

Checks run on Linux x86_64 with no emulator:

- `./gradlew testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest assembleRelease`: passed; 562 unit tests, 0
  failed. `MediaNetworkTest` runs OkHttp against local MockWebServer
  instances (https through okhttp-tls) with an injected resolver. Lint's
  only new findings are the expected "newer version available" warnings
  for OkHttp 4.12.0, which Media3 1.11.1 is built against.
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`:
  Media3 1.11.1, OkHttp 4.12.0, Okio 3.6.0 and Guava 33.4.8-android
  (raised from Media3's 33.3.1-android by the constraint) with
  failureaccess 1.0.3 and jspecify 1.0.0; no checker-qual or jsr305.
  Licensee reports every library in the release APK as Apache-2.0.
- APK size, built without the native engine before and after the
  change: debug 35,247,662 to 43,842,237 bytes (+8.6 MB), unsigned
  release 27,255,370 to 34,189,899 bytes (+6.9 MB), nearly all of it
  dex (R8 is off). That is about twice VIDEO.md's estimate.
- `python3 scripts/check_docs.py` and `git diff --check`: clean.

[Engine run 40748590962](https://github.com/L-K-M/Combray/actions/runs/40748590962)
at `d95d8f9` on the API 30 x86_64 emulator: 139 of 140 fixture checks
passed. Media3 decoded and drew the clip (the green and blue pixel
checks of `aPageVideoPlaysAfterATapWithoutServosCookie`), the sheet,
format error, direct link, audio (its position advanced under
`-no-audio`), refusals and the accessibility checks passed.
`aRedirectIsFollowedOnlyToAWebAddress` failed: no Play notice in 240 s.
The tab still showed the previous check's `data:` page, and the new
page's media report was credited to it (the load overtook the commit),
where the policy refuses local media from a public page. The fix holds
such reports while a page the app asked for is pending (section 13,
"No audio or video"); `BrowserCoordinatorTest` covers it, and both new
cases fail without it. [Engine run 40757033391](https://github.com/L-K-M/Combray/actions/runs/40757033391)
at `6e2e35f` then passed all 140 fixture checks, the redirect check
included. The first review round's changes after it (the own-host
exception never reaching this device or the local link, a `blob:` page
as secure as its maker, the player's snapshot on stop and start, and a
notice when Play outlives its page) go through the PR's next Engine
run. [Engine run 40780880291](https://github.com/L-K-M/Combray/actions/runs/40780880291)
at `ee3cab6`, the head that merged, built both native ABIs and passed all
142 fixture checks. In the C1 runs, `aDirectLinkPlays` reported
`direct link body: requests=1 read in full=1`: Servo's navigation read
the 512 KiB slow fixture to the end without the player, so a direct link
is fetched twice.

On 29 October 2026 the owner confirmed the rules with one change: home
networks and tailnets are open to any page, and this device and
link-local hosts stay closed to pages that are not on that host
themselves. The own-host exception went with it. The change's unit tests
and Engine run are recorded in
[L-K-M/Combray#156](https://github.com/L-K-M/Combray/pull/156).

### SVG chart rendering (29 October 2026)

The owner reported an inline SVG bar chart on a dark card rendering as
black shapes with no labels, axes or gridlines. A synthetic case
(a chart whose fills, strokes, text and one geometry attribute come
from the page's stylesheets through class rules and custom properties)
reproduced it in the engine tests.

Root cause: Servo rasterizes inline SVG subtrees as images from their
serialized XML, and that serialization carries no document styles.
`var()` in presentation attributes did not parse into presentational
hints at all (the property's value parser rejects it), and class rules,
custom properties and case transforms from the page's stylesheets never
reached the rasterizer, so every paint fell back to the initial black
and the text was invisible on the dark card.

Downstream change in `L-K-M/servo` (PR
[#16](https://github.com/L-K-M/servo/pull/16), commits `fb51aca9dae`,
`3e5952a5ebb`, `a4e02b4d65a`), pin `6d3218a2a94`:

- Presentation attributes parse as CSS declarations (SVG 2 §Styling),
  with the SVG 2 attribute mapping completed for the properties Servo's
  style system implements.
- Each serialization resolves the document's cascade into the clone:
  computed paint, font, text and geometry values (the geometry onto the
  shapes' attributes, which is where the rasterizer reads it), and
  `text-transform` applied to each element's own text.

Evidence (Debian 12 x86_64 host, Rust 1.97.1):

- `cargo test --profile medium -p servo --test webview
  test_svg_presentation_attribute_var_resolves`: passed. Fails on the
  pre-change engine (the hints are dropped and the computed values fall
  back to black).
- `cargo test --profile medium -p servo --test webview
  test_svg_document_styles_paint`: passed. Fails on the pre-change
  engine (bars in initial black, labels invisible) and without the
  on-demand style resolution for `display: none` subtrees (grouped
  marks and labels unstyled).
- `./mach test-tidy`: no new findings (the co-author findings on older
  history commits remain).
- Not run: `./mach test-wpt tests/wpt/tests/svg/` (no desktop build on
  the development host) and device checks (this is a pin-candidate
  record; the Engine workflow's native builds and fixture checks run on
  the pin PR).

Limitations recorded in `L-K-M/servo`'s `DOWNSTREAM.md`: document CSS
for `transform`, `overflow`, `filter`, `mask`, `clip-path`, `marker-*`,
`font-variant` and `text-decoration` is not resolved into the raster; a
`display: none` from a class rule on a direct child of an `<svg>` stays
unresolved; substituted geometry values must carry units; deeper
subtree mutations still do not invalidate the cached serialization
(pre-existing).

## 30. Device-check timing races (30 October 2026)

Three device checks failed intermittently in the Engine workflow for
timing reasons, not engine behavior. The changes are test-only.

- `PageContrastTest.defaultTextStaysReadableAcrossThemeChangesReloadsAndNewTabs`
  sampled the reloaded page as soon as its title was back
  ([run 41015837139](https://github.com/L-K-M/Combray/actions/runs/41015837139)):
  `text color ffffff`, a frame from before the page painted, not the
  contrast bug's `e8e8e8`. After the reload it now waits for the
  fixture's paint marker, as `open()` does, and a color failure names
  both samples.
- `ScrollTest.aSnackbarOverThePageDoesNotTakeTheButtonsTap` (same run)
  saw "Scroll to bottom" displayed, but the chained click then found no
  such node: under automatic clock advancement the button's 2 s idle
  timer ran out during the engine wait. The check freezes Compose's
  clock once the Undo snackbar shows, through the swipe and the tap,
  then restores automatic advancement.
- `AboutTest.theEngineLicenseCanBeRead` timed out after 60 s
  ([run 41023689990](https://github.com/L-K-M/Combray/actions/runs/41023689990),
  attempt 2). Logcat shows a global configuration change (`h648dp` to
  `h599dp`, app bounds gaining a 136 px top inset) 13 ms after the
  check's activity started. In the passing run the configuration was
  already `h599dp` before the first class. `scripts/check-app.sh` now
  waits until `am get-config` has held for 5 s before the first class
  and records it in `device.txt`. On failure `AboutTest` saves a
  screenshot and `dumpsys window windows`, which `check-app.sh` moves to
  `<results>/AboutTest-failure/`. The logged change arrived with the
  activity launch, so the wait may not prevent a repeat; the evidence
  shows what the window held if it does.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -I -m unittest discover -s scripts/tests
shellcheck scripts/check-app.sh
python3 scripts/check_docs.py
git diff --check
```

The new `check-app.sh` functions were also exercised against a fake
`adb` (configuration changes reset the wait; evidence is copied
byte-for-byte and removed from the device). Device checks were not run
on the development host, which has no emulator; they run in the Engine
workflow on the PR.

## Toolbar tab-swipe checks (30 October 2026)

The existing horizontal `draggable` handled the URL chip and buttons,
but also switched tabs on cancellation, multi-touch, and a vertical drag
that later moved sideways. Each case failed in `BottomBarSwipeTest`
before replacing it with a one-finger, direction-locked release gesture.
The coordinator's tab ordering and engine commands are unchanged.

Ten Robolectric checks pass: swipes starting on the address and every
button in both layouts, physical directions in RTL, idle Home, editing
Home, cancellation, a second finger, vertical intent, short drags and
taps and long-press shortcuts. `TabSwipeTest` adds two real-Servo checks:
a swipe from the address or New tab button selects the previous tab,
preserves the tab count, and paints that tab's solid-color fixture.

Local checks passed on Linux x86_64, JDK 21, SDK 37:

```sh
ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
(cd native && cargo fmt --check && cargo test --lib --locked) # 123 passed after merging main
python3 scripts/check_docs.py
git diff --check
```

Local native/device validation is blocked: `scripts/build-native.sh`
reaches the pinned downstream Servo `6d3218a2a949c433d5445fe2c9eb7b66ba0a1081`,
but the default host compiler lacks C headers (`glslopt`: `assert.h`
not found). `scripts/m0/emulator.sh` exits for missing `libX11.so.6`;
`scripts/check-app.sh emulator-5560 ch.lkmc.combray.TabSwipeTest` reports
no device. The PR's Engine workflow runs native builds and device checks.
No physical-device result is claimed.

[Engine run 41116731775](https://github.com/L-K-M/Combray/actions/runs/41116731775)
built both native ABIs and passed both `TabSwipeTest` checks on the API 30
x86_64 emulator. The suite's only failure was
`BrowsingTest.mediaIsCancelledBeforeDownloadingAndThePageIsTold`.
It reported one media entry instead of two; the swipe checks passed in
15.3 s.
Main's media-report fix (#164, below) was merged into the swipe branch
before rerunning the full gate.

### Swipe feedback (31 October 2026)

The controls now follow a horizontal drag by up to 24dp. A top-edge line
fills in the physical swipe direction toward the existing 56dp threshold.
Release, cancellation, multi-touch or changing tabs clears the line and
springs the controls back. The gesture's coordinate space and page stay
fixed. During the drag the swipe line takes precedence over the loading
line; loading returns on release if the page is still loading.

Four added Robolectric checks cover held progress, its cap, reversal,
spring-back, cancellation, interrupted tab identity and RTL. The first
three failed before the UI change; all 14 toolbar checks pass afterward.
Both real-Servo `TabSwipeTest` cases now also hold the finger down to check
that feedback is visible while the original blue page and tab stay put,
then release and check that feedback clears and the green tab paints.

Local checks on Linux x86_64, JDK 21 and SDK 37 passed:

```sh
ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
(cd native && cargo fmt --check && cargo test --lib --locked) # 125 passed
python3 scripts/check_docs.py
git diff --check
```

The unchanged downstream pin is `331149c771f2ab018a0412c366c810f67197e900`.
`ANDROID_HOME=$HOME/android-sdk scripts/build-native.sh` reaches the native
build but fails in host `glslopt`: the default clang has no C headers,
including `assert.h`. The headless emulator cannot start because
`libtcmalloc_minimal.so.4` is missing; adb lists no device. Native builds
and real-Servo checks therefore require the PR's Engine workflow. No
local emulator or physical-phone result is claimed.

## 31. Missing media notice after a load overtakes its page (30 October 2026)

Symptom: the Play notice never appeared for a page with media, and
`MediaPlayerTest` waited its full 240 s. It happened in
[Engine run 41015837139](https://github.com/L-K-M/Combray/actions/runs/41015837139)
inside the report-only stop-and-start check (logged as
`REPORT stop and start: failed`, counted as passed) and in
[Engine run 41023689990](https://github.com/L-K-M/Combray/actions/runs/41023689990)
attempt 2 (`aRedirectIsFollowedOnlyToAWebAddress`); section 29 records
the same symptom on an earlier pin.

Likely mechanism, read from the code and not yet confirmed from logs
(neither the fixture server nor the engine logged navigations or media
attribution): Servo's embedder takes script, network and constellation
messages through a crossbeam `Select`, which picks among ready channels
at random (`components/servo/servo.rs`, `EmbedderMessageSelector`), so a
page's media load can overtake its document's `LoadStatus::Started` and
its commit. The engine held loads only from a start report. In a new tab
nothing had committed, so the load was dropped; after a page of the same
origin that had already shown its Play notice, the load was credited to
that page, the app's referrer hold did not apply to a same-origin
referrer, and the page's notice was already used.

Change (no Servo pin change):

- `media::PendingMedia` holds loads from the moment the app asks for a
  page (`CreateTab`, `Navigate`, `Reload`) as well as from a start, and
  gives them to the next document that commits: the started one, or,
  before any start, whichever commits first. A request that commits
  nothing gives its loads back to the committed page after 30 s (the
  app's own navigation timeout), a start after 5 s as before; the engine
  ends expired holds on its loop as well as when the next load arrives.
- The app holds every report with a referrer while a page it asked for is
  pending, not only cross-origin ones; the new page's origin keeps them
  at its commit, and when the wait ends without a commit (timeout,
  refusal, Stay, a same-page address) they go to the page on screen.
- Diagnostics: the fixture server logs each request's method and target
  to `fixtures.log` with every `k=` value replaced by `<key>`, in
  logcat's time format; the engine logs (tag `CombrayEngine`, no
  addresses) the loads held until a document committed, loads dropped
  with no committed document, and loads a hold credited to the committed
  document after it ended without a commit; `summary.md` lists every
  failed report-only check and every check slower than 30 s from
  `logcat.txt`. These lines use `info`, because the native library is
  always built `--release` with `log`'s `release_max_level_info`, which
  removes `debug` lines.

Remaining: loads of a page the page itself opened (a link) still rely on
the start report; during a hold, a late load of the page being left is
credited to the new page; and a page's own loads wait while a request
that commits nothing (a 204 answer, a download) is pending.

Host checks, run on Linux x86_64 with no emulator:

- `cargo fmt --check` in `native/`: clean.
- `cargo test --lib` in `native/`: 123 passed, 0 failed. The new
  `media::` case for a load that arrives before a requested page's start
  failed before the change (the load came back at once, to be credited to
  the previous page) and passes after it.
- `./gradlew testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest`: passed; 581 unit tests, 0 failed. The new
  `BrowserCoordinatorTest` case for a same-origin page whose media
  overtakes its commit failed before the change (no Play notice after the
  commit) and passes after it. Lint: no new findings.
- `python3 -I -m unittest discover -s scripts/tests`: 91 passed.
- `python3 scripts/check_docs.py` and `git diff --check`: clean.

`engine.rs` is Android-only, so this host did not compile the engine
changes; the PR's Engine run builds both native ABIs and runs the
emulator fixture checks.

[Engine run 41096726970](https://github.com/L-K-M/Combray/actions/runs/41096726970)
at `91c9d76` built both native ABIs and passed all 142 fixture checks
on the API 30 x86_64 emulator, `MediaPlayerTest` included (10 checks in
80 s). Its summary listed no failed report-only check and two checks
over 30 s that wait on purpose (a page-control question's deadline and a
Karakeep retry). `fixtures.log` held 376 requests, every key replaced.
None of the new engine media lines appeared: no load overtook its
document's commit in that run, none was dropped and no hold expired, so
the race itself is still unobserved; the lines will show it when it
recurs.

## 32. Scroll-position flicker (30 October 2026)

The owner's recording showed brief returns to an earlier scroll position
while the browser controls stayed steady. A synthetic page reproduced it:
scroll from a red section into green, then create and remove an offscreen
iframe. The regression records the center pixel after every paint. Before
the fix, one frame was red, followed by green, without another scroll.

WebRender resets its scroll tree when a scene changes. Servo restored the
offsets for ordinary display lists, but omitted initial empty iframe
scenes and pipeline removals. Downstream [PR #17](https://github.com/L-K-M/servo/pull/17),
code `aaab47173cd`, restores surviving pipelines' offsets in those three
transactions. Pin: `e284adf9fe3d47b71faeb3c0e8bcb6730113b8c8`.
Changed engine files: `components/paint/painter.rs`,
`components/servo/tests/webview.rs`, `DOWNSTREAM.md`.

Local evidence, Linux x86_64, Rust 1.97.1, Xvfb and Mesa software rendering:

- `cargo rustc --locked --profile medium -p servo --no-default-features
  --features bundled,js_jit --test webview -- -C link-arg=-fuse-ld=lld`:
  built with the host's conda clang, zlib headers and linker libraries.
- `test_iframe_changes_preserve_every_scrolled_frame --exact`: failed
  before the fix with a red intermediate frame; passed after it.
- All 34 WebView tests passed, each in a separate test-binary process.
  A single-process run fails because Servo's global options initialize once.
- Independent MiMo V2.6 Pro review: no important defects. The new test
  covers iframe creation/removal; whole-view removal also passed its
  existing pipeline-release check, which does not check transient pixels.
- `scripts/build-native.sh` and APK verification passed locally for
  x86_64, with NDK 29.0.14206865 and four Cargo workers.
- Both ABIs passed in [Engine run 41107468092](https://github.com/L-K-M/Combray/actions/runs/41107468092),
  with 142 device checks passing; a same-origin player launch timed out.
  The interim first-document media hold was superseded by the broader
  request-based fix in [PR #164](https://github.com/L-K-M/Combray/pull/164)
  (section 31). Integration takes that implementation and its regressions
  unchanged. Final combined checks are recorded in
  [PR #159](https://github.com/L-K-M/Combray/pull/159).
- The local software emulator crashed during setup and supplied no device
  proof. Whole-file Servo rustfmt reports pre-existing differences outside
  the changed hunks; the native bridge's fmt and whitespace checks pass.

The phone and live example have not been rechecked. The regression proves
this pipeline-change flicker; other causes remain possible.

## 33. Shadow-root fields and Paste (30 October 2026)

Servo retargets composed focus events to the shadow host for page listeners.
Its default-action dispatch used that host too, so a shadow-root input never
requested its input method. Downstream `df5304686d4` extends the existing
keyboard-default exception to focus events: defaults use the original field,
while listeners keep seeing the host. Composition behavior is unchanged.
Source and regressions: [downstream PR #19](https://github.com/L-K-M/servo/pull/19).
The final pin `331149c771f` also includes the merged scroll fix from section 32.

Combray also discarded context menus without link/image addresses. It now
offers Paste when Servo permits it. Android reads plain clipboard text only
on that tap; URI clips are not fetched and text above 256 KiB UTF-8 is refused
whole. `ContextMenu::paste_text` carries the text with the retained control's
pipeline and node. Servo rejects inactive documents and removed/adopted nodes,
then uses its normal cancellable paste and input-event path. No clipboard
grant remains for another request. Both response boundaries redact diagnostics.
The menu is a non-focusable popup: a focusable dialog hid the IME, blurred the
field and withdrew its own menu. A scoped Back handler dismisses the popup
before page navigation.

Evidence on Linux x86_64, Rust 1.97.1, NDK r29 and API-30 x86_64 emulators
`emulator-5590`, then `emulator-5584` (SwiftShader, software CPU emulation):

- The archived APK `b0a3c689c260e4c89b26897c28412be501cf0c459aafb6e3bc433e893d28c6d1`
  fails the shadow-field keyboard assertion and discards the long-press control
  (`build/input-red-retry/`). The first attempt stopped at painting because
  System UI had an ANR; restarting System UI let both checks reach their targets.
- Paste-only pin `5df20e78f74` builds natively. Its APK
  `8c3bdfd35556b21f706f840a391da76619c551f2609302427e2b32f915c773d9` passes
  the new paste check and all three existing keyboard checks. The shadow-field
  tap reaches the page, then still fails to open the keyboard, isolating the
  missing focus default (`build/input-paste-interim/`, four passed, one failed).
- With the focus fix and dialog menu, six focused device checks passed; paste
  with the keyboard open failed because the dialog triggered an IME dismissal
  (`build/input-final-clean/`). The popup fix passes all seven checks, including
  open-keyboard paste, further typing and accessible targets (`build/input-popup/`,
  APK `0e19d821423cf2f1b6454069f4a1c61d552b3d51cf7d453776e3f02483f745f0`).
- All eight focused desktop tests pass on the combined pin: two shadow-root
  IME cases, ordinary IME show/hide, four paste cases and the redacted Debug
  library test. Run each WebView test in its own process; Servo's options
  initialize once. Three pre-existing embedder-traits doctests fail to compile;
  the focused `--lib` test passes. The desktop setup uses Xvfb `:99`, Mesa from
  the `poltergeist-gl` conda environment and build dependencies from `build`.
  The final host linker supplies conda's `libstdc++.so` by full path because
  Zig maps `-lstdc++` to libc++, which lacks the prebuilt SpiderMonkey symbols.
- Kotlin unit tests, lint, debug app/test assembly, 125 native host tests and
  92 script checks pass. The combined-pin x86_64 native build passes (1m16s
  incremental). Final both-ABI CI and full device results are tracked in
  [PR #166](https://github.com/L-K-M/Combray/pull/166).
- `PasteMenuBackTest` fails without the menu's Back handler and passes with
  it. The device Back check passes in CI at `52cae22`; the local emulator
  crashed before that additional case ran.
- That full CI run passes 149 of 150 checks, including every keyboard/paste
  case. `SaveTest` looked up Save before the bar's merged semantics were
  ready. Its shared tap helper now waits for that node, like other UI checks,
  before clicking. Saving and server-acknowledgment assertions are unchanged.

Reproduction uses `testing/fixtures/server.py`'s nested closed-shadow password
field. `KeyboardTest` checks tap acknowledgement, keyboard opening, IME text,
Back/reopening, and paste both before and after opening the keyboard.
`AccessibilityTest.aPagePasteMenuIsAccessible` covers labels and touch targets.
No live login or physical phone was tested.

```sh
export ANDROID_SDK_ROOT=$HOME/android-sdk
COMBRAY_HOST_CC="$PWD/servo-checkout/host-cc" \
  COMBRAY_HOST_CXX="$PWD/servo-checkout/host-cxx" \
  CARGO_BUILD_JOBS=6 scripts/build-native.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
scripts/check-app.sh emulator-5584 ch.lkmc.combray.KeyboardTest
```

The host wrappers use the existing Zig 0.17 C/C++ wrappers with
`-fno-sanitize=undefined`, as in the earlier builds. The initial native attempt
used the NDK's clang for host code and failed on missing host C headers.
Raw logs remain under `build/`; no generated binaries or screenshots are committed.

## 34. Cloudflare verification (31 October 2026)

The owner supplied an article URL and a screenshot of Cloudflare's
"Performing security verification" page. The article stayed at that page
during an emulator reproduction. This is an open compatibility blocker;
no engine fix is established.

Environment: a fresh Android 11/API-30 x86_64 emulator, `emulator-5590`,
SwiftShader and software CPU emulation (no KVM), 720×1440 at 320 dpi.
The debug APK came from [Engine run 41143423337](https://github.com/L-K-M/Combray/actions/runs/41143423337),
with Servo pin `331149c771f2ab018a0412c366c810f67197e900`.
APK SHA-256: `401388c75e1c8177c1c855b6a7e631481357e2d17251481ba34f7c4c2469a586`.
No integration services or personal history were used.

Observations:

- An independent HTTP request returned `403` with
  `cf-mitigated: challenge`. This identifies the interstitial, not its
  failure mechanism in Servo.
- [Cloudflare's diagnostic](https://debug.challenges.cloudflare.com/),
  version 1.3.4, ran in Combray. Its Automation Check, System Clock,
  Privacy Tools and Server Connection checks all passed. The widget then
  showed "Security Check Failed", error `300031`, and status `failed`.
- Cloudflare documents `300*` as a [generic challenge failure](https://developers.cloudflare.com/turnstile/troubleshooting/client-side-errors/error-codes/).
  It names no missing API. Its [browser support](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)
  guidance lists custom engines and embedded browsers as limited-support
  environments. Neither fact proves the cause of this failure.
- The filtered `CombrayEngine` log reported no engine exception. That log
  is not a complete JavaScript console or network trace.

Reproduction with the same APK installed on a test device:

```sh
adb -s <serial> shell am start -W -a android.intent.action.VIEW \
  -d 'https://blog.adafruit.com/2026/10/27/an-illustrated-online-book-on-hewlett-packard-equipment/' \
  -n ch.lkmc.combray.debug/ch.lkmc.combray.MainActivity
adb -s <serial> shell am start -W -a android.intent.action.VIEW \
  -d 'https://debug.challenges.cloudflare.com/' \
  -n ch.lkmc.combray.debug/ch.lkmc.combray.MainActivity
adb -s <serial> logcat -d CombrayEngine:I AndroidRuntime:E '*:S'
```

Observe the article before opening the diagnostic. Wait for its final
widget result and scroll through the diagnostic results. Capture only
the error and check outcomes when sharing evidence: Technical details
also includes the IP address and session identifiers.

### Phone report and next probe

The owner supplied a phone screenshot on 31 October 2026. All four basic
checks pass, but Technical details shows Turnstile Status `loading`.
No final widget result or error code is visible. This confirms a pending
state at capture time, not the emulator's `300031` result. The browser
string names Servo 0.7.0; the exact APK, device OS, ABI and elapsed wait
are not established. The screenshot's IP and session identifiers are not
part of this record.

In the inspected diagnostic v1.3.4 source, `renderTurnstile()` sets
`loading` before rendering the widget. The Server Connection check passes
when a no-CORS `HEAD` fetch resolves, without checking its HTTP status.
Neither observation establishes that the challenge's scripts, iframe or
requests completed successfully.

Source inspection confirms that Combray does not enable WebDriver or
devtools (`native/src/engine.rs`), forward page-console messages or report
failed challenge resources (`native/src/delegate.rs`). Its generic
`NativeEngine.evaluate` test route is used by the M0 harness, but has no
app UI trigger. A controlled debug harness can use that route for a
bounded error probe; a top-document probe alone cannot observe errors
inside a cross-origin challenge iframe. No ADB device was connected for
this follow-up, so no additional runtime probe was run.

Next: obtain the phone's widget outcome (or confirm it stays pending),
collect the relevant console/subresource failure and compare another
browser on the same network. Isolate a specific engine defect and
reproduce it with a synthetic regression before changing Servo.
Emulation can affect challenge timing and browser signals; it does not
establish the phone's failure mechanism. Native builds and the fixture
suite were not rerun for this documentation-only update.

The owner also reports that the diagnostic's Copy button and manual text
selection do not work. These are deferred to a separate follow-up,
[issue #168](https://github.com/L-K-M/Combray/issues/168), as requested.

## 35. Pull-to-reload, 31 October 2026

Pulling down at the top arms a reload after 80dp beyond touch slop. Release
reloads once; a progress ring distinguishes pulling from ordinary scrolling.
Short pulls, reversal below the threshold, horizontal/upward gestures,
cancellation and multi-touch do not reload. A scroll that only reaches the
top does not arm the same gesture.

DOM scroll metrics are insufficient: section 15 records stale offsets after
painted jumps. Downstream [Servo PR #20](https://github.com/L-K-M/servo/pull/20)
adds `WebView::can_scroll_up_at_point`, reading compositor root, nested/frame
scroll chains and pinch-viewport offsets. Missing hit-test/scroll data means
unknown, not top. CSS `touch-action: none` or `pan-x` also withholds a pull.
Candidate pin: `ef6beb09ab19325d597dd96f758ed359ad33ccce`,
based on the prior pin, with no unrelated engine upgrade or dependency change.
Changed downstream files: `components/servo/webview.rs`,
`components/paint/{paint,painter,pinch_zoom,webview_renderer}.rs`,
`components/shared/paint/display_list.rs` and its `tests/compositor.rs`.
No upstream contact or contribution.

The bridge follows raw touch input without stealing page touches. Release
waits for every input acknowledgement: delayed or out-of-order
`preventDefault` wins. Unanswered input is bounded to 128 outstanding events
and a three-second release deadline. View generation, document and selection
are rechecked after the acknowledgements. The coordinator refuses reloads
under Reader, page controls or an existing load. Progress stays in memory,
without reading Room on each touch movement. Only changed pull progress crosses
JNI; inactive scrolls produce no pull events, and completion/cancellation is
sent once. Four emission-policy regressions failed before that filter and
pass after it.

Touch-down enters Servo before probing the scroll chain, resetting any
preceding fling's cached hit target. This ordering guard is source-verified,
not reproduced locally. Manual check: with the root at top, fling one nested
panel to top, then immediately pull down over another panel scrolled below
top. The second panel must scroll without reloading the document.

The scroll-button fade was already merged in #147 and remains unchanged:
two idle seconds, accessibility-adjusted, followed by a 200 ms fade.

Local checks on Linux x86_64, JDK 21, Rust 1.97.1 and NDK r29:

- Passed: `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
  The new coordinator regression failed before its handler and passes after
  it; coverage includes cancellation, duplicate release, same-address reload,
  generation mismatch and hidden/crashed/loading/Reader pages.
- Passed: `cargo fmt --check`, `cargo test --lib` (138 tests), fixture-server
  script tests, documentation and whitespace checks.
- Passed: all 11 compositor integration tests, including five new query
  checks. The CSS-owned-drag case failed before the guard and passes after it.
  This host lacks pkg-config/FreeType development files, so a standalone
  Cargo driver ran the unchanged test file against the real source crates,
  with bundled FreeType and the installed zlib's development symlink.
- Passed: final-candidate x86_64 `scripts/build-native.sh`, Gradle packaging
  and `verify-apk.py --expected-arch x86_64`. The built library exports
  `Java_ch_lkmc_combray_engine_NativeEngine_reloadPage`.
  CI native/emulator runs are tracked in [PR #171](https://github.com/L-K-M/Combray/pull/171).
  Reproduce the source checks with `cargo test --profile medium -p servo-paint-api --test
  compositor`, then `scripts/build-native.sh` and
  `scripts/check-app.sh <serial> ch.lkmc.combray.ScrollTest`.
  The real-Servo checks cover top release, reaching top during a scroll,
  painted jumps with stale DOM metrics, page-prevented drags and nested panels.

No local ADB device was connected. The local emulator cannot start without
`libX11.so.6`, and KVM is unavailable. Physical-device and live-site checks
were not run. PR checks carry native/emulator evidence before landing.

Initial Engine CI at `5af10c6` built both ABIs and passed 154/155 API-30
emulator checks. The nested-panel check timed out before reporting its
first scroll: the fixture's head script accessed `panel` before its body
existed. A Node lifecycle probe reproduced that `ReferenceError`. The
listener now binds after the panel is parsed, using an explicit element
lookup. Panel overflow stays shorter than the return drag, so fling travel
cannot invalidate its endpoint assertion. Browser behavior is unchanged.
Follow-up [Engine CI](https://github.com/L-K-M/Combray/actions/runs/41220778947)
at `25a62ce` passed both native ABIs and all 155 API-30 x86_64 emulator
checks. The final follow-up records evidence only; browser and fixture code
are unchanged from that tested revision. [PR #171](https://github.com/L-K-M/Combray/pull/171).

## 36. Scroll-hiding bottom controls (31 October 2026)

Owner request: hide the bottom bar while scrolling down and reveal it while
scrolling up. This also applies to Reader. Implementation defaults keep
Home, Find, editing, page dialogs and enabled accessibility services pinned.
Tab, document and editing-context changes restore controls.

Pages reuse the existing single-finger vertical intent signal; taps,
horizontal gestures and multi-touch do not choose a direction. Scripted
page jumps have no direction signal. Reader uses its list's scroll activity.
Removing the bar frees page space in one viewport resize, not an animated
sequence of relayouts. System insets remain reserved; snackbars and scroll
buttons follow the new page bottom.

A focused JVM regression reproduced Reader's resize feedback: near the
article end, hiding controls enlarges the viewport and clamps the list
backward, falsely reporting an upward scroll and revealing the controls.
Ignoring viewport-change corrections fixes it; the regression failed before
the guard and passes after it. Callback replacement also has coverage.

Local Linux x86_64 / JDK 21.0.13 checks use unchanged Servo pin
`ef6beb09ab19325d597dd96f758ed359ad33ccce`, Rust 1.97.1 and NDK r29:

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
- Eight focused visibility, accessibility and Reader observer checks.
- `cd native && cargo fmt --check && cargo test --lib --locked`: 138 tests.
- `scripts/build-native.sh` for x86_64, then APK verification with
  `python3 -I scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk --expected-arch x86_64`.
- `python3 scripts/check_docs.py` and `git diff --check`.

Real-Servo checks in `ScrollTest` verify hiding/revealing, freed viewport
space, painting after resize, Home/tab resets, same-address reload and
pull-to-reload while revealing hidden controls.
`ReaderTest` checks the same direction behavior with an extracted article.
They compile locally; the [Engine PR workflow](https://github.com/L-K-M/Combray/actions/workflows/engine.yml)
carries runtime evidence for `feat/scroll-hide-bottom-bar` before landing.
Initial CI at `80ef61a` built both ABIs and passed 158/159 emulator checks.
Only the combined hidden-controls pull timed out. Its setup now cancels the
previous fling before jumping to top: one green frame does not prove a
stable top edge. Failure output also distinguishes an unchanged document
from a load that never finished. Follow-up evidence is in [PR #172](https://github.com/L-K-M/Combray/pull/172).

A local software emulator booted after extracting X11 libraries into a
private temporary directory. Local diagnostics stopped at paint/input-focus
setup and did not validate the target gesture; temporary tracing was removed.
KVM and a physical device remain unavailable. No personal history or live
service was used.

## 37. Stable tab ordering (1 November 2026)

The owner reported that bottom-bar swipes reorder tabs. `stepTab` used a
snapshot of last-focus order for three seconds, then rebuilt it after a
pause. Focusing each swiped tab changed the next snapshot. The focused
pause regression failed before the fix and passes afterward.

Swipes and Recent's Open tabs now use creation time, newest first, then tab
ID for equal timestamps. Creation time already persists in Room, so there
is no schema change. The swipe snapshot and timer are removed. Undo returns
a tab to its creation slot; a new background tab joins the order at once.
All, address suggestions, previous-tab switching, close successor, Continue,
retention and renderer eviction keep their last-use semantics. Row times
still show last use. Only All shows last-use day headers, avoiding repeated
header keys when fixed tab order crosses the same day more than once.

Coordinator checks cover pauses, both edges, focus/navigation versus history,
background insertion, both Undo modes, restart and tied creation times.
`TabSwipeTest` keeps the held-feedback checks and adds a three-page walk,
with a deliberate four-second pause, identity/count and painted-color
assertions at both ends. Its obsolete swipe-run reset sleeps are removed.

Local Linux x86_64, JDK 21 / SDK 37 commands:

```sh
ANDROID_HOME=$HOME/android-sdk ./gradlew :core:testDebugUnitTest --tests 'ch.lkmc.combray.core.browser.BrowserCoordinatorTest.aPauseBetweenSwipesKeepsTheTabOrder'
ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_docs.py
git diff --check
```

The Servo pin remains `ef6beb09ab19325d597dd96f758ed359ad33ccce`; native
code is unchanged. Native builds and real-Servo fixtures run in the
[Engine PR workflow](https://github.com/L-K-M/Combray/actions/workflows/engine.yml)
before merge. No local device or physical-phone result is claimed; no
personal history or live service was used.

[Initial Engine CI](https://github.com/L-K-M/Combray/actions/runs/41276183297)
built both ABIs and passed all three tab-swipe cases (159/160 checks total).
The unchanged `ScrollTest.scrollingDownHidesTheBarAndScrollingUpRestoresIt`
failed its final blue-paint assertion: the bottom jump raced the previous
upward fling. Its geometry check now uses the existing gesture cancellation
before jumping, as the top-jump check already does. Touch, resize and paint
assertions remain; browser/native behavior is unchanged. Follow-up CI is
tracked in [PR #173](https://github.com/L-K-M/Combray/pull/173).

## Deadline-driven engine sleep (1 November 2026)

Recovered only #88's idle scheduling and host-test design. Its throttling
changes are superseded: Servo 0.7 hides/throttles whole views, including
later documents and frames. That behavior is unchanged here.

The engine waits on its command channel, which also carries Servo wakeups.
Timed waits retain capture deadlines, deferred capture retries, leave-page
questions, released reload pulls, media holds and failed paints. Media
windows are inclusive; their timer wakes one millisecond after the last
active hold ends. A usable surface retries failed paint after 250 ms;
an absent surface waits for its lifecycle command.

Local Linux x86_64, Rust 1.97.1, unchanged Servo pin
`ef6beb09ab19325d597dd96f758ed359ad33ccce`:

```sh
cd native
cargo fmt --check
cargo test --lib
cd ..
python3 scripts/check_docs.py
git diff --check
```

Two scheduling assertions failed against the extracted polling policy,
then all four passed after removing the idle tick. All 145 native host
checks pass, including three new media-expiry cases. Android builds and
real-Servo fixtures remain merge gates in the Engine PR workflow. This
host has no device or KVM; no local device, idle-CPU or battery result is
claimed. No personal history or live service was used.

### Wake-source audit after PR review

At the unchanged pin, every receiver in `Servo` is built with the supplied
`ChannelWaker`: paint, embedder, network and constellation. Native app
commands use the same `mpsc` sender. Sending before `recv` is safe: the
message remains queued. No native/tab state is mutated from other threads.

| Producer | Wake or owner-thread path |
| --- | --- |
| Surface, resize, input, IME, foreground, profile writes, controls | JNI `send` uses the engine command sender |
| Network and constellation results | `GenericEmbedderProxy::send` enqueues, then calls the event-loop waker |
| Script notifications | `ScriptToEmbedderChan::new` routes to the embedder sender and wakes it; the raw sender clone in `constellation/event_loop.rs` is used only by this wrapper |
| Compositor and renderer | `PaintProxy::send` and `Servo::create_paint_channel`'s IPC callback both wake after enqueueing |
| View delegates | `Rc<RefCell>` queue, owner-thread only, drained after `spin_event_loop` in the same `pump` |
| Capture, Find, tap checks and evaluation results | Owner-thread JavaScript callbacks, drained later in that same `pump` |

`next_timeout` covers pending capture expiry, deferred/touch-quiet retries,
leave-page questions, released pulls, pending-media expiry and failed paint.
All `Instant`/elapsed uses were checked: user-activation eligibility and
paint-error log throttling require no proactive work; the startup clock
only stamps events. Profile scheduling is app-owned and posts a command.

`paint` clears `needs_paint` after a usable context is made current. A
missing context/surface waits for lifecycle commands; `make_current`
failure retains the flag and the bounded retry. No extra native deadline
or wake-less state producer was found. A zero-duration deadline now has an
explicit regression against paint retry. No debug polling clamp was added:
it would hide missed wakeups from the full fixture suite's timeouts.

## Refused Reader links (1 November 2026)

Recovered only #44's rejected-link handling. Reader extraction admits an
http(s) prefix, not a complete address-policy decision: an anchor with
`href="https://"` survives extraction but cannot navigate. The UI closed
the article before checking that decision. It now closes only on acceptance;
external schemes remain outside Reader's supported links.

The real-Servo regression was committed first in [PR #176](https://github.com/L-K-M/Combray/pull/176).
[Initial Engine CI](https://github.com/L-K-M/Combray/actions/runs/41304317615)
built both ABIs. All seven existing Reader checks passed; the new eighth
failed at the retained-article assertion (`Ready` expected, `null` actual),
after confirming the extracted malformed link. The same regression checks
the unchanged address and tab count after the fix.

Local Linux x86_64, JDK 21 / SDK 37, unchanged engine pin
`ef6beb09ab19325d597dd96f758ed359ad33ccce`:

```sh
ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -I -m unittest discover -s scripts/tests -p test_fixture_server.py
python3 scripts/check_docs.py
git diff --check
```

Initial local Kotlin gates and the fixture-server checks pass. Two later
full Engine runs lost the emulator before `ReaderTest` ran; neither counts
as a pass. The branch was then refreshed onto main `370cc68` (engine pin
`4eb8b125d8cc984e0e4d09b69ea183dd0cda2bb7`), which reruns those gates and
full Engine CI before merge. There is no local device or KVM; no local
runtime or physical-phone result is claimed. No personal history or live
service was used.

## 38. Inline CSP nonce properties (2 November 2026)

Validated source: `e4710ec93581bd90cbd3980ccc8af508b1ed2803`, downstream
[PR #22](https://github.com/L-K-M/servo/pull/22), merged as
`8e791033712f825edb4b6b4e7b257f0f940b4047` on `combray/0.7`.
App integration: [PR #180](https://github.com/L-K-M/Combray/pull/180).
This fixes CSP authorization, not the verification failure.

### Reproduction before the fix

A fresh API-30 x86_64 KVM emulator used the diagnostic native APK from
Engine run `41346084773`, pinned to
`ef6beb09ab19325d597dd96f758ed359ad33ccce`. Manual probe run
[41367137326](https://github.com/L-K-M/Combray/actions/runs/41367137326)
found:

- Same-origin and cross-origin iframe message identity passes in light DOM
  and closed shadow roots; all four frames paint their expected colors.
- A worker's deliberate exception reaches the existing trace even when its
  DOM error handler cancels propagation. Missing worker errors are not
  established as the cause.
- Styles with a matching `.nonce` property remain black instead of the
  requested `rgb(17, 34, 51)`, both before insertion and after insertion
  before text. Reading a missing nonce content attribute as `null` is
  correct, not a nonce-hiding defect.
- JavaScript evaluation remains blocked and `wasm-unsafe-eval` works.
- The live diagnostic reaches `failed` after about 70 seconds; the supplied
  article loads. The phone failure still needs direct confirmation.

Independent Chromium run
[41369435474](https://github.com/L-K-M/Combray/actions/runs/41369435474)
applies both nonce-property styles. Its attribute-nonce control, wrong-nonce
rejection, blocked JavaScript evaluation and allowed Wasm compilation pass.
Only synthetic cases and manually supplied public targets were read.
Uploaded captures are encrypted; raw session data stays private.

### Mechanism and pending proof

The shared inline CSP check discarded the cryptographic nonce unless a
nonce content attribute existed. The IDL setter deliberately creates no
such attribute. The candidate reads that slot while retaining nonceability
checks for existing attributes, matching external-script preparation.

Eight downstream WPT assertions cover matching, missing and wrong nonces,
attribute controls, inline scripts and rejection of style attributes.
Using the real WPT harness and HTTP CSP header:

- Unchanged Servo run `41371925127`: five pass, three targeted failures.
- Chromium run `41371923177`: eight pass.
- Patched Servo run `41374714293`: eight pass.

Both ABI builds and all 160 emulator fixture checks pass in Engine run
`41372328020`; routine CI run `41372327945` passes. The former ScrollTest
failure does not recur. Local native host checks pass: 146 tests; notice
checks: 17. The full local script suite timed out after 180 seconds and
is not claimed as passed.

Local `scripts/build-native.sh` succeeds for x86_64 in 67m59s: Linux,
Rust 1.97.1, NDK 29.0.14206865, Zig host wrappers with UBSan disabled,
`CARGO_BUILD_JOBS=1`. Rust formatting, documentation and whitespace checks
pass. Independent GLM review completes one minor-only round with no
important findings after one harness-permission failure. Extra duplicate
parse-error and nonce-slot resynchronization cases are deferred; existing
attribute-bearing nonceability checks remain unchanged.

The patched live diagnostic still fails `300031` after about 70 seconds;
the article loads. This is not a verification fix. A temporary manual-only
branch is tracing CSP/resource failures and frame-local layout, with
private encrypted captures. It must not merge. Diagnostic console and
probe prototypes are excluded from the app integration. The arm64 APK from run
`41372328020` was offered for phone confirmation; no phone result is claimed.

## 39. Toolbar recovery (1 November 2026)

Recovered the interrupted owner request: inline Stop while loading, Reader
otherwise; Back, Save, Close tab, New tab, Recent and Menu; Share in Menu;
an instant menu with upward-drag release selection. The old task stopped
with an unresolved `browser.stopLoading` call: Servo 0.7 exposes no such
embedder API. No UI-only loading flag is accepted as cancellation.

Draft [PR #179](https://github.com/L-K-M/Combray/pull/179) preserves the UI
on current main, including gesture feedback, fixed tab order, pull reload
and scroll-hiding controls. The original worktree remains untouched. After
a session restart removed temporary worktrees, staged UI was recovered from
Git's index; new development and evidence now use persistent directories.

The client routes Stop through the coordinator, engine port and JNI to the
Servo owner thread. Replies carry view generation, request and retained
document identity. Tests cover delayed view creation, callback/commit order,
new navigation, equal-address replacement, renderer replacement, failure and
retry. A reply ahead of its retained document waits for the commit to become
durable before ending loading; two regressions failed before this guard.
Restart must not reopen a cancelled target under a completed Stop. Stopping
does not close the logical tab or change an existing committed URL.

Local Linux x86_64, JDK 17, SDK 37 results:

- Both new wire-message tests failed before decoding was added. Engine's
  47 tests and core's 454 tests then passed.
- Thirteen real-composable Menu tests and fourteen swipe tests pass. Two regressions first
  reproduced actions after a second finger and after Back while dragging.
  An overlay press now cancels an existing drag; dismissing invalidates its
  handler, so a held finger cannot reopen or activate a dismissed menu.
- Four fixture-server checks pass, after the new endpoint first returned
  404. Per-check gates isolate delayed responses. The page heartbeat reports
  actual `document.readyState`; changing native UI state cannot complete it.
- Native host tests pass: 147 tests after integrating #178, using an isolated Cargo target. This
  checks pure bridge logic, not the Android-only Servo adapter.
- `assembleDebugAndroidTest` passes, including three real-Servo Stop checks.
  These checks are compiled, not run locally. They require retained article
  content, a cancelled blocking script, and navigation after initial Stop.

Home retains its labeled Menu action; a missing-label test failed before
sharing gesture handling between icon and labeled buttons. A thirteenth
Menu test covers Home's drag path. Four activity tests cover native-page
drag selection, Share placement, Home's label and second-finger cancellation.
All seven new activity checks passed on the API-30 x86_64 emulator at
`f0ad26a` (Engine run `41377744676`). The normal full local gate
lost its Gradle daemon. The same tasks then passed with bounded Gradle memory:
651 JVM tests (app 150, core 454, engine 47), lint and both APK assemblies.
The coalescing candidate passes 652 JVM tests (app 151, core 454, engine 47),
lint, both APK assemblies, 147 native host checks and 17 notice checks.

Commands:

```sh
JAVA_HOME=$HOME/jdk17 ANDROID_HOME=$HOME/android-sdk ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
JAVA_HOME=$HOME/jdk17 ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest --tests ch.lkmc.combray.ui.ToolbarMenuTest :app:assembleDebugAndroidTest
python3 -I -m unittest discover -s scripts/tests -p test_fixture_server.py
(cd native && CARGO_BUILD_JOBS=2 CARGO_TARGET_DIR=$HOME/.cache/combray-toolbar-host-target cargo +1.97.1 test --lib)
python3 scripts/check_docs.py
git diff --check
```

Downstream [draft #21](https://github.com/L-K-M/servo/pull/21) now supplies
candidate `4eb8b125d8cc984e0e4d09b69ea183dd0cda2bb7`. Its `efd55be7` merge
preserves Stop head `a3badf8` and published nonce handling `8e79103`.
All 45 serial SDK checks pass: 36 existing WebView checks, seven Stop checks
and two input-completion regressions. Android requalification remains required.
A real-Servo callback test
first failed with `RefCell already borrowed`; taking the callback before
invoking it fixed that failure. Seven regressions passed on `a3badf8`:
retained partial content, pending navigation and iframe cancellation, reusable
initial Stop, late approval, close and callback reentry. A separate, explicitly
enabled failure probe suppresses fallback creation: it timed out before the
guard and then returned `DocumentUnavailable`. The shipping call is restored;
the manual probe stays ignored without that documented fault setup.

The SDK uses process-global options, so its normal runner is Nextest. A plain
multi-case `cargo test` fails initialization. A parallel `28e41c8` Nextest run
hit the existing 20-second timeout; `--test-threads 1` passed all seven on that
head and the guarded candidate without changing assertions or timeouts. Later-navigation checks wait
for a new document, not late completion from the stopped one.

Host: Linux x86_64, Rust 1.97.1, Zig 0.17.0, Nextest 0.9.150 and Mesa 22.3.6.
Debian 12 runtime packages were extracted into an isolated test sysroot; no
system packages were installed. Local linker wrappers supply font libraries
and SpiderMonkey's GNU C++ runtime. Reproduction:

```sh
cargo +1.97.1 nextest run --cargo-profile medium -p servo --test stop_loading --test-threads 1
```

At `f0ad26a`, both native ABIs passed and actual GLM source reviews completed
without a confirmed blocker: two SDK rounds at final head `a3badf8`, one app
round. The full emulator suite failed: 167 passed, two failed. The narrow-bar
check found a doubled chip gutter; a new JVM regression failed first and
passes after removing that gutter. The other failure is
`ScrollTest.aPullRevealsHiddenControlsAndStillReloads`: no reload request
reached the fixture. A slow allowed/prevented touch fixture isolates that path.

At `1c8844d` (Engine run `41386360583`), both ABIs passed; 170 of 171 emulator
checks passed, including narrow geometry, hidden-controls reload, Stop and Menu.
The slow allowed drag failed. It armed at the top, then received
`prevented=false, dispatch_failed=true, decision=Cancelled` after 90 ms;
release was still at the top. Servo had coalesced a move, not seen a page veto.

The candidate reports intentional coalescing as `Consumed`, while real target,
shutdown and channel failures remain `DispatchFailed`. The dispatched move
still carries the page veto. The native failure gate is unchanged. A desktop
consumed-result assertion failed first; both new regressions now pass, also
checking page refusal and genuine hit-test failure. Temporary tracing is removed.
The 100 ms handler delay, assertions and existing timeouts remain unchanged.

### Final qualification (2 November 2026)

[Engine run `41393285172`](https://github.com/L-K-M/Combray/actions/runs/41393285172)
passed both Android ABIs and all 171 API-30 x86_64 emulator checks at
`04e9f9d3559f82c5b52dae31aa8bc127d6589c1f`, pinned to the candidate above.
Both slow-drag cases, three Stop cases and four Menu cases executed and passed.
No assertion or timeout was weakened.

SDK [#21](https://github.com/L-K-M/servo/pull/21) merged as `f1b1054`;
app [#179](https://github.com/L-K-M/Combray/pull/179) merged as `7162cf1`.
Actual final-head GLM reviews found no confirmed blocker: three SDK rounds,
two app rounds. The hosted hybrid review also completed; its major claims
were disproved by the shared dismiss callback, retained rows and compiled
activity tests. Minor cleanup is deferred under the review stopping rule.

No local device, physical-phone, battery or idle-CPU result is claimed;
no personal history or service was used. Reader, PDF and compatibility holds
are unchanged.

## 40. Verification stall and automation signals (2 November 2026)

Validated source: `6fbfc3d70ad83df5443445d3ff4c43871aae41dd` on `combray/0.7`, which merges downstream
[PR #23](https://github.com/L-K-M/servo/pull/23) (message origins),
[PR #24](https://github.com/L-K-M/servo/pull/24) (console formatting) and
[PR #25](https://github.com/L-K-M/servo/pull/25) (WebDriver helpers).
App integration: [PR #182](https://github.com/L-K-M/Combray/pull/182).
This fixes the stall that §34 and §38 reported as `300031`. Whether the
owner's phone then passes the checkbox is not yet established.

### Reproduction

Desktop servoshell, headless, with Combray's prefs, the Android user agent
and WebDriver, against Cloudflare's diagnostic. Official v0.7.0 reproduces
the stall: the widget's watchdog reports `300031` after about 50 seconds.
A userscript logging the widget's protocol messages showed the challenge
stopping before its interactive step. A userscript that delivered port and
worker messages with an empty origin let it continue.

### Mechanism

The challenge runs a worker built from a blob URL. That worker evaluates
only messages for which `isTrusted` holds, `origin` is the empty string
and `source` is null. `Worker.postMessage` and `MessagePort.postMessage`
run the HTML port post message steps, which set no origin. Servo set the
sender's origin, so the worker discarded every message and the widget
waited until its watchdog gave up. The engine change leaves `origin`
empty for both; `BroadcastChannel` keeps the sender's origin, as its
steps require. A five-case downstream WPT test fails its three port and
worker cases on v0.7.0 and passes with the fix; the upstream
`postMessage_event_properties.htm` no longer fails.

With the fix and no workaround, the diagnostic reaches its interactive
checkbox under mouse and touch input and with Android, desktop, Servo-only,
Chrome and Firefox user agents. Clicking it then fails with `600010` in
every variant. Official headless Firefox 159, driven by WebDriver on the
same host with `navigator.webdriver` hidden, fails the same way. That
environment cannot judge the click, so it is no evidence of a further
Servo defect.

### Automation signals

Two Servo behaviours would let a page tell Combray apart from an ordinary
browser on a phone, where the environment no longer masks them:

- Console formatting ran page script: getters, proxy traps, `toString`,
  `toJSON` and numeric conversions, plus devtools previews built with no
  client attached. Bot checks log trap objects to detect open developer
  tools, and the diagnostic logs such objects. The fix formats from
  property descriptors and engine state, and builds devtools previews only
  for an attached client. A 26-case WPT test fails 24 cases on official
  v0.7.0, two of them because it lacks `console.table` and
  `console.dirxml`, and passes with the fix. Making the console fully inert did not change the
  desktop click result, so this is a hardening measure, not a proven cause.
- `window.webdriverElement`, `webdriverFrame`, `webdriverWindow` and
  `webdriverShadowRoot` were enumerable on every global. They now exist
  only when servoshell runs WebDriver. A five-case WPT test fails all five
  on v0.7.0 and passes with the fix.

### Checks

- The merged tree is the tree built and tested locally:
  `./mach build --profile medium --media-stack dummy`, with the `script`
  crate at opt-level 0 for the final build because the host's 16 GiB
  container limit kills it at opt-level 2.
- WPT with that build: 186 tests as expected. They cover the three new
  downstream tests, `console/`, `webmessaging/` and the worker
  event-properties test. WebDriver's Execute Script argument and node tests
  pass. An earlier origin-only build ran `webmessaging/` and `workers/`:
  499 tests, 498 as expected, plus one unexpected pass that the change cannot
  affect ([PR #23](https://github.com/L-K-M/servo/pull/23)).
- Devin SWE-2 and Pi MiMo V2.6 Pro reviewed each change; the PRs record the
  findings and what was done with them.
- The Engine workflow (both ABIs and the emulator fixtures) and routine CI
  run on the app integration PR.

Limitations: all engine evidence is from desktop servoshell. The host's
network and headless WebDriver environment fail the checkbox for every
browser tried. The phone result is pending, with the arm64 APK from the app
integration PR's Engine workflow.

## 41. Flex rows wider than the screen (4 November 2026)

Validated source: `825ef1f0356fd4adb6184207ffdb84f572241ef0` on
`combray/0.7`, which merges downstream
[PR #27](https://github.com/L-K-M/servo/pull/27) onto `6b44b9b2a43`. That
base also brings the HTTP/2 header list limit and the inert PDF viewer
document. The merge differs from the tested PR commit `dbf53b7e4e1` only in
a comment of the new test. An article the owner opened on the phone
scrolled sideways instead of fitting the screen.

### Reproduction

Desktop servoshell at 745 CSS px laid the article out 1183 px wide;
Chromium 156 fits it to 745 px. The page's main column is a grid with one
`auto` track. A figure caption in it is a flex row: a label that cannot
shrink (an icon and a few words) and a long sentence. A synthetic page with
that structure, the `fit-width.html` fixture, lays out 1268 px wide in a
412 px viewport; Chromium keeps it at 412 px.

### Mechanism

Servo computed a single-line flex container's min-content size with the
flex-fraction ideal algorithm of CSS Flexbox §9.9.1.1. The specification
says that algorithm is not Web-compatible and allows the Web-compatible
one of §9.9.1.2. Under the ideal algorithm, an item whose desired flex
fraction is zero, such as a single word, an icon or the label above, makes
the line's chosen fraction zero. Every other item then contributes its
flex base size, its max-content width. The caption's min-content width
became the whole sentence on one line, and the grid track grew to it.

The engine change sums the items' min-content contributions instead, each
clamped by the item's flex base size and min/max main size as §9.9.3 says.
Multi-line containers and the max-content size are unchanged.

With the page fitting, its byline showed a second defect. In a wrapped flex
row with column gaps, v0.7.0 counted the gaps twice when deciding whether a
line's items shrink, so each label broke onto two lines although the line
had room. The candidate backports upstream's fix (`f80ad0305e2`).

### Checks

- A seven-case downstream WPT test fails five cases on the previous engine,
  with the widths the ideal algorithm predicts, and passes all seven with
  the fix. The upstream `gap-006` tests and `flexbox-column-row-gap-001`
  pass with the backport.
- WPT `css-flexbox`, `css-grid`, `css-sizing`, `css-tables` and `css-align`
  (5192 tests) with the previous and the candidate engine. Twelve results
  newly pass: the new cases, the gap tests and two compat cases, among them
  Chromium's case for this bug in `row-compat-001.html`. Ten flex-fraction
  subtests of `row-005.html` and `row-008.html` newly fail; Chromium 156
  fails all ten too. Nothing else changed beyond load flakes in the
  baseline run.
- The article and the synthetic pages match Chromium's widths, byline
  included. `FitWidthTest` loads the fixture on the device and requires a
  page no wider than its viewport.
- The fixture's sentence is over 2500 CSS px wide on one line, so the
  check also holds at tablet widths: at 1280 px the previous engine lays
  the page out 3159 px wide, the candidate and Chromium 1280 px.
- Reviews: Devin SWE-2 and two GLM rounds on the downstream PR; their
  findings and the responses are recorded there.
- Builds: `./mach build --profile medium --media-stack dummy` on macOS
  arm64. The Engine workflow builds both Android ABIs and runs the emulator
  fixtures on the app PR.

Limitations: engine evidence is from desktop servoshell and the API 30
emulator. The phone result is pending.

## 42. Frozen pages after a script thread panic (4 November 2026)

Validated source: `7c7e2069db9eb6c90e34333cc43928acb5390c2b` on `combray/0.7`,
which merges downstream [PR #28](https://github.com/L-K-M/servo/pull/28) onto
the §41 source `825ef1f0356`. The "before" evidence below comes from the pin
before §41, `6fbfc3d70ad`; neither has the port fixes.

### Report and reproduction

The owner reported an article page (not named here) that did not scroll and
sometimes crashed Combray. With the CI arm64 APK of `6fbfc3d70ad` on an arm64
API 36 emulator:

- 2 to 6 seconds after the page loads, a script thread panics:
  `close_message_port called on an unknown port` (`globalscope.rs:854`).
  The page's bot-detection script transfers a `MessagePort` and then calls
  `close()` on the object it keeps.
- The page stays on screen, but swipes move only the bottom bar; later frames
  lose the page's text. "This page stopped working" never appears.
- Served through a local proxy that leaves out that one script, the same
  build loads the page and scrolls it to the footer. The frozen page is the
  panic's consequence, not a layout or input problem.

Synthetic case: `const {port1} = new MessageChannel(); window.postMessage("x",
"*", [port1]); port1.close();`, or the same with `MessagePort.postMessage` or
`Worker.postMessage`. Desktop servoshell at `6fbfc3d70ad` crashes on all
three.

### Mechanism

- v0.7.0 panics when `close()` reaches a port its global no longer manages.
  Upstream fixed this on 4 November 2026 (`20b7ad33607`).
- Even without the panic, closing the object a transfer leaves behind
  disentangled the channel: the remote port got a `close` event and stopped
  delivering to the transferred port.
- Servo learns that a script thread died only from a panic report sent by its
  own logger (`Servo::setup_logging`). Combray installs Android's logger, so
  the report never reached the constellation: no crash page, no
  `notify_crashed`, and the dead page kept its last frame.

### Changes

- Downstream: the upstream fix, cherry-picked; `close()` on a detached port
  does nothing, as in Chromium and Gecko; `Servo::panic_reporter` returns a
  `PanicReporter` whose `report_panic` sends the panic report from an
  embedder's own panic hook.
- Here: the panic hook reports panics on Servo's threads through that
  reporter while Servo runs (`PanicAction::ReportAndUnwind` in
  `native/src/panic_policy.rs`). Panics Servo recovers from stay unreported;
  an engine thread panic outside a recoverable operation still aborts. Servo
  then crashes the thread's pages, and the app shows its existing "This page
  stopped working" notice with Reload. Servo's default options exit the
  process once a reported panic reaches the constellation (`hard_fail`, meant
  for test runners); Combray turns that off, as servoshell does by default.

### Checks

- Downstream, desktop servoshell (`--profile medium`): the new WPT test
  `messageport-close-after-transfer.html` crashes at `6fbfc3d70ad`; with
  only the backport its channel subtest times out; with both changes it
  passes. `webmessaging/`, the upstream crash test and the port-origin test:
  165 as expected. `streams/transferable/`, the worker event-properties test
  and `structured-clone/`: 18 as expected. The validated source, rebuilt after
  merging the flex change: the same port tests with `streams/transferable/`,
  162 as expected. The new `panic_reporter`
  integration test passes, and fails after its 30-second bound when the hook
  does not call `report_panic`; the existing `crash` test passes. No new
  `./mach test-tidy` findings.
- Site check against Chromium: the reported page is `crash` at `6fbfc3d70ad`
  and `ok` with the change.
- Here: `cargo test --lib` (149 passed) and `cargo fmt --check` on the host.
- Android, arm64 API 36 emulator, CI-built APKs of a temporary probe (the
  `PanicReporter` commit without the port fixes, with this hook): the page's
  panic now reaches `handle_panic` (`hard_fail=false`, one WebView, two
  pipelines), the delegate gets `notify_crashed`, and the app shows "This page
  stopped working" with Reload instead of the frozen page. Reload replaces
  the renderer; that engine panics again and the notice returns, with the
  process alive throughout. The probe's x86_64 fixture checks all passed.
- New device check: `BrowsingTest#closingATransferredMessagePortKeepsThePageRunning`
  opens the fixture `/port-close.html`, which transfers a port, closes the
  object left behind and expects a message through the channel (`PC:ok`).
  Desktop servoshell at `6fbfc3d70ad` panics on it; at this pin it reaches
  `PC:ok`. On the emulator it runs in this PR's Engine workflow with the other
  fixture checks.

## 43. HTTP/2 response header list limit (4 November 2026)

Validated source: `3423e2b538029a6c5585b7244ecce281dbdceaea` on `combray/0.7`, which merges downstream
[PR #26](https://github.com/L-K-M/servo/pull/26) on top of the §40 source.

### Reproduction

hyper's HTTP/2 client advertises and enforces a 16 KiB `SETTINGS_MAX_HEADER_LIST_SIZE`
(RFC 9113, section 6.5.2). A server that negotiates `h2` and answers with a decoded header list
above that (synthetic case: ten 10 KiB headers) has its stream reset with `PROTOCOL_ERROR`; the
fetch fails with `client error (SendRequest)` and a navigation shows Servo's network error page.
The same response over HTTP/1.1 loads. Servo now sets the limit to 256 KiB, Chromium's value.

### Checks

- Downstream, on desktop servoshell: the new `servo-net` test
  `fetch::test_fetch_http2_response_with_large_header_list` fails before the change and passes
  after it; `servo-net` integration tests 392 of 393 (the remaining `test_filemanager` failure
  is the same on the previous pin and passes alone); fetch WPT `api/basic` and `api/headers`
  167 as expected; no new `./mach test-tidy` findings; independent review without findings.
- Here: Combray takes this fix with `6b44b9b2a`, the PDF viewer merge of §44, whose first parent
  is `3423e2b53`, and with the §41 and §42 pins built on it; `native/Cargo.lock` and the
  notices' content are unchanged (§44). Host tests, both native ABI builds and the x86_64
  emulator checks run in the Engine workflow of the PRs that set those pins; no local device or
  phone result is claimed.

## 44. PDF viewer document (30 October 2026)

The engine change is `6b44b9b2a43137c527f8fa79fdb306813b8c9e20` on
`combray/0.7`, the merge commit of
[L-K-M/servo#18](https://github.com/L-K-M/servo/pull/18)
(`engine/pdf-viewer-document`), merged on 4 November 2026; upstream base
still v0.7.0. Section 42's pin `7c7e2069db9e` (section 45 moves past it) adds only
the flex sizing fix of
[L-K-M/servo#27](https://github.com/L-K-M/servo/pull/27) (section 41) and
the port close and panic report fixes of
[L-K-M/servo#28](https://github.com/L-K-M/servo/pull/28) on top of it:
`git diff 6b44b9b2a 7c7e2069d` touches `components/layout/flexbox`,
`globalscope.rs`, `messageport.rs`, Servo's `lib.rs`, `servo.rs` and
panic reporter, their tests, WPT metadata and `DOWNSTREAM.md`, no PDF,
parser or document file and no `Cargo.lock`, `Cargo.toml` or toolchain
file. Main set those pins with the viewer's preferences still unset, so
the viewer turns on with this section's app change. The parents of `6b44b9b2a` are `3423e2b53`,
`combray/0.7` with the HTTP/2 header-list fix of
[L-K-M/servo#26](https://github.com/L-K-M/servo/pull/26) (section 43)
over `6fbfc3d70` (the engine fixes of sections 32, 33, 35, 38, 39 and
40), and `71b94369b`, #18's head and this section's previous pin.
`git diff 3423e2b53 6b44b9b2a` is exactly the 12 files of the PDF
change and `git diff 71b94369b 6b44b9b2a` only the HTTP/2 fix
(`DOWNSTREAM.md`, `components/net/connector.rs`, `test_util.rs` and
`tests/fetch.rs`), each with the same patch ID as its side over the
merge base `6fbfc3d70`, so the merge adds nothing of its own.

#18 merged on the owner's waiver of the independent review that
L-K-M/servo's `AGENTS.md` requires, for its commits after `494d7f4b1`
([PR comment](https://github.com/L-K-M/servo/pull/18#issuecomment-6640382806)).
GLM-5.3 reviewed and approved the branch up to `494d7f4b1`; the later
commits were reviewed only by the model that wrote them.

The branch's first-parent history
(`git log --oneline --first-parent 3423e2b53..6b44b9b2a^2`):

- `71b94369b` Docs: note the PDF viewer's charset follow-up
- `80540fca2` Servo: accept no Referer from the PDF viewer in its test
- `a0cbb4e55` Docs: list the PDF viewer policy container commit
- `42ab59b22` Script: give the PDF viewer a new policy container
- `d3ff19f14` Merge combray/0.7 into the PDF viewer document branch
- `ee5d4c625` Script: narrow the navigations that abort a PDF viewer load
- `496f63151` Docs: describe Stop for the PDF viewer in DOWNSTREAM.md
- `65f0f01c1` Script: fail a PDF viewer load that Stop aborts
- `dab4d6a8b` Merge combray/0.7 into the PDF viewer document branch
- `494d7f4b1` Merge combray/0.7 into the PDF viewer document branch
- `05027c941` Docs: list the PDF viewer document in DOWNSTREAM.md
- `5c92900d5` Servo: test the PDF viewer document
- `92e06d5d5` Net: report the bytes in-memory blob URL entries hold
- `ec2452bc4` Script: refuse PDF bodies larger than the viewer accepts
- `16ec03de4` Script: release a PDF viewer's body when it is discarded
- `273a0430c` Script: show top-level PDFs in a viewer document
- `0bce9843c` Config: add the Combray PDF viewer preferences
- `d1e33448d` Add the GLM review workflow

`0bce9843c` to `05027c941` are the engine change, `65f0f01c1` to
`ee5d4c625` its handling of Stop and `42ab59b22` to `71b94369b` its
isolation from the PDF response (below). `d1e33448d` adds the
downstream GLM review workflow (`.github/workflows/zai-code-review.yml`,
owner decision of 29 October 2026, recorded in its `DOWNSTREAM.md`) and
is no engine build input. The merges `494d7f4b1` (of `combray/0.7` at
`ef52ceadf57`), `dab4d6a8b` (at `f1b105495`) and `d3ff19f14` (at
`6fbfc3d70`) changed no PDF line: over `combray/0.7`, `dab4d6a8b` has
the same added and removed lines as `05027c941` over its base
`6d3218a2a94`, and `d3ff19f14` the same as `ee5d4c625` over
`f1b105495`. Before the merge this section pinned `05027c9413a`,
`494d7f4b13b`, `dab4d6a8bfa`, `ee5d4c625`, `d3ff19f14` and
`71b94369b`.

What the engine change does (its `DOWNSTREAM.md` row has the details):

- With the `combray_pdf_viewer_module` and `combray_pdf_viewer_csp`
  preferences set, a top-level `application/pdf` response becomes a viewer
  document at the PDF's own URL: a fresh opaque origin, Combray's policy
  in place of the response's, and markup that loads Combray's viewer
  module as soon as the headers arrive. Without them, PDFs keep Servo's
  stand-in page, as before.
- The body is fetched once. At its end it moves into the blob URL store
  under the document's origin, and the root element's `data-pdf-state`
  (`loading`, `ready`, `too-large`, `failed`), `data-pdf-src`,
  `data-pdf-length` and `data-pdf-limit` tell the viewer where it is.
- A body over `combray_pdf_viewer_max_bytes` is refused at the headers
  or mid-stream, and the navigation fetch is cancelled. Aborting a viewer
  while its body still arrives (Stop, `window.stop()`, a navigation the
  page starts) drops the body, cancels the fetch at once and sets
  `data-pdf-state` to `failed`, which the viewer shows as a failed load
  (`65f0f01c1`). A body shorter than its
  `Content-Length` is `failed`.
- The viewer gets a new policy container (`42ab59b22`): the response's
  `Referrer-Policy`, embedder policy and `Content-Language` no longer
  apply, so a PDF served with `Referrer-Policy: unsafe-url` cannot make a
  font or a navigation the viewer starts send the PDF's full URL across
  origins. `80540fca2` lets that test also accept no `Referer`, which the
  specifications give for the viewer's opaque origin.
- The blob is released when the document unloads unsalvageable or leaves
  session history, so Back to a viewer in the back-forward cache reads
  the same blob without a request.
- PDFs in frames, and popups of scripts-sandboxed documents, keep the
  stand-in page. `Content-Disposition: attachment` is ignored, so
  attachment PDFs open in the viewer.

Engine behaviour the viewer works around (source at the pin, found in
review of the Combray PR):

- `Worker.terminate()` does not end an idle worker. `Worker::Terminate`
  (`components/script/dom/workers/worker.rs`) only sets the closing flag
  and requests a JavaScript interrupt, which fires only while script
  runs; it sends nothing. A worker waiting in `run_worker_event_loop`
  (`abstractworkerglobalscope.rs`) keeps its runtime, here PDFium's wasm
  heap (at least 17.75 MiB) and the PDF, until its document is discarded,
  and a document in the back-forward cache is kept. `postMessage` still
  sends after `Terminate`, and the woken worker's `handle_mixed_message`
  (`dedicatedworkerglobalscope.rs`) sees the flag, leaves its loop and
  clears its runtime, so the viewer posts an empty message after
  `terminate()`. Follow-up for L-K-M/servo, not done here: make
  `Terminate` send the worker its `WakeUp` message itself.
- A canvas resized to 0 × 0 cannot be painted, so
  `CanvasState::set_bitmap_dimensions` sends no `Recreate`, and the
  canvas paint thread keeps the old pixmap and WebRender image until
  garbage collection drops the context. The viewer shrinks a page's
  canvas to 1 × 1 instead.
- A pipeline in session history gets `ResizeInactive`, which updates its
  viewport without a `resize` event, and Back queues only `pageshow`. The
  viewer lays its pages out again whenever it opens the PDF.

Evidence for the engine change is in #18: 27 tests in
`components/servo/tests/pdf_viewer.rs` and its reviews. At `ee5d4c625`
and again at `d3ff19f14`, its `pdf_viewer` (25), `stop_loading` (7) and
`input_events` (2) suites pass; at `71b94369b`, `pdf_viewer` (27),
`stop_loading` (7) and `input_events` (2) pass. At `d3ff19f14` and
`71b94369b` each `stop_loading` and `input_events` test ran in its own
process. The Stop test failed on `dab4d6a8b` before the fix, and the
referrer-policy and `Content-Language` tests failed on `d3ff19f14`
before `42ab59b22`; the referrer-policy test as `80540fca2` left it
still fails with `42ab59b22`'s fix reverted. These suites did not run
again on the merge commit here. On this Linux x86_64 host, for the pin:

- `git diff --name-only 6d3218a2a94 6b44b9b2a`, main's engine fixes,
  the HTTP/2 fix and the PDF change together, touches no `Cargo.toml`,
  `Cargo.lock`, toolchain, default resource or license text; Servo's
  `Cargo.lock` is byte-identical at `6d3218a2a94`, `ef6beb09ab1`,
  `05027c9413a`, `494d7f4b13b`, `4eb8b125d`, `dab4d6a8bfa`,
  `ee5d4c625`, `6fbfc3d70`, `d3ff19f14`, `71b94369b`, `3423e2b53` and
  `6b44b9b2a`.
  `cargo test --lib` in `native/` against the pinned checkout: 157
  passed, and `native/Cargo.lock` did not change. Host builds compile
  only the pure modules (Servo is an Android-only dependency), so this
  checks the lockfile against the checkout, not the engine API. The
  notices' content is the same; only their input hash of
  `scripts/m0/servo-pin.sh` and the compare link in
  `THIRD_PARTY_NOTICES.md` follow the pin. At `71b94369b` and again at
  `6b44b9b2a`, `scripts/generate-notices.py` (`cargo about` 0.9.2, NDK
  r29) produced exactly that `native.json` and an unchanged
  `Rust-Licenses.html`.
- `scripts/m0/fetch-servo.sh` refused this host's checkout because
  `servo-checkout/servo` is a symbolic link to a checkout elsewhere on the
  same disk (space). Its fetch and checkout steps were run by hand on that
  checkout: `git fetch --no-tags --depth=1` of `6b44b9b2a` from a local
  clone of L-K-M/servo, then `git checkout --force --detach` and
  `git clean -ffd`. Its mach Python preparation was not run; the host
  tests do not need it.

No native build or emulator ran on this host: both native ABI builds,
packaging and the fixture checks are the Engine workflow's evidence on the
PR that sets this pin. Combray turns the viewer on in the same PR
([PDF.md](PDF.md#phases)). On `494d7f4b13b`, Engine runs
[41311662496](https://github.com/L-K-M/Combray/actions/runs/41311662496)
(head `41ca252`) and
[41356434589](https://github.com/L-K-M/Combray/actions/runs/41356434589)
(head `0de24d9`) passed all 173 checks, with no report-only failures.
On `dab4d6a8bfa`, Engine run
[41412055823](https://github.com/L-K-M/Combray/actions/runs/41412055823)
(head `0243cda`) passed all 184 checks, with no report-only failures.
On `ee5d4c625`, Engine run
[41419351117](https://github.com/L-K-M/Combray/actions/runs/41419351117)
(head `98ec510`) passed all 184 checks. On `d3ff19f14`, Engine run
[41508955098](https://github.com/L-K-M/Combray/actions/runs/41508955098)
(head `76bc681`) passed all 185 checks, with no report-only failures.
Engine run
[41881346624](https://github.com/L-K-M/Combray/actions/runs/41881346624)
on `71b94369b` (head `2a5a42f`) was cancelled by the next push before its
emulator job ran. On `6b44b9b2a`, Engine run
[41903858889](https://github.com/L-K-M/Combray/actions/runs/41903858889)
(head `d650c1b`) built both ABIs and passed host tests; on the emulator,
every check through `TabCounterTest` passed, `PdfTest` (10) and
`StopLoadingTest` (3) included. A second after `TabCounterTest`'s app
process ended normally, logcat stops and adb then reported the emulator
offline, with no crash in either log; the remaining suites (`TabSwipeTest`, `TextSizeTest`,
`ToolbarMenuTest` and the process-death checks) did not run. On
`825ef1f0356f`, Engine run
[41952234622](https://github.com/L-K-M/Combray/actions/runs/41952234622)
(head `4c8fb3c`) lost the emulator the same way after `HisterTest`, as
main's own flex sizing PR did once without the viewer; its re-run was
cancelled by the next push. Engine run
[41960527435](https://github.com/L-K-M/Combray/actions/runs/41960527435)
(head `f09199d`, with the fixes from the PR's GLM review) passed all
186 checks, with no report-only failures. The Engine run on the merge of
main that moves the pin to `7c7e2069db9e` is the evidence for that pin.

Open follow-ups for L-K-M/servo: a `charset` on the PDF's
`Content-Type` still sets the viewer document's encoding, and with it
how the viewer encodes URL queries, once the body ends (found by
reading the code, not tested); a load the embedder starts with
`WebView::load` away from a PDF that is still downloading, as
Combray's address bar does, does not abort it (Servo keeps the old
document in session history), so that download continues in the
background up to the cap; and the network layer notices a cancelled
fetch only when the next chunk arrives.

## 45. Requests on a dropped pooled connection (5 November 2026)

Validated source: `0bf44ec270a049eed4a7af96050b97603567680b` on `combray/0.7`, which merges downstream
[PR #31](https://github.com/L-K-M/servo/pull/31) onto `b212be3ebc`. That base adds
[PR #29](https://github.com/L-K-M/servo/pull/29) (inline box content size floored at zero) and
[PR #30](https://github.com/L-K-M/servo/pull/30) (`overflow: hidden` on inline SVG roots) to the
§42 pin; this pin is the first to carry them.

### Report

The owner reported a video page (not named here) that showed "Could not load the requested
page: client error (SendRequest)" with the address bar's warning sign. On an arm64 API 36
emulator, twice each:

- The CI arm64 APK of `6fbfc3d70ad` (main's pin until #185 landed on 5 November 2026 at
  01:53 UTC) shows exactly that screen. The error page keeps the address the navigation
  started from, which is why the bar shows the first host and not the redirect's target.
- The APK of main at `532d88a` (pin `7c7e2069db9`) loads the page.

The page redirects to a host whose HTTP/2 response carries a 19,920-byte header list (measured
with curl and the app's user agent), over hyper's 16 KiB default: the §43 limit, which main has
carried since #185. A phone build from before #185 needs updating; that page needs no further
change.

### Second cause of the same message

`client error (SendRequest)` is hyper-util's error for a request that reached a connection and
then failed before a response. hyper-util sends a request again only when it never reached a
connection. A request written to an idle pooled connection that the server or the network had
already closed therefore fails, although nothing answered it: a server's idle timeout can race
the request, and the socket of an idle connection can die unnoticed while a phone sleeps or
switches between Wi-Fi and mobile data. Servo shows its network error page; Chromium sends the
request again on a new connection. Servo did that too with synchronous hyper (`6079dd4de7`,
2017).

Synthetic reproduction: an HTTP/1.1 server answers the first request on a keep-alive connection,
then reads the next request on it and closes the connection without answering.

- Desktop servoshell at `b212be3ebc`: `Error(SendRequest, hyper::Error(IncompleteMessage))` and
  the error page. Chromium (Playwright's headless shell) sends the request again on a second
  connection and loads the page.
- Android, main's APK at `532d88a`, the fixture `/reused-close.html` after a solid page: the same
  error page, and the fixture counts the request as dropped with no second attempt.

### Changes

- Downstream (PR #31): a request without a body goes out once more, on a client that opens a new
  connection, when its pooled connection failed before any response, and either its method is
  idempotent and the connection closed or was reset, an HTTP/2 `GOAWAY` without error included
  (RFC 9110, section 9.2.2), or the server guarantees it did not process the request: hyper never
  wrote it, or an HTTP/2 `REFUSED_STREAM` (RFC 9113, section 8.7). A `GOAWAY` is no such
  guarantee, because h2 also hands it to a stream it had just opened below the GOAWAY's last
  stream id (found in review). Requests with a body (Servo streams them from their source once),
  failures to connect and other errors, such as an HTTP/2 stream reset for another reason, fail
  as before. `h2` becomes a direct dependency of `servo-net`.
- Here: `native/Cargo.lock` gains that dependency edge (no new crate), the notices are
  regenerated, the fixture serves `/reused-close.html` (answered as a connection's first request,
  dropped unanswered on a reused one, counted in `/_admin/state` "reused_close"), and
  `BrowsingTest#aRequestOnAConnectionTheServerDroppedIsSentAgain` opens it after a solid page and
  expects the page, one dropped request and one answered one.

### Checks

- Downstream, desktop servoshell (`--profile medium`): the new `servo-net` tests
  `connection_reuse` (seven cases) pass; with the second attempt switched off in the same binary,
  every case that expects one failed with `client error (SendRequest)`, and the cases that must
  not send again passed. Full `servo-net` integration tests 399 of 400 (the remaining
  `test_filemanager` failure passes alone, as in §43); fetch WPT `api/basic`, `api/headers` and
  `api/redirect` 213 as expected; no new `./mach test-tidy` findings. The synthetic servoshell run
  above sends the dropped request again on a second connection.
- Reviews: GLM 5.3 workflow review on the downstream PR in three rounds (two claims refuted with
  hyper-util's source, the last two rounds without important findings) and independent helper
  reviews (Devin SWE-2 max, GLM 5.3, DeepSeek V4.1 Flash). Devin found that h2 also reports a
  `GOAWAY` without error to a stream just opened below its last stream id; that `GOAWAY` now lets
  only idempotent requests go out again.
- Here: `cargo metadata --locked` in `native/` passes with the new pin and the added edge, and fails
  without it; the regenerated notices differ only in the hashes of `native/Cargo.lock` and the
  pin. `scripts/tests/test_fixture_server.py` covers `/reused-close.html` over a kept-alive
  connection. The new device check and the rest of the fixture suite run on the x86_64 emulator in
  this PR's Engine workflow; that run is the evidence for this pin. Resource timing and devtools
  still show one attempt for a request sent again (follow-up).

## 46. Pages wider than the screen (5 November 2026)

Validated source: `e05945443307f38620138c5bd1c38ba07b768aaf` on `combray/0.7`, which merges downstream
[PR #32](https://github.com/L-K-M/servo/pull/32) onto §45's pin `0bf44ec270a0`. Its tree is that of
the PR's head `53179536a4`, which the device checks below ran on.

### Report

The owner reported a page (not named here) that was too wide for the screen and could not be
zoomed out. It has no viewport meta tag and a fixed-width table about 1080 CSS px wide. Combray lays
such a page out at the screen's width (412 CSS px on the reporting phone), the table overflows, and
Servo's pinch zoom, a magnifier that stopped at 100%, showed only the page's left part; the rest was
reachable only by scrolling sideways at 100%.

Synthetic reproduction: a page without a viewport meta tag holding a 1080 px table, in a 412x860
viewport at device pixel ratio 2.

- Desktop servoshell at `0bf44ec270` with `viewport_meta_enabled`: the page lays out 412 px wide, its
  content is 1088 px wide, only the left 412 px show, and pinching out does nothing.
- Chromium's mobile emulation (Playwright's headless shell, `isMobile`): the page opens at a page
  scale of 0.379 (412/1088) with a 1088x2271 CSS px visual viewport; `innerWidth` is 1088 and
  `documentElement.clientWidth` 980, its legacy layout width. With `width=device-width,
  initial-scale=1` it opens at 1.0 and zooms out to 0.379; a `position: fixed; bottom: 0` bar then
  sits at the bottom of the larger 1088x2272 layout viewport, off screen at 1.0.

### Changes

- Downstream (PR #32): layout grows the root scrollport (the layout viewport) to the width of the
  page's content, keeping its aspect ratio, by at most `initial-scale` over `minimum-scale` (over
  0.25, Chrome's default, without one). The initial containing block, media queries, `vw` and
  `innerWidth` keep their size, so the page does not lay out again. A root that hides overflow in
  either axis (`overflow: hidden` or `clip`) does not grow, since the larger viewport would show what
  it hides, and overflow to the left (right-to-left pages), which the page cannot scroll to, does
  not count. The page background covers the larger viewport. Paint shows the whole
  layout viewport at a pinch zoom of 1.0, so pinch zoom reaches the content's full width, with the
  page's `<meta viewport>` limits applied to the page scale. A page without `initial-scale` (or
  without a viewport meta tag) opens zoomed out to its width, like Chrome's "auto" initial scale, and
  follows its content as it loads until the user zooms; a page with one opens at it. Once the user
  zooms, Paint keeps the chosen page scale through later viewport changes where the limits allow.
  When script moves the root scroller to an edge of its range, as Combray's scroll-to-top and
  scroll-to-bottom buttons do, the pinch zoom viewport moves to that edge too. Content and initial
  containing block are compared in app units, so content that fits gives exactly 1, and a viewport
  without width (or a pinch zoom viewport without size) stays unzoomed instead of turning the
  transform into NaN.
  `<meta viewport>` descriptions now belong to their document and pinch zoom resets when another
  document becomes the root (both carried over to the next page before; desktop servoshell keeps
  its pinch zoom), pinch steps that arrive in one frame multiply instead of only the last counting,
  and a viewport zoom of zero no longer parses (Servo divided by it).
- Here: the pin, the fixture `/wide.html` (a 1080 CSS px row, red on its left half and green on its
  right; `?initial-scale=1` adds `width=device-width, initial-scale=1`), and `ZoomOutTest`: the page
  without an initial scale shows green at the screen's right edge, and the `initial-scale=1` page
  shows red there until a two-finger pinch zooms it out to green.

### Differences from Chrome and limits

- Combray still lays out a page without a viewport meta tag at the screen's width; Chrome and
  Firefox use a 980 px layout width. Without text autosizing (Servo has none), 980 px would make the
  text of simple pages too small to read, while pages wider than the screen now open zoomed out
  anyway.
- Fixed-position boxes stay in the initial containing block, the top left of the larger layout
  viewport, so a fixed bar stays on screen at a page's `initial-scale`; zoomed out, it no longer
  spans the screen.
- The field tap check converts by scale only and ignores the pinch zoom viewport's offset (§13). A
  page that opens at its `initial-scale` inside a wider layout viewport behaves there like a
  pinch-zoomed page, so after the user has scrolled, a tap on a field the page focused can miss it
  (follow-up). The scroll buttons reach the page's edges: Servo moves the pinch zoom viewport along
  when script scrolls the root to an edge.
- WebDriver touch actions use CSS pixels, which Servo converts at the current zoom, so a WebDriver
  pinch gains speed as it zooms; Android forwards touches in device pixels.

### Checks

- Downstream, `--profile medium`: twelve new embedder tests in `components/servo/tests/webview.rs`
  (`test_viewport_*` and `test_desktop_pinch_zoom_survives_navigation`, each in its own process) and
  two `viewport_description` unit tests pass. Each failed before its change: on the tests-only commit
  `2b3b6de206` the four that zoom out stayed at 1, the 25% default opened at 10%, a `<meta viewport>`
  added after the user zoomed out to 0.5 left 0.8, `overflow-y: hidden` and right-to-left pages
  zoomed out to 0.4, desktop lost its pinch zoom on navigation, fitting content at a 384.6 CSS px
  width moved a pinch zoom viewport by 0.0013 px instead of scrolling, and a script scroll to the top
  left the visual viewport 750 px down; the `overflow-x: hidden` guard passes either way. Unit tests
  of layout's expansion (`servo-layout`, `stacking_context::tests`) and of `PinchZoom` (`servo-paint`,
  `pinch_zoom::tests`) cover infinite and NaN ratios and an empty viewport; the two guard tests
  failed without the guards. The `webview` (42), `accessibility` (32) and
  `input_events` (2) suites, each test in its own process, pass except `test_svg_document_styles_paint`,
  which fails the same way on `0bf44ec270`; `test_accessibility_click_link` caught an extra
  accessibility update after navigations, fixed before review. `servo-paint-api` compositor tests
  pass (11); `./mach test-tidy` has no new findings.
- Reviews: GLM 5.3 on the downstream PR, and independent helper reviews (Devin SWE-2 max, GLM 5.3,
  DeepSeek V4.1 Flash) with each finding checked by a second model. Real findings, fixed: a viewport
  zoom of zero, the user's scale lost to a later `<meta viewport>`, hidden vertical overflow revealed
  when zoomed out, right-to-left overflow counted, and desktop's pinch zoom reset on navigation.
  A second helper review of the exact-fit and edge-following commits found that the exact fit had
  dropped the guard for a viewport without width (an infinite ratio, then a NaN transform), fixed in
  `37af7a980a`; the edge-following lens found nothing. Refuted: a missing green background in the pixel test (the shared helper paints it) and
  `background-attachment: fixed` against the larger layout viewport (that is the layout viewport,
  as in Chrome).
- Desktop servoshell with the change, the synthetic page: opens at 0.379 with a 1088x2271 visual
  viewport, as Chromium does; with `initial-scale=1` it opens at 1.0 and a WebDriver touch pinch zooms
  it out to 0.379.
- Engine workflow on the candidate `47f51554ad`: both native ABIs and the full x86_64 emulator suite,
  `ZoomOutTest` included, passed.
- arm64 runtime (the CI arm64 APK of `47f51554ad` and the test APK, both re-signed with one debug key,
  on an arm64 API 36 emulator on the Mac, 1080x1894 at density 2.625): `ZoomOutTest` passed (2), and
  failed with main's arm64 APK (pin `0bf44ec`), red where green belongs. `FixedLayerScrollTest`,
  `TextSizeTest`, `FitWidthTest`, `KeyboardTest` and `PdfTest` passed; `ScrollTest`'s
  `aPullRevealsHiddenControlsAndStillReloads` failed three times of three (main's APK passed it):
  the viewport there is 411.43 CSS px wide, the scroll fixture came out a hair wider than it in app
  units, the page opened at a pinch zoom of 1.00001, the test's swipe moved that pinch zoom viewport
  and the scroll-to-top button left it there, so the pull did not arm. The x86_64 CI emulator's
  integer width hid this. Both causes are fixed in `349266dd3a` (exact fit; script scrolls to an edge
  bring the pinch zoom viewport along) with regression tests above; this pin's Engine run and a
  repeat of the arm64 run check them. On `53179536a4` the Engine workflow passed (both ABIs, full
  x86_64 emulator suite), and on the arm64 emulator `ZoomOutTest` (2), `ScrollTest` (14, the pull
  after the scroll-to-top button included), `FixedLayerScrollTest` (2), `TextSizeTest`,
  `FitWidthTest`, `KeyboardTest` (7), `PdfTest` (10) and `BrowsingTest` (30) passed.
- Here: `cargo metadata --locked` in `native/` passes (no lockfile change); the regenerated notices
  differ only in the pin's hash; `scripts/tests/test_fixture_server.py` covers `/wide.html`. The
  device checks run in this PR's Engine workflow.

## 47. canPlayType answers backed by the player (5 November 2026)

### Report

The owner reported a mobile video page (not named here) that renders but never plays: its
player mounts a `<video>` and shows its own error card, and Combray's notice never appears
because no media request is made.

Probe on the desktop servoshell (`servo-sitecheck` at `--profile medium`, the sitecheck
harness's Combray preferences and Android user agent, an injected userscript that logs
`canPlayType` calls, media-element events and the site's player data):

- Baseline: `MediaSource`, `ManagedMediaSource` and `SourceBuffer` are undefined, and every
  `canPlayType` answer is the dummy backend's `""`. The page probes HLS, MP4 and WebM forms,
  leaves the `<video>` sourceless (`networkState` 0, `readyState` 0) and shows its error card.
  No media request reaches the delegate, so the offer flow cannot start.
- Claiming only the HLS MIME types (`application/(x-|vnd.apple.)mpegurl`, `audio/(x-)?mpegurl`)
  makes those probes answer `maybe`, and the page still attaches no source; the site's player
  response offers this client no `hlsManifestUrl` or `dashManifestUrl` at all.
- Adding the H.264/AAC MP4 claim removes the error card (the muted-autoplay UI mounts), and
  once the page's own player API is told to play, `video.src` becomes a progressive
  `itag=18` `video/mp4` address (H.264 + AAC, no cipher): a `video` load the delegate cancels
  and reports.

The claim that unlocks a progressive source there is MP4, not HLS. HLS stays unclaimed until
the player gains an HLS source (VIDEO.md's firm rule); the table claims only what Media3's
progressive source reads through the decoders Android requires.

### Changes

- `native/src/media.rs` gains `MediaSupport` and `claimed_support`: `Maybe` for the MIME
  types the progressive player reads, `Probably` when `codecs` names only device-mandated
  codecs (H.264 `avc1`/`avc3`, AAC `mp4a.40.*`, MP3 `mp4a.69`/`6b` and `mp3`, WAV's PCM
  family, FLAC, Vorbis/Opus/FLAC in Ogg), `No` for everything else.
- `native/src/media_backend.rs` installs that answer as the `ServoMedia` backend before
  `ServoBuilder::build` — the option-B installation VIDEO.md describes: every `Backend`
  method forwards to `DummyBackend` except `can_play_type`; the factory is panic-safe and
  `ServoMedia::get()` waits for it, so Servo's own `media_platform::init` is a no-op.
- `native/Cargo.toml` gains path dependencies on `servo-base`, `servo-media` and
  `servo-media-dummy`; `engine.rs` installs the backend next to `ServoBuilder`.
- `testing/fixtures/server.py` gains `/canplay.html` (a `<video>` that attaches the clip only
  when `canPlayType` claims the MP4 codecs; title `CANPLAY:unsupported` without it) and
  `/media-source.html` (a single `<source>` typed `video/mp4; codecs=...`), and
  `MediaPlayerTest` checks 11 and 12 open them, see Play offered with no clip request before
  the tap, and reach `READY` after it.

### Checks

- `cargo test --lib` in `native/` passes: 163 host tests including the claim table's four
  (claimed and rejected codec lists, the audio tables, the unclaimed types).
- `cargo check --target x86_64-linux-android --locked` passes: the shim compiles against the
  pinned Servo's `Backend` trait with the dummy's 14 other methods forwarded.
- `cargo fmt --check` passes. The new device checks and the rest of the fixture suite run in
  this PR's Engine workflow; that run is the evidence for the claim end to end.
- Not run: the reported page itself on a phone; whether its progressive URL plays in Media3
  on device is verified by the fixture clip's identical path, and the owner's own run closes
  the rest.

## 48. SVGAnimatedString and libz-sys (6 November 2026)

Validated source: `4778c513e962e63cfdd1f59b36093263f1ccf886` on `combray/0.7`, which merges
downstream [PR #34](https://github.com/L-K-M/servo/pull/34) onto the merge of
[PR #33](https://github.com/L-K-M/servo/pull/33), on §46's pin `e05945443307`. Upstream base
still v0.7.0.

- [PR #33](https://github.com/L-K-M/servo/pull/33) adds SVG 2's `SVGAnimatedString` interface and
  `SVGElement.className`, which reflects `class` through it. Scripts that tell an SVG `<a>`'s `href`
  from an HTML one with `instanceof SVGAnimatedString` threw a `ReferenceError` and stopped. Checks:
  a new WPT test (4 of 5 subtests failed before), 46 more passing subtests in `svg/idlharness` and
  `svg/types/scripted/SVGAnimatedString.html` with no other change in `svg/` and `dom/nodes/`, and
  a 20-page site-check sweep with no change. Reviews: gpt-6.1-sol (one minor finding, fixed) and
  GLM 5.3 (one minor test suggestion, not taken).
- [PR #34](https://github.com/L-K-M/servo/pull/34) backports upstream `4ad464ffb5d` and
  `ed6a9248aae`: libz-sys 1.1.29 to 1.1.31 in the lockfile, which clears cargo-deny's advisory
  against its build script (environment variables leaked into the build directory). libz-sys
  is linked through fontsan and mozjs. Checks: `css/WOFF2` WPT (298 tests) unchanged,
  `fetch/content-encoding` passes, and a 20-page sweep with no change.
- Here: `native/Cargo.lock` takes the same libz-sys version and checksum, and `cargo metadata
  --locked` passes; the regenerated notices differ only in their input hashes; the native host
  tests pass. The device checks run in this PR's Engine workflow.

## 49. HLS claims backed by the player (6 November 2026)

Phase C2's remainder (docs/VIDEO.md): the player reads HLS, so the claim table may claim it.

- `native/src/media.rs` claims `application/vnd.apple.mpegurl`, `application/x-mpegurl`,
  `audio/mpegurl` and `audio/x-mpegurl` — `Maybe` bare, `Probably` when `codecs` names only
  the playlist's supported codecs (the MP4 family's H.264/AAC rows cover X's fMP4 + AAC
  shape). DASH, WebM and MSE stay `No`. Five host tests assert the rows.
- `gradle/libs.versions.toml` pins `androidx.media3:media3-exoplayer-hls` at Media3 1.11.1,
  and `mediaSource(request, factory)` in `PlayerActivity.kt` picks `HlsMediaSource` for a
  `.m3u8` address, progressive otherwise — the source is chosen by the address's extension,
  not Media3's automatic selection. The claim is by MIME type while inference is by
  extension, so a progressive source that finds no extractor for its input
  (UnrecognizedInputFormatException) is retried once as HLS: a claimed playlist does not
  always carry `.m3u8`. Playlist and segment requests share the same OkHttp call factory,
  so `HopPolicy` applies to every hop unchanged.
- Fixture: `/hls.html` gates `video.src = /media/hls.m3u8` on `canPlayType`, and the server
  serves a variant playlist in X's shape (version 6, `EXT-X-MAP`, root-relative URIs, a
  separate audio playlist). The video segments are remuxed from `clip.mp4`'s own samples at
  start-up — the init segment keeps its `stsd`/`avcC`, two 90-sample `moof`+`mdat` segments
  split on the colour boundary's keyframe — and two committed afconvert AAC segments form
  the audio playlist (testing/fixtures/media/README.md). No ffmpeg needed.
- `MediaPlayerTest`'s `anHlsPlaylistPlaysAfterATap` waits for `HLS:errors:1` (the cancelled
  playlist load), asserts zero fixture requests, taps Play, and requires READY with video
  tracks; every recorded request then carries no cookie, the page's origin as `Referer` and
  Servo's user agent, and the recorded paths name the master, both media playlists and the
  init segment. `anHlsPlaylistWithoutItsExtensionStillPlays` plays `/hls-noext.html`, whose
  playlist is served at `/media/hls-manifest`, exercising the retry. Fixture-side unit
  tests cover the remux's box layout and the playlists.
- Not run here: an X post on a phone; the emulator run in this PR's Engine workflow is the
  device evidence for the claim and the HLS source.

## 50. Firefox user agents without a Gecko token (6 November 2026)

The owner reported a page that showed only "Access denied". Its site's firewall answers 403
with that 13-byte body whenever the `User-Agent` contains `Firefox` but no `Gecko/` token: it
takes such an agent for a forged one. Servo's agents claim Firefox (`rv:153.0`,
`Firefox/153.0`) but write `Servo/0.7.0` where Firefox writes `Gecko/153.0`, so every page of
the site was refused. Probes with curl and nothing but the agent changed:

| `User-Agent` | Status |
|---|---|
| Servo's Android agent, Servo's Linux desktop agent | 403 |
| `Firefox/153.0`, `Mozilla/5.0 Firefox/153.0`, `... Servo/0.7.0 FirefoxX/153.0` | 403 |
| Servo's Android agent with `like Gecko` or a bare `Gecko` in front of `Servo/` | 403 |
| curl's own, `Servo/0.7.0`, Servo's Android agent without its `Firefox/` token | 200 |
| Firefox for Android's own (`... rv:144.0) Gecko/144.0 Firefox/144.0`) | 200 |
| Servo's Android agent with `Gecko/153.0` (or `Gecko/20100101`) in front of `Servo/` | 200 |

Change: `native/src/user_agent.rs` puts `Gecko/<rv>`, `<rv>` being the agent's `rv:` version,
in front of Servo's token before Servo starts. Combray sends
`Mozilla/5.0 (Android 10; Mobile; rv:153.0) Gecko/153.0 Servo/0.7.0 Firefox/153.0`: Firefox for
Android's agent with Servo's token added, so it still names Servo. Servo sends its `user_agent`
preference both as the header and as `navigator.userAgent`, and `engine_ready` reports it for
the media player's requests, so all three stay equal. A later Servo agent without
`rv:<version>)` or without a `Servo/` token after it is sent unchanged, with a warning in the
log; one that already has a `Gecko/` token is kept. Done in Combray rather than in the engine
fork because the agent is the embedder's choice (servoshell has `--user-agent` for it), and the
transformation follows the pinned Servo's version and Firefox revision on its own.

Trade-off: scripts that tell engines apart by a `Gecko/` token now see Gecko. Browser detection
already saw Firefox in `Firefox/153.0`. A site that picks a Gecko-only code path this way can
call something Servo lacks; no such page is known.

The site-check harness sends the same agent to servoshell and Chromium
(`USER_AGENT_TEMPLATE`). Two tests keep it in step with a pin: a Rust host test reads the pinned
Servo's Android agent from its `prefs.rs` and fails when it differs from `SERVO_ANDROID` in
`user_agent.rs`'s tests, and `tests/test_config.py` fails when the harness's template differs
from `COMBRAY_ANDROID` there. Chromium results cached under the old agent are fetched again, since
the agent is part of the cache fingerprint. The harness cannot find refusals like this one:
Chromium is refused the same way, so the page counts as `reference-failed` and never enters the
triage queue.

Checks:

- Desktop servoshell (the site-check build, headless, 412×915, `--user-agent`): with Servo's
  Android agent the reported page shows "Access denied"; with Combray's it renders the article.
- Rust host tests for the transformation (Servo's Android agent, another revision, an agent that
  has the token, seven agents of other shapes) and for the pinned agent pass, as does `cargo fmt
  --check`. The pin test fails when `SERVO_ANDROID` names another Firefox revision. The harness's
  544 tests pass, and its new test fails when the template lacks the token.
- The fixture server's `/agent.html` applies the firewall's rule and, when it lets the page
  through, checks that `navigator.userAgent` equals the header; host tests in
  `scripts/tests/test_fixture_server.py` cover the rule and the header's escaping into the page's
  script. `UserAgentTest` loads it on the device. With main's engine (the arm64 APK of the Engine
  run for `bf8e052`, on the arm64 API 36 emulator) it fails: the page is refused and its tab has
  no title. With this change (a local `COMBRAY_ABI=arm64 scripts/build-native.sh` build of
  `0575f0e`, same emulator) it passes, and the reported page opens with its article and cookie
  notice.
- The full device suite (`scripts/check-app.sh`) on that build and emulator: 196 passed, 3
  failed. The three (`AccessibilityTest.theBottomBarOnANewTabIsAccessible`,
  `ReaderScrollTest.readerJumpsToBothEndsAndKeepsTheButtonOnTheLeftForRtlArticles` and
  `MediaPlayerTest.aPageVideoPlaysAfterATapWithoutServosCookie`, a video pixel colour) fail the
  same way with main's engine on this emulator, so they predate the change.

## 51. Typing into rich text editors (7 November 2026)

Validated source: `5aa68fd29b6b84319b9c411ff1b552da27e0bc6a` on `combray/0.7`, which merges
downstream [PR #35](https://github.com/L-K-M/servo/pull/35) onto the merge of
[PR #37](https://github.com/L-K-M/servo/pull/37), on §48's pin `4778c513e962`. Its tree is that of
the PR's head `6588db0749a`, which the local device checks below ran on.

### Report

The owner reported that commenting did not work on a site (not named here). The comment box shows
only when signed in, which the site-check policy rules out, so its editor could not be inspected.
Form fields take typing in Combray, which pointed to an editable element (a rich text editor) rather
than a form field.

Synthetic reproduction, in desktop servoshell at `4778c513e962` driven by WebDriver, with Chromium
(Playwright's headless shell) as reference: a plain `contenteditable` div, a Lexical 0.53
rich-text editor in light DOM and in an open shadow root, and a ProseMirror 1.42 editor, each
clicked and typed `hello`, Backspace, `p`, Enter, `world`.

- Without `dom_exec_command_enabled` (Combray's setting): keydown events reach the page and nothing
  is inserted in any of them.
- With it: ProseMirror works; the plain editor gives `hell<font size="7">pworld</font>` and no new
  paragraph; Lexical inserts nothing, because execCommand's `beforeinput` has no `inputType` and
  Lexical cancels it.
- On Android the keyboard does not open at all: Servo asks the embedder for an input method only
  for `<input>` and `<textarea>`, and Combray's committed text arrives as a composition end, which
  only those insert.
- Chromium: `hellp`/`world` in all four.

### Changes

- Downstream (PR #35), each with tests:
  - the backport of upstream's `ImeEvent::Commit`;
  - restoring the font size and link after `delete` (the `<font size="7">`);
  - `Selection.modify()` by character and word through the DOM, which Lexical needs for Backspace
    and Delete;
  - Input Events `beforeinput`/`input` with `inputType` and `data` for user input in editing
    hosts, input only into the focused editing host, and the left and right arrow keys;
  - a multiline text input method requested by a focused editing host and released on blur,
    removal or loss of `contenteditable`;
  - after review: `modify()` moves over images, form controls and non-editable blocks in one
    step (a non-editable block trapped the caret, and the position after a trailing image was
    unreachable); a host whose focus listener moves focus on asks for no input method; the
    restore after deleting converts every recorded size form (`5`, `x-large`, `20px`); and a
    hidden `<img>` or `<br>` no longer counts as content beside a `<br>`.
- The pin also takes [PR #37](https://github.com/L-K-M/servo/pull/37), merged before #35:
  `location.reload()` and Reload send the referrer and referrer policy of the request that fetched
  the document, as Firefox does, instead of the document's own URL.
- Here: the pin; `dom_exec_command_enabled`, which runs editing-host typing and gives pages
  `document.execCommand` as other browsers do; `ime_commit` sends `ImeEvent::Commit` (as Servo's
  own Android shell now does) instead of a composition end; the fixture `/contenteditable.html` and
  `KeyboardTest.aRichTextEditorOpensTheKeyboardAndTakesTyping`.

After the change, the synthetic run gives Chromium's text in all four editors (the plain editor
keeps a trailing `<br>`), and `ab`, Enter, `c`, two Backspaces, `X`, two left arrows, Delete gives
`aX` in all of them, as in Chromium.

### Differences from Chrome and limits

- No caret is painted in editing hosts: Servo paints one only in text controls. A text-node caret
  was tried and dropped, as it cannot show in an empty editor (`<p><br></p>`) and broke caret
  reftests.
- Combray still sends a word to the page when the keyboard commits it, with no composition
  preview, as for form fields.
- `Selection.modify()` moves through the DOM, not layout: moves by line, sentence, paragraph and to
  a boundary do nothing, and "left"/"right" ignore right-to-left text. Shift with an arrow key does
  nothing in an editing host (no `selectstart` yet).
- `getTargetRanges()` stays empty for user input; Lexical and ProseMirror do without it.
- The keyboard shows a single-line action key: Combray does not yet pass `multiline` to Android.
  The key sends Enter, which inserts a paragraph.
- The reported site's editor was not seen signed in, so its exact framework is unconfirmed.

### Checks

- Downstream, `--profile medium`: three embedder tests (`test_typing_in_editing_host`,
  `test_ime_for_editing_host`, `test_commit_without_composition`, each in its own process) and the
  existing input method tests pass; on the commit before each fix the two new ones fail. The new
  mozilla WPT test failed 4 of 4 before. WPT `editing/`, `input-events/` and `selection/` (1086
  tests) against the unchanged engine on the same Mac: 453 subtests newly pass, none regress.
- Here: `scripts/tests/test_notices.py` (the notices' crate set is unchanged; only the pin script's
  hash moves), `scripts/check_docs.py`, the site-check harness tests, `./gradlew testDebugUnitTest
  lintDebug`, and `cargo fmt --check` and `cargo test --lib` (164) in `native/` pass.
- On the local arm64 API 36 emulator (`combray-api36`), with a debug APK built at this pin:
  - `KeyboardTest` (8 tests, the new case included), `UserAgentTest` and `BrowsingTest` (30)
    pass.
  - By hand, with Gboard, the Lexical test page took `h`, `i`, space, Backspace, `o`, Enter, `y`
    as two paragraphs, `hio` and `y`, with the keyboard opening on the tap.
- The full device suite runs in this PR's Engine workflow.

## 52. Bot-check device verification (6 November 2026)

The owner sent an article address and a phone screenshot of a bot-protection block page ("Access is
temporarily restricted"). The site and the protection vendor are not named here, and the captured
traffic stayed on the Mac.

Validated source: `8720afc00e4daead852b8f7f63bb59e87e0b2a99` on `combray/0.7`, which merges
downstream [PR #36](https://github.com/L-K-M/servo/pull/36) onto §51's pin `5aa68fd29b6b`. The
reload change, [PR #37](https://github.com/L-K-M/servo/pull/37), came with §51's pin. Upstream
base still v0.7.0.

### Diagnosis

Desktop servoshell at the old pin, with Combray's preferences, reproduced the block. The vendor's
device check runs in a cross-origin frame, posts its measurements and receives a verdict: a hard
block, an interactive challenge, or a pass with a cookie and a reload of the page.

- Network: Safari in a private window on the same Mac and network loaded the article, so the
  address is not blocked as such.
- User agent: a plain Firefox for Android string, and a desktop Firefox string at desktop size, got
  the same block. The verdict's reason codes changed only in their first entry. The `Gecko/` token
  from §50 does not help here.
- TLS: through a re-encrypting proxy, which replaces Servo's TLS handshake with the proxy's own,
  Servo stayed blocked. Playwright's headless Firefox 157 through the same proxy got an interactive
  challenge.
- Engine features: traces of the Web API calls the check makes (a userscript wrapping prototype
  methods and getters, replayed offline from the proxy) differ in dozens of places between Servo
  and Firefox. Firefox was then made to look like Servo in 15 feature groups at once and in a
  4-bit combination of them (five live runs). It reproduced Servo's block and reason codes exactly
  when, and only when, the SVG geometry methods were removed: `getBBox()`, `createSVGPoint()`,
  `isPointInFill()`. The check draws an SVG fingerprint with them. Firefox missing every other
  difference (navigator properties, `speechSynthesis`, WebGL debug info, OffscreenCanvas in
  workers, timing values, media types and others) still passed.
- Reload: with the SVG methods present, the verdict passed, but the reload it triggers was
  challenged again. Rewriting Servo's reload request toward Firefox's in the proxy, one header group
  at a time, isolated the `Referer` header. Servo sent the page's own URL; Firefox sends what the
  original request sent, here nothing, as HTML requires.

### Engine changes

- [PR #36](https://github.com/L-K-M/servo/pull/36) adds `SVGGraphicsElement.getBBox()`,
  `SVGGeometryElement.isPointInFill()` and `SVGSVGElement.createSVGPoint()`, `createSVGMatrix()`
  and `createSVGRect()`, computed from the elements' attributes. Text and nested viewports with a
  `viewBox` throw `NotSupportedError`.
  - It also makes a negative ellipse radius take the other radius, for `getTotalLength()` too.
  - Checks: a new 18-case WPT test fails all 18 before the change and passes after; Firefox passes
    all 18 and Chromium 16. 227 more passing subtests in `svg/`, with no other change against the
    unchanged build on the same Mac. A 16-page seed sweep turns one `js-error` into `ok` and
    changes nothing else.
- [PR #37](https://github.com/L-K-M/servo/pull/37) reloads with the referrer and referrer policy of
  the request that fetched the document, for `location.reload()` and the embedder's reload alike.
  - Checks: a new 3-case WPT test fails 3 cases before the change and passes after; Firefox passes
    all 3 and Chromium does not. No change in the 102 upstream tests that reload.
- Reviews:
  - Before the PRs: Devin SWE-2, Reflection Beam 1.0 and LongCat 2.5.
  - On them: GLM 5.3, three rounds on #36 and two on #37.
  - The PRs list each finding and how it was handled. Two remain open: a style-sheet
    `display: none` on a direct child of `svg` is still not seen, as servo.css's `svg > *` rule
    hides it from the rasterizer as well; and `use` of a `symbol` with a `viewBox` throws.

### End to end

Desktop servoshell with both changes, Servo's own user agent, the same network:

- It passes the device check and loads the article.
- With Servo's Android user agent at phone size, the check offers its interactive challenge instead
  of the block. Two things fit that: the Mac's fonts and hardware contradict an Android user agent,
  and the check probes OS-specific fonts. Emulating the phone's pointer, touch, platform and pixel
  ratio did not change that verdict.

On an arm64 API 36 emulator with the same network, Combray at the old pin and Combray with both
changes (a local build, `COMBRAY_ABI=arm64 scripts/build.sh --debug`) both stop at the vendor's
interactive challenge, not at the block. Real Firefox 157 with a Firefox for Android user agent
and touch emulation on the Mac is blocked as well (reason `25` and another). Neither desktop nor
emulator therefore shows what a phone gets.

The phone itself is not yet confirmed.

### Not changed

These differences remain, did not decide this check, and are candidates for later work:

- Servo exposes no `navigator.webdriver` or `navigator.maxTouchPoints`.
- OffscreenCanvas stays behind `dom_offscreen_canvas_enabled`, so the check's worker tasks throw.
- `performance.timing.navigationStart` is 0.
- A script reload sends `Sec-Fetch-Site: same-origin` and `Sec-Fetch-User: ?1`.
