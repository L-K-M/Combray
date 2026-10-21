# Sol's Combray review

**What this repo does:** Combray embeds Servo in a native Android browser. It keeps controls at the bottom, combines tabs and local history in Recent, saves to Karakeep, archives rendered text in Hister, and searches the web with Kagi.

**Baseline:** `e5cf8ed`, 21 October 2026. Expected load: one person, 10–30 logical tabs, several active sites, intermittent connectivity, and days between clean process exits. This review was written before implementation. The final handoff will distinguish open proposals from work offered in PRs.

Path shorthand: `core/...` means `core/src/main/kotlin/ch/lkmc/combray/core/`; UI filenames are under `app/src/main/kotlin/ch/lkmc/combray/ui/`.

## Assessment

The separation between logical tabs, native views, visits, and durable integration work fits the product. Keep it. Existing features include Reader view, link menus, external-link return behavior, service failover, Karakeep lists, remote Recent scopes, and labeled primary controls. Reimplementing them would add little.

The next gains should come from trustworthy page state, dependable input, and predictable layout. A faster-looking theme cannot compensate for a stale frame, a lost input composition, or a long main-thread wait. Servo is a full web engine, including JavaScript; a small browser shell does not by itself make arbitrary pages cheap.

Fix S13 and S14 first: they break promises about retained and forgotten content. S01 and S15 are smaller correctness fixes. S05, S06, and S20 need native/device evidence before broader daily use.

## Evidence and limits

- Read the project brief, implementation plan, integration contracts, engine report, build/release tooling, coordinator, and relevant tests. Independent source reviews cover the native bridge, integration/data layer, and Android UI.
- Baseline `python3 scripts/check_docs.py`: passed.
- Baseline `./gradlew testDebugUnitTest lintDebug assembleDebug`, with the installed Android SDK and JDK 21: passed.
- Baseline `python3 -I -m unittest discover -s scripts/tests`: 29 passed.
- Baseline native host tests: 20 passed after preparing the pinned Servo source. `cargo fmt --check`: passed.
- Fresh native compilation and an isolated x86_64 emulator are being prepared. Device observations will be recorded separately; source reasoning is not a measurement of phone responsiveness.
- Existing M0 screenshots demonstrate engine behavior, including a stale painted page. They do not show the current Compose browser chrome.
- No personal history, live services, Kagi login, physical phone, or other contributors' PRs were inspected. Visual proposals below need device validation.

## Confirmed source findings

### S01. Reader view survives a same-document page change

**Problem:** `BrowserCoordinator.onHistory` clears Reader only for `DOCUMENT_COMMITTED`. A site that changes from article A to article B with `pushState` retains the old Reader overlay. A pending extraction can also finish after the address changes and be presented with the earlier address. See `core/.../browser/BrowserCoordinator.kt:417–445,597–607`.

**Fix:** Invalidate Reader when its tab's accepted address changes, including same-document navigation, while preserving it for unrelated hidden-tab events. Keep dropping results from invalidated requests.

**Acceptance:** Cover ready and pending Reader states, `pushState`, address-changing history updates, delayed results, and hidden-tab navigation in `ReaderTest`. A new article must never appear under the previous article's address.

### S02. Local search silently stops at the newest 500 history entries

**Problem:** `recent(query)` fetches 500 eligible visits and only then filters them in Kotlin. With 501 retained visits, searching for a unique word in the oldest one returns nothing although it is still stored. See `BrowserCoordinator.kt:125–137,841–845` and `core/.../data/BrowserDao.kt:40–49`.

**Fix:** Apply the search before the result limit. Preserve case-insensitive Unicode matching and literal `%`/`_` behavior; do not replace the current matching rules with an unescaped SQL `LIKE` query.

**Acceptance:** Seed more than 500 visits within retention. Find a uniquely matching old title and URL; verify Unicode, wildcard characters, failed visits, and current-open-visit deduplication. Keep unfiltered Recent bounded.

### S03. Diagnostic events still rebuild the browser snapshot

