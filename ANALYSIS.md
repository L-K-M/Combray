# Combray — analysis and future work

Consolidated roadmap from `SWE2.md` and the [Sol review](https://github.com/L-K-M/Combray/pull/28),
21 October 2026. Both source reviews started at `e5cf8ed`; source line citations
in Sol refer to that baseline. Read `AGENTS.md` and the
[implementation plan](docs/IMPLEMENTATION_PLAN.md) before implementing an item.
This file is the current backlog; the individual reviews preserve their evidence
and rationale. Keep confirmed requirements distinct from proposed refinements.

State today: M0–M3 implemented, device-checked on an x86_64 emulator with fake
services only. No physical device, real Karakeep/Hister/Kagi endpoint, or live
site has been exercised in the recorded acceptance evidence. Servo v0.6.0 is
mandatory. Its pinned embedding has no Stop API or JavaScript interruption.

## Priority and completion rules

1. Review the prepared privacy/state fixes below; avoid parallel implementations
   of work already offered in a PR.
2. Resolve stale-frame correctness, input ownership, and bounded extraction.
3. Complete Recent/Settings accessibility, readable bottom chrome, and theme
   validation. Then add Find in page and small workflow improvements.
4. Prove the owner's essential sites and a signed, engine-bearing arm64 APK.

An open PR is not an implemented feature on main. Move an item out of this
backlog only after its fix lands and its acceptance evidence is recorded. No
personal history, credentials, or service mutations are needed for fixture work.

## In flight (PRs open for owner review, not merged)

The following six entries are retained from the existing roadmap. Their PRs were
not inspected during the Sol pass; descriptions are proposals until merged.

- #24 `swe2/inert-events-no-publish` — skip `publish()` on state-inert engine
  events (Painted, surface/selection echoes, diagnostics). All subtypes are
  enumerated so a new `EngineEvent` fails to compile until placed.
- #25 `swe2/recent-filter` — Recent collects once and filters the list in memory;
  proposes sharing `RecentItem.matches` on the model. This does not resolve
  search truncation before filtering (correctness item 10 below).
- #26 `swe2/theme-palette` — all M3 color roles set for both modes; light
  `outline` at ~3.5:1 for WCAG 1.4.11.
- #27 `swe2/reader-close` — floating close button in reader view.
- #29 `swe2/outbox-lease` — lease raised 60 s → 150 s; worst-case dispatch is
  ~62 s + unbounded DNS (two `routes.call` × two roots, connect-failure retry
  only). Rationale in the KDoc.
- #30 `swe2/budget-flag` — `captureBudgetReached` is derived from live queue
  size and clears when the queue drains.

If any of these are declined, their rationale is in the PR discussion and the
SWE2.md finding number they implement.

Sol's independent fixes, all left open for the owner:

| PR / branch | Fix and acceptance evidence |
| --- | --- |
| [#31](https://github.com/L-K-M/Combray/pull/31) `sol/expire-abandoned-captures` | S13: expire abandoned Hister claims and recheck age inside every claim. Regression tests first demonstrated old bodies reaching the fake server; verify expired payload erasure, late-result fencing, live leases, and a drain crossing the cutoff. |
| [#32](https://github.com/L-K-M/Combray/pull/32) `sol/atomic-forgetting` | S14: local history deletion and queued-content cancellation share one Room transaction. Failure injection verifies rollback, visible failure, retry, subdomains/lookalikes, protected open tabs, and fresh integration objects without in-memory tombstones. |
| [#34](https://github.com/L-K-M/Combray/pull/34) `sol/reader-navigation` | S01: leave ready/pending Reader when its accepted URL changes, including same-document/hash routing. Verify stale results cannot reopen it, same-address updates retain it, and hidden tabs stay independent. |
| [#35](https://github.com/L-K-M/Combray/pull/35) `sol/karakeep-list-receipt` | S15: list-bound saves cannot finish without a usable bookmark ID. Fake 200/201 malformed/missing-ID receipts recover the same bookmark or need attention after ten attempts; standalone acknowledgment is preserved. |

These four fixes have regression-first Room/JVM evidence, passing local JVM
tests/lint/debug and release assembly, and changelog/plan updates in their PRs.
CI and review details belong to the linked PR revision. They do not establish
deployed service compatibility or physical-device acceptance.

## Correctness items (not yet done)

1. **`PageInfoDialog` dismisses during composition.** `BrowserScreen` does
   `tab?.url?.let { PageInfoDialog(...) } ?: dismiss()` — a state write inside
   composition. Hoist into an effect or make the sheet key on an existing target.
   File: `app/src/main/kotlin/ch/lkmc/combray/ui/BrowserScreen.kt`. Verify the
   active tab disappears while Page Info is selected: one clean dismissal,
   no composition-time state mutation or stale dialog.
2. **`KarakeepPanel` refetches on any service-row change.** `LaunchedEffect(query,
   service)` reruns when `updatedAt` bumps (e.g. choosing a save list), restarting
   pagination from page 1. Key on the fields that matter (fingerprint/generation).
   Keep existing results/cursor when only list preference changes; invalidate
   them when destination or credentials change.
3. **Failed-visit detection keys on Servo error-page titles** ("Error loading
   page", "Certificate error"). If Servo renames/localizes them, failures become
   successful history rows. Options: engine-level load-failure signal upstream,
   or a device-check asserting those titles. Coordinator `SERVO_ERROR_TITLES`.
   A successful page can also choose either title, so prefer a trusted engine
   error signal. Prove network/certificate failures are not normal history or
   Hister captures, and ordinary articles with those titles still work.
4. **Coordinator command channel is unbounded** (`Channel<suspend () -> Unit>`).
   Bursts queue stale work; consider conflation of publish work or a bounded
   channel with a deliberate overflow policy. Preserve ordered navigation,
   controls, and capture results; never silently drop them to reduce queue size.
   Flood diagnostic/title events, then verify a user command completes and
   accepted document identities remain ordered.
5. **`Retention()` re-instantiated in SettingsSheet** for the history-days label —
   diverges the day retention becomes configurable. Pass the configured instance
   through `AppGraph` when that setting becomes configurable. Check the label
   against the actual maintenance policy rather than adding configuration now.
6. **Process-lifetime map growth:** `HisterIntegration.forgotten` tombstones,
   `LiveView.documentVisits`, and native per-view document/retired maps grow.
   A six-view cap does not bound navigations within one long-lived tab.
   Measure a long navigation/forget loop, then prune obsolete records without
   breaking back/forward attribution, capture guards, or cancellation. Do not
   choose an arbitrary identity window before checking Servo's cache semantics.
7. **Tab badge renders the raw count:** cap the visual label at "99+" while
   retaining the real count in its TalkBack description. Check 0/1/99/100 tabs
   and large text without changing logical tab state.
8. **IME bridge is minimal**: `EngineInputConnection` lacks
   `getTextBeforeCursor`/`getCursorCapsMode` etc. Autocorrect and gesture
   keyboards see an empty field. Committed Unicode has fixture evidence;
   composition preview and selection synchronization are absent.
   `deleteSurroundingText` emits two JNI calls per requested character. Test
   AOSP/Gboard, CJK, correction, emoji/surrogate pairs, selection, and large
   deletion ranges. Implement bounded, document/view-attributed edits or decline
   unsupported requests honestly; silently clipping an edit is incorrect.
9. **`dismiss()`/focus race candidates on sheets**: `AddressSheet` requests focus
   in `LaunchedEffect` possibly before the sheet window is laid out; modal sheets
   also historically ignored IME insets. Verify keyboard opens reliably and the
   field clears the keyboard on a real device. Test warm/cold opening, rotation,
   landscape and Back with the IME visible. The fixed 72dp snackbar offset in
   `BrowserScreen` also needs measured bounds against a growing bottom bar.
10. **Local search misses retained visits beyond the newest 500 (Sol S02).**
    `BrowserCoordinator.recent(query)` calls `BrowserDao.observeHistory` with
    `RECENT_LIMIT` before applying the title/URL filter. Search before limiting
    results. Seed more than 500 retained visits and find the oldest unique match;
    cover Unicode, literal `%`/`_`, failed visits and open-visit deduplication.
    Preserve current matching semantics and a bounded unfiltered list.
11. **Connection wording can contradict a failed load (Sol S16).**
    `BottomBar.AddressChip` and `SettingsSheet.PageInfoDialog` infer security
    solely from the HTTPS URL. Pass accepted load/security state to both. Test
    TalkBack and visible text for pending, failed certificate, offline, HTTP and
    established HTTPS states; a failed load must not claim an encrypted connection.
12. **Service status needs loading and rejection states (Sol S17).**
    `HisterSearchPanel`/`KarakeepPanel` initially collect configuration as null,
    flashing a false setup prompt before Room answers. Delay the first configured
    emission and verify no false prompt. Budget warnings must also clear after
    drain/discard without another capture. Include rejection when a new body's
    size exceeds remaining space although `queuedBytes` is below the limit;
    a simple `queuedBytes >= limit` flag does not describe that case.
13. **A service root is not an account identity (Sol S18).**
    Root-based fingerprints can reuse Saved status across accounts on one server;
    changing a key pauses jobs but does not identify the replacement account.
    Establish a supported read-only account identity, or make account replacement
    explicit and isolate old state. Do not invent an endpoint or use token hashes
    as account IDs. Verify two fixture accounts on one host, key rotation within
    one account, status separation and no old-account upload resumption. Plan a
    preservation-safe migration only after the deployed contract is known.
14. **Captured inline words merge (Sol S19).** `native/src/capture.js:86–89`
    drops whitespace-only nodes, so `<b>one</b> <i>two</i>` becomes `onetwo`.
    Preserve a collapsed separator at inline boundaries within the UTF-8 budget.
    Run real-engine fixtures for emphasis, links, spans, blocks, preformatted
    text, multibyte text and a separator at the byte limit; search for the
    original separate words through fake Hister.
15. **A hidden field can replace the visible IME owner (Sol S20).**
    `native/src/engine.rs:1091–1117` updates its single `ime_owner` for any live
    view, while the coordinator ignores hidden-view IME events. Restrict ownership
    to the selected view or track it per view. Verify hidden autofocus followed
    by visible blur hides the correct keyboard; test delayed composition during
    tab switching, closure and generation replacement.
16. **Large Retry-After values overflow before clamping.**
    `core/src/main/kotlin/ch/lkmc/combray/core/integration/DispatchResult.kt:48–51` multiplies parsed seconds by
    1,000 before bounding them. `Long.MAX_VALUE` seconds wraps negative and
    becomes a zero wait instead of the six-hour cap. Clamp the seconds before
    multiplication. Test huge, invalid, negative and normal values, preserving
    existing case-insensitive header handling and the documented HTTP-date limit.

## Performance

- `publish()` performs Room reads for every delivered event on reviewed main,
  including ignored diagnostics. The inert-event fix is offered in #24, not
  merged at consolidation. Paint reports are already limited to one per second;
  do not claim a 60-fps database bottleneck. After that fix, measure stateful
  title/load bursts before adding a snapshot cache or dirty flag. Count queries
  and verify real navigation/title updates still publish.
- `RecentSheet` day-grouping allocates a `Calendar` per row per list composition;
  reuse one instance or compute boundaries once if measured work justifies it.
  Search truncation is correctness item 10; it is separate from #25's collector
  optimization.
- `ServiceStore.create()` touches `AndroidKeyStore` on the main thread during
  `Application.onCreate`; make lazy if cold start regresses.
- Six-live-view cap evicts back stack and scroll position. Persisting per-tab
  scroll offset or revisit-on-restore would soften it; Servo cannot restore
  session history today.
- **Bound extraction before allocating (Sol S06/S20).** `reader.js:31` clones
  the whole DOM before Readability checks `maxElemsToParse`. `engine.rs:1433–1492`
  clones all select options/optgroups and dialog strings before `controls.rs`
  truncates JSON. Bound acquisition/preflight, not just output. A huge DOM or
  100,000-option picker must become bounded/unavailable without another complete
  copy on the paint/input thread. Page-controlled getters are not trusted bounds.
- **Actual cancellation:** capture timeouts abandon results without interrupting
  script execution. Measure capture and Readability separately, including hostile
  getters. Prefer trusted engine extraction or a genuinely interruptible path;
  do not describe a callback deadline as an execution limit.
- **JNI callback cost and failure:** `jni_api.rs:90–108` calls
  `NativeEngineHost.deliver` on the engine thread, where large JSON is parsed.
  Measure 256/512 KiB payload delivery. Test a throwing listener and subsequent
  events against the pinned JNI exception cleanup before changing it. If parsing
  moves off-thread, preserve event order and bound its queue.
- **Frame/battery baseline:** measure cold chrome, resident/evicted tab switches,
  scroll frame times, 1/10/30-tab memory, idle CPU, thermal behavior and background
  timers on a real GPU. The loop polls at 250ms and selection survives backgrounding.
  Use lifecycle evidence before introducing suspension, frame scheduling or a
  different live-view budget. Emulator timings are not phone performance budgets.
- **Profile/density:** existing limitations include synchronous profile writes
  and density read only at startup. Measure write-related stalls and exercise a
  display-size change before changing lifecycle behavior.

## Missing features and focused workflow slices

- Stop/abort load is blocked by the pinned embedding's API; revisit on engine
  updates. Text selection/copy, downloads, permissions and site-data controls
  need explicit engine and privacy policy. Pinch touches are forwarded but pinch
  behavior remains unverified. Prioritize the owner's essential sites.
- **Find in page (Sol S10):** a bounded, document-scoped Servo search path with
  bottom search/count/next/previous/dismiss controls. Cover Unicode, many/no matches,
  large/changing documents and Back/IME. No full-page copy on every keystroke and
  no Hister/Kagi request for local page search.
- Share should include the page title (`title\nurl`) — `share()` in the app
  module sends only the URL today.
- Long-press the address chip → copy the full URL.
  Keep a visible accessible alternative. Confirm link/image-address copying on
  API 30–32, which lacks the newer system clipboard preview; avoid double feedback.
- Recent: "Close all tabs" / "Close other tabs"; swipe-to-close or swipe-to-forget
  rows. Keep closing, forgetting and remote deletion distinct, with an accessible
  non-gesture path and clear scope/Undo behavior.
- Quick tab switch: long-press Recent → focus the previously-focused tab (the
  runner-up by `lastFocusedAt` is already queryable).
  Treat this and an optional address swipe as one **Return ticket** experiment;
  include a visible action and avoid fighting Android Back or page gestures.
- Reader view: font-size control; per-host "always open reader" list (reuses the
  exclusion-list settings idiom).
  Add a **Reading seam** only for the same tab/document: preserve Reader scroll
  across a brief departure and invalidate it when content is replaced.
- Home surface: show open tabs or the most recent visits — the "Recent home"
  design alternative can be trialed here without committing to it.
- Favicons in Recent rows and the address chip — only if the embedder API
  surfaces them; do not fetch page icons through another stack.
- Settings: theme choice (system / light / dark) and optionally Material You
  dynamic color on Android 12+, if the owner wants an override. Keep pine/paper
  as the deliberate default and never forcibly recolor arbitrary websites.
- **Save receipt (Sol S11):** show acknowledged destination/list and distinguish
  Pending, Saved and Needs attention in text as well as color. Add direct queue
  Retry/details. Preserve original payload and destination through switching and
  response loss; bookmark creation and failed list membership are separate facts.
  Map safe error categories consistently to useful copy, rather than exposing
  one specially translated code alongside otherwise raw queue errors.

## Visual and layout notes

- The complete palette is proposed in #26. Reviewed main still partially overrides
  Material defaults (`Theme.kt`), leaving unrelated container/outline roles.
  Preserve the pine/ivory direction and validate actual sheets, chips, dialogs,
  disabled/errors, Reader and system bars in both modes. Check text and non-text
  contrast; do not count an open palette PR as completed theming.
- Empty states (Recent empty, Home) are plain text — a light glyph or tighter
  copy would carry the design further.
- Sheets: verify IME insets and focus timing on device (above).
- **Bottom-bar legibility:** five 48dp actions plus padding leave roughly 64dp
  for the address surface at 320dp width, before its icon and internal padding.
  Measure the visible origin at narrow widths and large type. A responsive
  full-width address row above the action row is an option; preserve the owner's
  explicit bottom Share action and all 48dp targets. Do not solve crowding by
  shrinking targets or showing only an ambiguous domain suffix.
- Landscape remains unverified. Check bar, page dialogs, Reader, sheets and the
  keyboard at narrow widths/200% text, including last list rows and pagination.
  An unweighted LazyColumn alone does not prove clipping; reproduce before
  applying blanket weight/fill changes.
- Recent already has date separators, active/open text markers, local filtering
  and a scrolling scope strip. Improve hierarchy rather than rebuilding them.
  Open tabs needs a scope-specific empty message; the current copy also mentions
  history that this scope hides. Cover duplicate tabs and TalkBack labels.
- Keep system typography in chrome and a restrained reading measure in Reader.
  Measure the snackbar against the actual bar height rather than assuming a
  fixed offset is correct at every font scale.

## Delightful/novel ideas

- Pending-dot on the Save icon when outbox saves/captures are queued.
  Pending already has a spinner/live-region label. Add a queue-wide dot only if
  it conveys another useful state; do not imply capture acceptance means indexing.
- Long-press Save → choose list + note before enqueue (Karakeep lists already
  loaded in settings).
- "Resume" row on Home: most-recent visit plus open tabs.
  Combine with a quiet wordmark/search/Recent home experiment, not a busy dashboard.
- Session trails: group history rows by originating tab ("how I got here").
- Time buckets finer than days in Recent ("this morning").
- Offline-flush summary: a snackbar when a drain empties the queue after being
  offline. Coalesce it; do not interrupt every automatic capture or claim it
  proves downstream crawl/index completion.
- **Madeleine:** explicitly send a selected phrase to the existing Hister scope
  for recall. No passive remote typing requests and no additional AI service.
- **Named Undo:** `Notice.TabClosed` already carries a title. Show a bounded title
  with a generic fallback while preserving tab identity and a reachable Undo.
- **Reader time:** a quiet, local approximate reading duration alongside byline
  metadata. Label the estimate; do not assume an English word rate fits all text.
- Address swipes belong to the Return ticket experiment above. Pull-to-refresh
  fights Servo's surface. Keep reduced motion and an accessible visible route
  for any gesture; avoid decorative animations/haptics on routine actions.

## Verification gaps to close with the owner

- arm64 physical device: stale-frame check (M0 SwiftShader artifact), pinch zoom,
  keyboard behavior on real pages, dark mode + landscape sweep.
  The stale-frame reproduction in `docs/ENGINE_SPIKE.md` section 6 compares
  pixels with the selected document after view churn/navigation. Repeat after
  eviction, backgrounding and rotation on a real GPU; a new URL alone is no proof.
- Live Karakeep/Hister/Kagi: one deliberate save and capture, then clean up.
- Accessibility pass on Recent and Settings (device checks cover other screens).
- Personal signing key + native build in CI before any release tag.
  Release CI currently builds no Servo and its packaging gate rejects that APK.
  Verify an engine-bearing, signed arm64 artifact, clean install, source/notices
  and the owner-approved sites before declaring daily-use readiness.
- Default `scripts/check-app.sh` omits `ReaderTest` (Sol S04). Include it and
  guard default test selection against omission while preserving explicit-class
  runs. A future isolated-run option should parameterize fixture ports so
  simultaneous local test sessions do not accidentally share a fake service.
- Review inherited native features (test bindings, Bluetooth, WebXR/WebGPU) and
  retain a small patch/update path. Change features only with supported builds
  and compatibility evidence. Content shares the app process; no content-sandbox
  or private-browsing guarantee is established.
- Android-only `native/src/engine.rs` has no host unit coverage. Extract focused
  ownership/attribution rules when touching them; retain real-engine tests.
  Production startup is serialized, but the direct native singleton publication
  path also needs a two-caller race check before supporting additional callers.

## Process notes for the next agent

- `python3 scripts/check_docs.py` and `git diff --check` always;
  `./gradlew testDebugUnitTest lintDebug assembleDebug` for Kotlin;
  `cargo fmt --check && cargo test --lib` for `native/`.
  App/engine behavior also needs `scripts/build-native.sh` and device fixtures;
  record exact commands, toolchains, failures and limitations in
  `docs/ENGINE_SPIKE.md`. Run Cargo through the pinned rustup proxy as the build
  environment does, and prepare Servo before host tests resolve its path dependency.
- A worktree may carry an uncommitted `native/src/engine.rs` change enabling
  IntersectionObserver (the `enable-intersection-observer` branch's in-flight
  work — not ours). Leave it out of unrelated commits.
- Set `ANDROID_HOME` to the actual SDK. The Sol run used
  `/home/paseo/android-sdk`; `/home/paseo/Android/Sdk` is only a default location.
- Keep this backlog consolidated. Sol S03/S09/S17 overlap the existing inert-event,
  palette and budget work; do not start competing implementations. S01/S13/S14/S15
  are offered in the four Sol PRs above. Their open status is intentional.
- Preserve URL/document identity and credentials at every boundary. Avoid
  unescaped SQL LIKE for local search, token-based account identities, or
  timestamps presented as a server latest-wins guarantee.
