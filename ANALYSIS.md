# Combray — analysis and future work

Shovel-ready list for the next session. Consolidated 21 October 2026 from the
earlier roadmap (`SWE2.md`), `Grok.md` (discarded after its merge),
`GeminiFlash.md`, and the [Sol review](https://github.com/L-K-M/Combray/pull/28).
Sol's source citations refer to `e5cf8ed`; its handoff records later verification. Read
`AGENTS.md` and [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) before
editing. Keep Servo and the personal-browser scope. Private browsing, extensions
and ad blocking are separate proposals; Stop, downloads and connection-security
claims need a verified path through the pinned embedding.

State today: M0–M3 implemented, device-checked on an x86_64 emulator with fake
services only. No physical device, real Karakeep/Hister/Kagi endpoint, or live
site has been exercised in the recorded acceptance evidence. Servo v0.6.0 is
mandatory. Its pinned embedding has no Stop API or JavaScript interruption.
IntersectionObserver enablement has landed on main (`ba4f947`); it is no longer
in-flight work.

Prioritize the prepared privacy/state fixes, stale-frame correctness, input and
bounded extraction; then readable chrome, accessibility and theme validation.
An open PR is not an implemented feature on main. Remove completed work only
after it lands and its acceptance evidence is recorded. New ideas below are
proposals, not additional confirmed requirements.

## Already in open pull requests

Leave these alone unless the owner declines them. Rationale stays in the PR.
Other contributors' descriptions are retained from the roadmap; their PRs were
not inspected during the Sol pass.

Earlier session:

- #24 skip `publish()` on state-inert engine events. New `EngineEvent` subtypes
  must be placed or the when fails to compile.
- #25 Recent filters in memory. `RecentItem.matches` is on the model.
- #26 full Material 3 roles for both modes. Light `outline` is about 3.5:1.
- #27 floating reader close button.
- #29 outbox lease 60 s to 150 s. Worst-case dispatch is about 62 s plus DNS.
- #30 capture-budget flag derived from the live queue.

Additional recorded work, not merged:

- #39 percent-encoded credential names (`%74oken`). A `%` that is not two hex
  digits never reaches the decoder: `java.net.URI` rejects it, and an unparseable
  URL is already not sent. Do not treat `#50%-off` as a new case.
- #41 forgetting a site deletes the visit an open tab is showing, and clears
  `currentVisitId`, so the page does not return to Recent after you leave it.
- #43 no lock and no "encrypted" claim from an `https` prefix. Cleartext HTTP
  still says not encrypted.
- #44 reader links use the address decision, including `mailto:` and `tel:`.
  A rejected link stays in the article.
- #45 a Hister search body with no result arrays, or a field of the wrong type,
  is `Malformed`, not an empty search.
- #46 a reader article is dropped if its view has crashed or been replaced.
- #47 bottom Back, on the first page of a tab opened by another app, returns to
  that app. Same call as system Back.
- #48 page info dismisses from an effect, not during composition.
- #49 engine refusal and crash logs are a category only. The message can be the
  page URL.
- #50 below 400 dp the address sits on its own row. Share stays in the bar.
- #40 key KarakeepPanel pagination LaunchedEffect on service identity (fingerprint/generation) so background sync or list changes do not reset pagination, wipe bookmarks, or jump back to page 1.

Sol's independent fixes, also left open for the owner:

| PR / branch | Scope and verification |
| --- | --- |
| [#31](https://github.com/L-K-M/Combray/pull/31) `sol/expire-abandoned-captures` | S13: expire abandoned Hister claims and recheck age inside every claim. Regression-first tests cover payload erasure, stale results, live leases and a drain crossing the cutoff. |
| [#32](https://github.com/L-K-M/Combray/pull/32) `sol/atomic-forgetting` | S14: local deletion and capture cancellation share one Room transaction. Failure injection covers joint rollback, visible failure, retry, subdomains/lookalikes, open-tab recovery and fresh integration objects. |
| [#34](https://github.com/L-K-M/Combray/pull/34) `sol/reader-navigation` | S01: accepted URL changes invalidate ready/pending Reader, including same-document/hash routing. Tests cover delayed results, same-address retention and hidden-tab independence. |
| [#35](https://github.com/L-K-M/Combray/pull/35) `sol/karakeep-list-receipt` | S15: a list-bound save needs a usable bookmark ID to finish. Malformed/missing-ID 200/201 receipts recover the same bookmark or need attention after ten attempts; standalone acknowledgment is preserved. |

Each Sol branch has regression-first evidence and passing local JVM tests, lint,
debug/release assembly, documentation and diff checks. See its exact PR revision
for CI/review status. These checks do not prove deployed service compatibility.

## Correctness still open

History/capture atomicity and abandoned-claim expiry are offered in #32 and #31;
do not implement them again. Connection labels, page-info dismissal, and
Karakeep-panel identity are offered in #43, #48 and #40. Validate the listed
source candidates before editing; none is a new device reproduction.

1. **A failed list add hides a bookmark Karakeep already stored.** After
   `createLink` succeeds, a list PUT that is not `Done` becomes the job result,
   and the saved-item row is written only on final `Done`. Record the bookmark
   when create succeeds. Retry only the list step. A `404` stays a visible
   failure. File: `Integrations.dispatchSave`. The plan says a list step must not
   mark the link unsaved. This is separate from #35's missing-ID fix. Test a
   known-ID creation followed by list 404/timeout: retain creation acknowledgment
   and identity, expose partial completion, and retry membership without another
   create. Keep list/destination choices and claim fencing explicit.
2. **Failed visits are detected by Servo error-page titles** ("Error loading
   page", "Certificate error") in `SERVO_ERROR_TITLES`. If Servo renames or
   localizes them, a failure becomes a history row. Prefer an engine load-failure
   signal. Until then, a device check must assert those titles. A normal page can
   choose either title too: test that it is not hidden from history or capture.
3. **The coordinator command channel is unbounded** (`Channel<suspend () -> Unit>`).
   A burst queues stale work. Bound it, or conflate publish work, and name the
   overflow policy. Preserve ordered history, controls and capture results; never
   silently drop them. Flood diagnostics/title updates, then verify a user command
   completes without corrupting accepted document identities.
4. **`Retention()` is built again in `SettingsSheet` for the history-days label.**
   Pass the configured instance from `AppGraph`, or the label will drift when
   retention becomes a setting. Do this when exposing retention choices, not as
   speculative configuration plumbing.
5. **Settings save and connection check share one `message`.** A slow check can
   finish after a save and replace "Settings saved" with the old result. Give
   each request a generation, or show the two statuses apart.
    File: `SettingsSheet.kt`. Delay an old check until a new configuration saves;
    it must not replace the new status or suggest a wrong server is connected.
6. **Failed save rows are never pruned.** `observeOpenJobs` loads every `FAILED`
   row. Page the list. Keep the rows until the user retries or discards them.
    File: `IntegrationDao.kt`. Verify paging and Retry/Discard with a large failed
    queue; do not automatically erase explicit saves to reduce row count.
7. **Verify file-picker path boundaries.** Kotlin copies
    uploads into an app directory. Rust forwards the returned `PathBuf`s to
    Servo. Verify staging, traversal/symlink rejection and the trusted Kotlin-to-JNI
    boundary before adding a Rust guard. If needed, reject paths outside the
    staging root without breaking multiple uploads. Files: `PageControls.kt`,
    `UploadStaging.kt`, `native/src/controls.rs`. No page-to-JNI path is established.
8. **Local search misses retained visits beyond the newest 500 (Sol S02).**
    `BrowserCoordinator.recent(query)` limits `BrowserDao.observeHistory` before
    filtering title/URL. Search before limiting. Seed more than 500 retained visits
    and find the oldest unique match; cover Unicode, literal `%`/`_`, failed visits
    and current-open-visit deduplication. Keep the unfiltered list bounded. #25's
    collector optimization does not solve this.
9. **Service loading and queue rejection states (Sol S17).**
    `HisterSearchPanel`/`KarakeepPanel` initially collect configuration as null,
    flashing a false setup prompt. Delay the first configured emission and verify
    no false prompt. Budget warnings must clear after drain/discard without another
    capture. Include a rejected body exceeding remaining space while queued bytes
    are still below the cap; `queuedBytes >= limit` alone misses that case (#30).
10. **A service root is not an account identity (Sol S18).** Root fingerprints
    can reuse Saved status across accounts on one server. Key rotation pauses jobs
    but does not identify an account. Establish a supported read-only identity, or
    make replacement explicit and isolate old state. Do not invent an endpoint or
    use token hashes as account IDs. Test two fixture accounts on one host, same-
    account rotation, status separation and no old-account resumption. Plan a
    preservation-safe migration after the deployed contract is known.
11. **Captured inline words merge (Sol S19).** `native/src/capture.js:86–89`
    drops whitespace-only nodes: `<b>one</b> <i>two</i>` becomes `onetwo`.
    Preserve collapsed inline separators within the UTF-8 budget. Run real-engine
    fixtures for spans, links, emphasis, blocks, preformatted/multibyte text and a
    separator at the byte limit; search for the original words through fake Hister.
12. **A hidden field can replace the visible IME owner (Sol S20).**
    `native/src/engine.rs:1091–1117` updates one `ime_owner` for any live view,
    while the coordinator ignores hidden-view IME events. Restrict ownership to
    the selected view or track it per view. Verify hidden autofocus followed by
    visible blur hides the correct keyboard, including closure/generation changes.
13. **Process-lifetime maps grow.** `HisterIntegration.forgotten`,
    `LiveView.documentVisits`, and native document/retired maps grow across
    navigation/forget loops. Six live views do not bound documents in one long-
    lived tab. Measure growth, then prune obsolete identities only after proving
    back/forward attribution and capture/cancellation guards remain valid.
14. **Large Retry-After values overflow before clamping.**
    `integration/DispatchResult.kt:48–51` multiplies seconds by 1,000 first.
    `Long.MAX_VALUE` wraps negative and yields zero delay, not the six-hour cap.
    Clamp before multiplication. Test huge/invalid/negative/ordinary values,
    case-insensitive headers and the documented HTTP-date limitation.
15. **External scheme handling for standard mobile protocols**:
    `AddressPolicy.EXTERNAL_SCHEMES` currently only allows `mailto:` and `tel:`.
    Adding standard mobile schemes (`sms:`, `geo:`, `market:`) allows opening them
    via user confirmation in external native apps instead of triggering `UNSUPPORTED_SCHEME`.
    Keep these optional. Test absent handlers and deliberate user activation;
    unsafe `intent:`, `javascript:`, file/content schemes stay rejected.

## Performance and engine

Do not claim a device reproduction for these. They are from the bridge and
`docs/ENGINE_SPIKE.md`.

1. **Surface teardown can wait five seconds on the UI thread, and a failed
   unbind can be reported as success.** `EngineSurfaceView.surfaceDestroyed`
   calls `send_and_wait` with `ACK_TIMEOUT` of 5 seconds. Acknowledge release
   only after detach. Do not bind the next window until that is true. A timeout
   is a failure, not a release. Files: `EngineSurfaceView.kt`,
   `native/src/engine.rs`, `native/src/jni_api.rs`.
2. **Touch queue, coalescing and tail delivery.** Trace rapid two-pointer input
   through `EngineSurfaceView.kt`, `native/src/commands.rs` and `engine.rs`.
   The engine handles at most 64 commands per turn. If coalescing/throttling is
   needed, keep the newest move per pointer, flush final coordinates even without
   another move, and preserve down/up/cancel order. Reproduce any claimed existing
   throttle before adding a timer. Preserve the long-press timing fix already in
   `LongPress.kt`: measure from when the app handles the press, not a queued event's
   timestamp (documented device runs `controls7`/`browsing11`).
3. **Reader mode clones the whole document before the node budget.**
   `native/src/reader.js` calls `document.cloneNode(true)`, then Readability's
   `maxElemsToParse`. Refuse an oversized tree before the clone. The script
   deadline does not interrupt a running script. Prefer trusted engine extraction
   or a genuinely interruptible path; page-controlled getters are not trusted
   execution bounds. Measure ordinary and hostile capture/Readability separately.
4. **Keyboard text goes to whichever tab is active when the command runs.**
   `ImeCommit` carries only the string. `ime_commit` uses `active_webview()`.
   Carry the view generation and drop the commit if that view is no longer the
   one with the field.
5. **`publish()` Room work.** Reviewed main still publishes ignored events; #24
   offers the inert-event fix. Paint reports are already limited to one per
   second, not 60 per second. After that lands, measure stateful title/load bursts
   before adding a snapshot/dirty flag. Count queries and verify real navigation
   and title events still publish.
6. **Recent day-grouping allocates a `Calendar` per row.** Reuse one instance, or
   compute the day boundaries once if profiling warrants it. Search truncation
   is correctness item 8, separate from collector/grouping cost.
7. **Measure startup before changing initialization.** The actual
   `KeystoreSecretStore` constructor creates its directory; `key()` loads
   AndroidKeyStore lazily during credential access, not automatically in
   `Application.onCreate`. Trace cold/warm startup and queued credential reads;
   the earlier `ServiceStore.create()` attribution was not supported by source.
8. **The six-live-view cap drops back-stack and scroll.** Restoring a tab reloads
   the URL only. Servo cannot restore session history today. Do not invent a
   session format. A stored scroll offset is optional and must say what is lost.
9. **Emulator cost, not a phone budget.** About 141 MiB for two small tabs, 156
   MiB for ten, 840 MiB after roughly fifteen cached documents and two large
   pages. A 20,000-node capture took 10 to 20 seconds. Shutdown has taken 15 to
   157 seconds. SwiftShader on x86_64. No budget has been set.
10. **Profile writes block the engine thread** (patch 0004, every five minutes and
    on leaving the app). Measure write-related stalls during a fling. Density is
    also read only at startup: exercise a display-size change within one process.
11. **`openTabCount()` query optimization**: `publish()` currently runs
    `dao.openTabs().size`, which executes a full `SELECT * FROM tabs WHERE state = 'OPEN'`
    loading all `TabEntity` rows into memory on every stateful coordinator event.
    Replace with `@Query("SELECT COUNT(*) FROM tabs WHERE state = 'OPEN'") suspend fun openTabCount(): Int`
    if measurements justify it. Prefer the count query before introducing another
    cache; cached active IDs/counts must stay transactional with tab state.
12. **Bound acquisition before serialization (Sol S20).**
    `engine.rs:1433–1492` clones select options/optgroups and dialog strings before
    `controls.rs` truncates JSON. Limit acquisition, including selected IDs and
    messages. A 100,000-option picker must not allocate another entire options
    tree on the paint/input thread before refusing or presenting bounded content.
13. **JNI delivery and frame/battery baseline (Sol S20).**
    JSON parsing/listener delivery run on the engine thread (`jni_api.rs:90–108`,
    `NativeEngineHost.kt:33–42`). Measure 256/512 KiB capture events and test a
    throwing listener plus subsequent delivery against pinned JNI exception cleanup.
    Moving parsing off-thread requires ordered events and a bounded queue.
    Measure cold chrome, resident/evicted switches, scroll frames, 1/10/30-tab
    memory, idle CPU, thermals and background timers on a real GPU. The loop polls
    at 250ms and selection survives backgrounding; use lifecycle evidence before
    changing suspension, frame scheduling or the live-view budget.
14. **History index candidate.** For `BrowserDao.observeHistory`, run EXPLAIN QUERY
    PLAN and timing on thousands of good/failed visits. Add a composite
    `visits(failed, lastSeenAt)` index in `BrowserEntities.kt` only if it helps
    beyond existing indexes. A Room migration must preserve tabs, visits and jobs.

## Chrome still open

- Recent chips (Open tabs, All, Hister, Karakeep) are one horizontal scroller
  with no overflow cue. On a narrow phone the last chips start off-screen. Wrap
  to two lines under a narrow width, or fade the trailing edge.
  File: `RecentSheet.kt`.
  Date separators, active/open text markers and local filtering already exist;
  improve hierarchy rather than rebuilding them. Open tabs needs its own empty
  message instead of advertising the history that scope hides. Cover duplicate
  tabs, last rows/pagination, long labels, keyboard and 200% text with TalkBack.
  Local typing stays local; remote requests require an explicit Hister/Karakeep
  scope. Keep local, long-term and web search distinguishable.
- Tab badge shows the raw count. Cap the display at "99+" but announce the real
  count. Check 0/1/99/100 without changing logical state.
- Share sends only the URL. Send the title and the address (`title` then the
  URL). File: `share()` in the app module.
- Long-press the address chip to copy the full URL, with a short "Copied" and a
  TalkBack announcement.
  Keep a visible alternative. Also confirm link/image-address copying on API
  30–32; avoid duplicate notifications beside newer Android's clipboard preview.
- Recent row menu has Remove and Forget. Add Copy link and Share there, using
  the clipboard and chooser the page UI already uses.
- The IME bridge does not implement `getTextBeforeCursor` or `getCursorCapsMode`.
  Composition preview/selection sync are absent; deletion repeats key events.
  Test AOSP/Gboard, CJK, correction, emoji/surrogate pairs and large ranges.
  Implement bounded, view-attributed edits or decline unsupported requests;
  silently clipping a requested edit is incorrect.
- Address-sheet focus is requested in `LaunchedEffect` and may run before the
  sheet window is laid out. Confirm the keyboard opens and the field clears it
  on a device.
- Empty states (home, empty Recent) are plain text. One line of useful copy is
  enough. Do not add decoration that competes with the page.
- The complete palette is offered in #26; reviewed main still partially overrides
  Material defaults. Preserve warm paper/pine and charcoal surfaces, system type
  in chrome and a restrained Reader measure. Verify sheets, chips, outlines,
  disabled/errors, dialogs, Reader and system bars in both modes, including text
  and non-text contrast. Landscape/large text on Recent and Settings is unverified.
- A theme override (system / light / dark) is not built. System appearance is
  the default. Add an override only if the owner wants the phone and the room
  to disagree.
  Material You dynamic color on Android 12+ is another optional variant; keep
  pine/paper as the deliberate default.
- Touch targets: Audit icon buttons in `BottomBar` and `RecentSheet` to ensure
  48x48dp minimum interactive component size (`Modifier.minimumInteractiveComponentSize()`).
  Bottom-bar checks already exist; prioritize Recent and Settings and measure
  non-overlap before adding blanket modifiers. Five 48dp actions leave roughly
  64dp for the address surface at 320dp, before its icon/padding. Verify #50's
  proposed full-width address row preserves readable origin, Share and target sizes.
- Soft keyboard layout stability: verify existing `imePadding`, focus timing and
  viewport resizing before adding animated insets. Respect reduced motion.
  Measure snackbar/bar bounds; its fixed 72dp clearance may not fit every font.
- OLED pitch-black option: true `#000000` surfaces may suit an AMOLED owner.
  Treat it as optional; measure comfort/contrast and device power rather than
  promising a battery improvement. Do not recolor arbitrary webpages.
- Configurable retention period: Expose history retention in `SettingsSheet`
  (1 day, 7 days, 30 days, 90 days, Forever) backed by `BrowserSettings`.
  This is a proposal. Keep the seven-day default, explain local versus remote
  expiry and unused-tab behavior, and prove migration/fake-clock preservation.
- Save receipt (Sol S11): show acknowledged destination/list and distinguish
  Pending, Saved, partial list completion and Needs attention in text as well
  as color. Add direct queue Retry/details while preserving original payload
  and destination through switching/response loss. Map safe error categories
  consistently to useful copy. Localize generic coordinator failures through a
  typed notice mapped to app resources, not resource IDs in the core domain.

Measure landscape, keyboard and 200% text before changing list sizing. An
unweighted LazyColumn alone does not prove clipping; avoid blanket weight fixes
without reproducing the last-row/pagination problem.

## Ideas, not Chromium parity

- The Madeleine: user-invoked recall through the existing Hister scope, using a
  selected phrase or, if the deployed API supports it, "on this day" last week/
  month. The literary reference is Proust's recollection. No passive remote typing
  requests or added AI service; date-aware recall must not invent a server query.
- Distraction-free Zen Mode: Auto-hiding bottom bar on downward scroll; instant
  slide-back on slight upward scroll.
- Swipe up on bottom bar to open Recent sheet: Fluid gesture opening Recent/Tabs
  from anywhere on the bottom navigation bar.
  Keep an accessible visible route and avoid Android's system gesture region.

- Long-press Recent to focus the previously focused tab. `lastFocusedAt` already
  orders them.
  Combine with optional address swipes as one Return ticket experiment; keep a
  visible action and avoid fighting Back or the page's gestures.
- A pending mark on Save when the outbox still has work, separate from the
  per-page star.
  Per-page Pending already has a spinner/live-region label; add another mark only
  for distinct useful information. Capture acknowledgment is not proof of indexing.
- On the new-tab surface, one line: the last page you can reopen, and the open
  tabs. That is the Recent-home idea in miniature. Do not retire the sheet.
  A quiet wordmark/search/Recent variant is another experiment; the owner has not
  selected a final Recent-home layout. Avoid a busy dashboard.
- Reader text size, A− / A+, remembered locally. Body text is fixed at 18 sp.
  Not a theme system.
  Reading seam: preserve Reader scroll across a brief departure only for the
  same tab/document; invalidate it when content is replaced. A quiet local reading-
  time estimate can join byline metadata; do not assume an English word rate.
- A snackbar when a drain finishes a queue that was waiting offline. The outbox
  already knows the count.
  Coalesce it; do not interrupt each automatic capture or imply downstream
  crawl/index completion.
- Group history by the tab that visited it, as a projection. Identities stay.
  The plan already allows grouping.
- Long-press Save to pick a list and a note before enqueue. Lists are already
  loaded in settings.
- Time buckets finer than days in Recent ("this morning").
- Close all tabs, or close other tabs, from Recent. A swipe may be extra. It
  must not be the only way to close a tab.
- Favicons in Recent or the address chip only if the embedder already has them.
  Do not fetch icons through another stack.
- A compact, non-color-only mark for whether this page will be captured
  (on, paused, excluded). Label eligibility/acceptance accurately; Hister's
  acknowledgment alone does not prove searchable indexing.
- Named Undo: `Notice.TabClosed` carries a title. Show a bounded title with an
  untitled fallback, preserving exact tab identity and a reachable Undo action.

Skip: pull-to-refresh (it fights the surface), edge-swipe as the only close,
and any second renderer. Keep reduced motion and avoid decorative animation or
haptics on every routine action.

## Blocked on the pinned engine

- Stop. More shows Reload on purpose.
- Downloads. The reviewed bridge has no download event. Confirm a Servo hook
  before adding a JNI method. Do not invent one.
- Camera, microphone, location. Every permission request is denied. That is the
  safe default. A prompt needs a tagged request, the origin, and the Android
  runtime permission first.
- Text selection, pinch zoom, find in page. Not exercised. Do not add a gesture
  that fights the surface.
  Pinch touches are already forwarded; verify behavior before adding another
  gesture recognizer. Find in page deserves a bounded, document-scoped engine
  path with bottom search/count/next/previous/dismiss controls. Test Unicode,
  many/no matches, large/changing documents and Back/IME. No full-page copy on
  every keystroke and no Hister/Kagi request for local page search. Site-data
  controls also need verified engine storage and privacy semantics.

## Verification still open

- arm64 physical device: the stale-frame check (seen only under SwiftShader),
  pinch zoom, keyboard on a real page, dark mode, landscape, large font.
- One deliberate save and capture against the real services, then clean up.
  Do not export personal history to validate a fixture.
- Accessibility pass on Recent and Settings. Other screens have device checks.
- Personal signing key and a native build in CI before any release tag.

The stale-frame reproduction in `docs/ENGINE_SPIKE.md` section 6 compares pixels
with the selected document after view churn/navigation. Repeat after eviction,
backgrounding and rotation on a real GPU; a new URL alone proves no paint.
Release CI builds no Servo and rejects that APK at packaging. Verify a signed,
engine-bearing arm64 artifact, clean install, source/notices and essential sites.

Default `scripts/check-app.sh` omits `ReaderTest` (Sol S04). Include it and guard
default test selection while preserving explicit-class runs. Parameterize fixture
ports for isolated simultaneous sessions rather than sharing a fake service.

Review inherited native features (test bindings, Bluetooth, WebXR/WebGPU) and
retain a small patch/update path. Change features only with supported builds and
compatibility evidence. Content shares the app process; no content sandbox or
private-browsing guarantee is established. Android-only `engine.rs` has no host
unit coverage: extract focused ownership/attribution rules when touching them and
keep real-engine checks. Before adding native startup callers, test concurrent
singleton publication; production startup is serialized today.

Sol validation: native x86_64 build passed in 16m11s, 20 native host tests and
29 script tests passed. Two complete Reader device runs each passed 2/3 checks;
a confirmed System UI ANR intercepted Back. A later recovery attempt timed out
and was stopped. This is an incomplete device gate, not phone performance or a
completed visual/accessibility pass. Exact commands/limits are in the report PR's
`docs/ENGINE_SPIKE.md`, section 14.

## How to check

- `python3 scripts/check_docs.py` and `git diff --check` always.
- Kotlin: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- `native/`: `cargo fmt --check` and `cargo test --lib`.
- A worktree may hold another model's uncommitted files, including
  `native/src/engine.rs` or `ReaderView.kt`. Do not stage them.
- Other models have open pull requests. Do not read or edit those unless the
  owner asks. Do not start a second patch for an item listed under open pull
  requests above.
- App/engine behavior needs `scripts/build-native.sh` and device fixtures;
  record commands, toolchains, failures and limits in `docs/ENGINE_SPIKE.md`.
  Use the pinned rustup proxy; prepare Servo before host path-dependency tests.
- Set `ANDROID_HOME` to the actual SDK. The Sol run used
  `/home/paseo/android-sdk`; `/home/paseo/Android/Sdk` is only a default location.
- Keep this backlog consolidated. Sol S03/S09/S17 overlap the existing inert-event,
  palette and budget work; do not start competing implementations. S01/S13/S14/S15
  are offered in the four Sol PRs above. Their open status is intentional.
- Preserve URL/document identity and credentials at every boundary. Avoid
  unescaped SQL LIKE for local search, token-based account identities, or
  timestamps presented as a server latest-wins guarantee.
- Add policy tests for pasted URL tabs/newlines before changing normalization;
  blindly replacing every whitespace character with a space can change meaning.
  Count ambiguous attempts conservatively: counting only returned failures can
  permit endless resends after repeated response loss. Non-positive Kotlin
  `repeat` counts do not establish a crash; the input concern is positive-range
  work and correct editing semantics.