**Problem:** Events ignored by `onEngineEvent` still call `publish`, which reads the session, active tab, and all open tabs. Paint reports are already limited to one per second, so this is avoidable database activity, not evidence of a 60-fps database bottleneck. See `BrowserCoordinator.kt:355–395,808–835` and `native/src/engine.rs:66–67`.

**Fix:** Skip publication for events that cannot change browser state. Preserve load, history, title, control, crash, and capture handling.

**Acceptance:** Count Room queries while delivering diagnostic events and verify they perform no browser-state queries. A following real navigation/title event must still update the UI.

### S04. The default device gate omits Reader view

**Problem:** `scripts/check-app.sh:144–160` names each default test class but never runs `ReaderTest`. A green default device run therefore does not cover a recently added browser feature.

**Fix:** Include Reader in the default gate and add a small selection regression so later test classes are not silently omitted.

**Acceptance:** A default run must show Reader checks in its summary. Selecting an explicit class must still run only that selection.

## Known engine and release work

These are existing limitations, retained because they affect daily use. They are not newly reproduced defects.

### S05. Resolve the stale-frame blocker before cosmetic speed work

The M0 reproduction can leave an earlier page painted while the chrome and captured content refer to a new document (`docs/ENGINE_SPIKE.md`, section 6). Reproduce on a real GPU and on the fixture emulator, then fix the Servo painting/surface cause. Acceptance must compare pixels with the selected document after view creation, eviction, closing, navigation, backgrounding, and rotation. A current URL is not proof that the current page is on screen.

### S06. Bound actual capture execution and measure page/engine stalls

Capture deadlines abandon results but do not interrupt page-context JavaScript. The documented 20,000-node extraction took 10–20 seconds under software emulation; that is a warning about work size, not a phone benchmark. Measure capture and Readability separately on long articles and hostile getters. Prefer trusted engine-side extraction or a genuinely interruptible evaluation path; keep capture optional until the limit is resolved. See `docs/ENGINE_SPIKE.md`, sections 8–10.

`native/src/reader.js:31` clones the entire DOM before Readability checks its element budget. Reject oversized documents before cloning, with a bounded/trusted preflight; a page-controlled JavaScript getter is not an execution guarantee. A huge DOM should become an explicit Reader-unavailable result without first duplicating the page heap.

### S07. Prove everyday compatibility and the release artifact

The current evidence does not establish Kagi login, the owner's sites, physical-device performance, or deployed Karakeep/Hister contracts. Release CI assembles Kotlin without Servo and has no personal signing configuration (`.github/workflows/release.yml:59–76`). Build and verify a signed arm64 engine-bearing APK, exercise a short owner-approved site list, and run authorized synthetic saves/captures against the installed service versions. Record unsupported downloads, selection, permission flows, and session restoration explicitly.

Keep an engine update/patch-rebase procedure and review the inherited Cargo feature set (`native/Cargo.toml:40–50`), including test bindings, Bluetooth, WebXR, and WebGPU. Remove features only after a supported build and compatibility measurements. Servo content currently shares the app process; do not advertise a separate content sandbox or private mode. Native host tests exclude Android-only `engine.rs`; add focused host coverage for extracted ownership/attribution rules when touching them, and retain real-engine checks.

## Interface direction and future slices

The following are proposals, not additional confirmed requirements.

### S08. Make Recent a calm, readable shelf

Keep one surface and the existing Open/All/Hister/Karakeep scopes, date separators, active/open text markers, and horizontally scrolling scope strip. These already exist in `RecentSheet.kt`. Improve their hierarchy and check title, domain, state, and close action at 320dp and 200% text. Preserve tab identity when titles or URLs match. Give Open tabs its own empty message; its present message also mentions history hidden by that filter.

**Acceptance:** Narrow portrait, landscape with the keyboard, long translated labels, large text, duplicate tabs, and empty/error/loading states are usable with touch and TalkBack. Remote requests occur only in an explicitly selected remote scope.

### S09. Complete the pine-and-paper theme

Use a coherent set of warm paper light surfaces, charcoal dark surfaces, and pine accents across sheets, selected chips, outlines, dialogs, disabled states, and Reader. Define the Material color roles actually used; partially overriding a default theme can leave unrelated container colors. Offer System/Light/Dark only if an override is useful to the owner. Avoid forced recoloring of arbitrary websites.

**Acceptance:** Check text contrast, selected/unselected controls, error states, status/navigation bars, large type, and Reader in both modes. Capture reference screenshots of actual Compose screens, not the HTML concept. Keep system type in chrome and a restrained reading measure in Reader.

### S10. Add Find in page before decorative browser features

A long article needs an in-page search even when Reader is unavailable. Implement a bounded, document-scoped Servo find path with a small bottom search bar, match count, next/previous, keyboard submit, and dismissal. Search only the current document; do not turn this into Hister or Kagi search. Navigation invalidates results.

**Acceptance:** Unicode, many matches, no matches, changing document, large pages, hidden controls, and Back/IME handling. No full-page text copy on every keystroke.

### S11. Make save feedback a useful receipt

Keep one-tap Save. Show the acknowledged destination and optional list in a compact status affordance, with Pending, Saved, and Needs attention distinguished by text as well as color. Successful bookmark creation and failed list membership are different outcomes. Expose Retry or queue details directly from the status instead of making the owner hunt through Settings.

**Acceptance:** Offline tap, duplicate tap, response loss, successful create plus failed list attachment, and navigation immediately after the tap all retain their original payload and destination.

### S12. Add small, optional moments of delight

- **Madeleine:** a user-invoked Hister recall action for a phrase, with the existing Hister scope and an ordinary query. No passive remote requests and no new AI service.
- **Reading seam:** restore Reader's scroll position when briefly leaving and returning to the same accepted article. Key it by tab and document, not URL alone; discard it on content replacement.
- **Return ticket:** an accessible last-tab action, optionally complemented by a bottom-bar gesture. Keep the visible alternative and avoid conflicting with Android Back.
- **Quiet home:** a restrained Combray wordmark, one search action, and a small route back to Recent. Use the existing icon's visual vocabulary rather than an animated dashboard.
- **Named Undo:** include a bounded page title in the close receipt. `Notice.TabClosed` already carries it; retain a generic fallback for untitled pages.
- **Copy confirmation:** confirm link/image-address copying on API 30–32, where Android has no system clipboard preview. Avoid duplicate notifications on newer Android. A long-press address-copy shortcut should also have a discoverable, accessible alternative.
- **Reading time:** a quiet, approximate duration beside Reader's byline. Derive it locally, label it as an estimate, and avoid treating a fixed English word rate as universal.

Ship these as separate experiments after the correctness and layout checks. Respect reduced motion; do not add haptics or animations to every routine action.

## Additional correctness and privacy findings

### S13. An abandoned running capture can escape the seven-day expiry

**Problem:** `core/.../data/IntegrationDao.kt:127–134` expires pending/retry/auth/failed captures but excludes `RUNNING`. After a worker dies and the app returns eight days later, `nextDue` reclaims the expired lease and sends the old body. The existing expiry test covers only pending work. A long drain can also cross the age boundary after its initial prune.

**Fix:** Expire abandoned running claims and clear their bodies. Enforce maximum age at the transactional claim boundary, not only at the start of a dispatcher drain. Preserve an actively owned request's fencing and the existing warning that already-sent bytes cannot be recalled.

**Acceptance:** Claim then simulate eight days of process death; restart and drain; no HTTP call, terminal expired state, no retained body. Also test a fresh live lease, a stale claimant's late result, explicit saves, and crossing the cutoff during a drain.

### S14. Forgetting local history and cancelling its capture are separate commits

**Problem:** `BrowserCoordinator.kt:267–290` commits the local deletion before calling `CaptureSink.forgetUrl/forgetHost`. Those methods cancel queued bodies in another transaction and retain tombstones only in memory (`HisterIntegration.kt:54,224–238`). A process death or cancellation failure between commits leaves invisible history content queued for upload after restart.

**Fix:** Put the local deletion and durable cancellation in the same database transaction. Keep network cancellation best-effort and delayed-capture generation checks. Document the shared-database transaction contract at the boundary.

**Acceptance:** Inject failure during invalidation and verify both durable changes roll back. Retry successfully, recreate the coordinator/integration objects on the same database, and prove that neither exact-URL nor matching-host captures can dispatch. Unrelated domains and protected open-tab recovery records stay intact.

### S15. Missing bookmark IDs silently skip a selected Karakeep list

**Problem:** `Integrations.kt:319–326` returns the create result when no bookmark ID is parseable. `KarakeepClient.kt:62–69` treats a malformed 200/201 body as acknowledged creation with a diagnostic note. With a configured list, the entire job becomes Saved although membership was never attempted and cannot be retried.

**Fix:** Keep creation acknowledgment distinct from list completion. At minimum, a list-bound job with no usable ID must remain retryable or need attention; a duplicate-safe retry can recover the ID. Preserve the current acknowledged-save behavior when no list was requested.

**Acceptance:** Malformed or missing-ID 200/201 with a selected list cannot end as fully succeeded. A subsequent valid duplicate response must add the same bookmark to that list. Cover list errors and standalone saves separately.

### S16. Failed HTTPS loads still announce an encrypted connection

**Problem:** `BottomBar.kt:124–131` derives the spoken security label from the URL even when `loadFailed` is true. `PageInfoDialog` also sees only the URL (`SettingsSheet.kt:535–550`). A certificate failure can therefore announce both “Encrypted connection” and “Page did not load”.

**Fix:** Pass accepted load/security state to both surfaces. Failed, pending, and established connections need honest wording; do not infer a successful secure connection from a typed HTTPS address.

**Acceptance:** Compose semantics and page information for certificate failure, connection refusal, loading, HTTP, and successful HTTPS. Keep the visible warning and spoken label consistent.

### S17. Service and queue status sometimes describe the wrong state

**Problem:** Remote panels initially collect service configuration as `null`, briefly presenting “not configured” before Room answers (`HisterSearchPanel.kt:57`, `KarakeepPanel.kt:56`). Separately, `HisterIntegration.kt:211–215` clears `captureBudgetReached` only after a later accepted capture; draining or discarding all bodies can leave a false paused warning.

**Fix:** Distinguish initial loading from absent configuration. Clear a budget-rejection notice when the blocking queue condition changes, or describe it as a past skipped capture. Do not derive it solely from `queuedBytes >= limit`: a rejected body can exceed the remaining space while the queue itself is below the limit.

**Acceptance:** Delay the first configured-service emission and verify no false setup prompt. Fill the queue, reject a capture, drain/discard it, and verify the warning clears without needing another page capture.

### S18. A server URL does not identify a service account

Destination fingerprints currently identify normalized service roots. Rotating a key pauses queued work, which is useful, but a different account on that root can inherit old Saved markers and the option to resume old payloads. See `Integrations.kt:263–275,308–317`, `ServiceUrls.kt`, and the destination-binding decision in the implementation plan.

Establish a non-secret account identity through the deployed service's supported read-only API, or make account replacement explicit and invalidate old-account state. Do not guess a user endpoint. Acceptance needs two isolated accounts on one fixture host, separate Saved status, and proof that replacing an account cannot resume the earlier account's captures. This is a contract/design task before a migration, not a request for the owner's credentials.

### S19. Text capture loses spaces between inline elements

**Problem:** `native/src/capture.js:86–89` drops whitespace-only text nodes. `<b>one</b> <i>two</i>` becomes `onetwo`, which changes Hister's searchable text. The source makes this deterministic; the native/device regression remains to be run.

**Fix:** Preserve a collapsed separator where it divides inline runs, without introducing spaces across block boundaries or exceeding the UTF-8 budget.

**Acceptance:** Real-engine capture of inline links, emphasis, adjacent spans, line breaks, preformatted content, multibyte text, and a separator at the byte limit. Search the fake Hister for the original separate words.

### S20. Harden input ownership and bound work before it reaches the engine thread

Split this into focused native slices:

- **IME ownership:** `native/src/engine.rs:1091–1117` lets a hidden tab replace the single `ime_owner`; its event is then ignored by the coordinator, and the visible field's later hide event can be lost. Track ownership per view or restrict it to the selected live view. Prove hidden autofocus cannot steal or strand the visible keyboard, including switching tabs while a composition is pending.
- **Real input editing:** `engine/.../EngineSurfaceView.kt:182–234` has no composition preview or selection synchronization and implements surrounding deletion as two JNI calls per requested character. Test AOSP/Gboard, CJK composition, emoji/surrogate pairs, correction, selection, and large deletion ranges. Implement bounded, properly attributed editing or explicitly decline unsupported requests; silently clipping a requested edit is not correct input handling.
- **Bound acquisition:** `native/src/engine.rs:1433–1492` clones all select entries and dialog strings before `controls.rs` truncates their JSON. Apply limits while acquiring descriptions, including optgroups, selected IDs, and messages. A 100,000-option fixture must not allocate another complete options tree on the paint/input thread.
- **Bound document bookkeeping:** `engine.rs` retains per-tab document/retired maps for the tab's lifetime. Measure a long-lived navigation loop and prune obsolete identities without breaking back/forward attribution. Do not evict IDs by a guessed fixed window before checking Servo's history/cache semantics.
- **JNI delivery:** JSON parsing and listener delivery run inside the engine-thread callback (`jni_api.rs:90–108`, `NativeEngineHost.kt:33–42`). Measure large-capture parse latency; test a throwing listener and subsequent delivery, including the pinned JNI crate's exception cleanup. If moving parsing off-thread, preserve strict event order and bound the queue.
- **Foreground/background budget:** measure frame timing, resident/evicted tab switches, 1/10/30-tab memory, idle CPU, and background timers on a real GPU. The engine polls at 250ms and the selected view remains selected after backgrounding. Use visibility/lifecycle evidence before introducing suspension or a frame scheduler.

Also harden direct concurrent native startup before exposing that API to more callers: `jni_api.rs:181–195` spawns before publishing its singleton. The production host already serializes `start`; a race test should prove exactly one owner/thread before adding initialization locks or cleanup paths.

## Reviewed candidates that need qualification

- A proposed blanket `weight(1f)` fix for every bottom-sheet list lacks a device reproduction. Compose `Column` measures non-weighted children against remaining constraints; the presence of an unweighted list alone does not establish clipping. Test the last row and pagination at narrow/IME layouts as part of S08 before changing sizing.
- The snackbar uses a fixed 72dp clearance (`BrowserScreen.kt:223`). Measure actual bar/snackbar bounds at large font and landscape; use the measured bar height if overlap is observed. Do not claim a font-scale failure from source estimates alone.
- Pasted tabs/newlines in URLs deserve explicit policy tests. Reject or normalize them according to URL rules; indiscriminately turning all whitespace into spaces can change a valid URL's meaning.
- Claim counts include ambiguous, abandoned requests. Counting only received failures would permit unbounded retries after repeated response loss. Keep the conservative retry bound unless a verified requirement justifies separate claim/dispatch counters.
- One generic coordinator failure message is hardcoded English (`BrowserCoordinator.kt:108`). Map a typed notice to a string resource when localizing the shell; avoid adding resource IDs to the core domain model.
- A helper attributed a negative-count crash to Kotlin `repeat`. Non-positive counts do not establish that crash. The valid concern is unbounded positive deletion work and incomplete IME semantics, retained in S20.

## Initial implementation shortlist

1. Expiry of abandoned captures and claim-time age checks (S13).
2. Atomic history forgetting and queued-content cancellation (S14).
3. Reader invalidation across same-document navigation (S01).
4. Honest completion for list-bound Karakeep saves (S15).

Keep fixes independent where possible. Open PRs for review and leave them open for the owner. Consolidate the remaining, unmerged work into `ANALYSIS.md`; do not describe an open PR as a completed feature on main.

**Verdict:** prioritize page/input correctness and trustworthy state, then responsive layout and the coherent theme. Measure actual device responsiveness before claiming speed gains.

**Not checked:** live services, personal data, physical-device behavior, full upstream Servo internals, and a legal/security audit of all third-party dependencies.
