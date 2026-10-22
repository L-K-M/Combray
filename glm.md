# Combray — GLM review

An independent, full read of the repository at `origin/main` (4105c28, 21 October
2026): `app/`, `core/`, `engine/`, `native/` sources, the Room schema, the
manifest, the scripts, and the project docs. `./gradlew testDebugUnitTest
lintDebug` passes clean on that revision (run in a fresh worktree, 132 tasks).
Nothing here was taken from other agents' in-flight work; items already claimed
by an open PR are marked so the next agent does not duplicate them. This file is
the review record; `ANALYSIS.md` carries the living consolidated backlog.

Overall: this is an unusually disciplined codebase. The identity model (tab /
visit / document / native view), the fenced outbox, and the capture policy
re-checks are better than most shipping browsers' internals. The weaknesses are
almost all at the surface: a chrome that is functionally complete but visually
shy, a handful of small Compose-correctness slips, and shortcuts the product
could give its single user almost for free.

## Bugs

1. **`PageInfoDialog` dismisses during composition.**
   `BrowserScreen.kt:284` — `Sheet.PAGE_INFO -> tab?.url?.let { PageInfoDialog(...) } ?: dismiss()`
   calls `dismiss()` (a write to `sheet`) inside composition. When the focused
   tab loses its URL while the dialog is open, this writes state while
   composing — undefined behavior territory and a recomposition loop risk.
   Fix: compose the dialog only when a URL exists; leave dismissal to the
   callback. (Offered in #105; same topic as #48/#99.)

2. **Save reports "sensitive" before "not set up".**
   `Integrations.kt:247` — `save()` checks `SensitiveUrls.looksSensitive(url)`
   before checking that Karakeep is configured. A first-run user who taps Save
   on a token-bearing URL is told the address was withheld for safety, when the
   actual state is "no service configured". The configuration check should come
   first; the sensitive guard only matters when a request would otherwise be
   queued. (Offered in #106.)

3. **Recent's relative times go stale while the sheet is open.**
   `RecentSheet.kt:90` — `now` is read once per composition. Rows say "5
   minutes ago" for an hour if the sheet stays open; item keys prevent
   recomposition of already-composed rows on scroll. A cheap fix is recomputing
   `now` per item (in `RecentRow`) or on a slow ticker. (Folded into
   ANALYSIS.md's chrome section.)

4. **Tab-count badge renders the raw number.** `BottomBar.kt:80` — three-digit
   counts ("100") do not fit the badge and overflow its shape. Cap at "99+".
   (Offered in #108.)

5. **Engine "starting" overlay flashes over the restored page.** On cold start
   with a restored tab, `PageOverlay` paints "Starting the engine…" over the
   whole page area (`BrowserScreen.kt:320`) until the engine reports ready. A
   restored tab will paint shortly; showing the home surface or nothing for
   that sub-second window would flicker less. (Folded into ANALYSIS.md.)

6. **Snackbar offset assumes a 64 dp bar.** `BrowserScreen.kt:223` hardcodes
   `padding(bottom = 72.dp)`. With font scaling the bar's height grows
   (`heightIn(min = 64.dp)` is a minimum) and the snackbar can overlap actions.
   (Already recorded in the consolidated backlog.)

7. **Display density is captured once per process.** `AppGraph` reads
   `displayMetrics.density` at `Application.onCreate` and hands it to
   `NativeEngineHost`. On a fold opening/closing or a display switch, pages
   render at the stale density until the process dies. Engine-side work needed
   (a density update command); record as a limitation. (Already recorded.)

8. **Known engine-level defects** (documented in `docs/ENGINE_SPIKE.md`, kept
   honestly open): stale-frame defect on multi-tab view creation, no
   interruption API (no Stop), script-capture timeouts that abandon but do not
   cancel, cookie writes at most five minutes behind. Not re-litigated here.

9. **Failed-load detection keys on Servo's error-page titles**
   (`BrowserCoordinator.SERVO_ERROR_TITLES`). If Servo renames or localizes
   `neterror.html`, failed loads become successful history rows. Needs an
   engine-level failure signal upstream. (Already recorded.)

10. **Minimal IME bridge.** `EngineInputConnection` implements composition,
    deletion and key forwarding only; `getTextBeforeCursor`,
    `getCursorCapsMode`, selection sync are absent, so autocorrect and gesture
    keyboards see an empty field. Known; functional today, worth hardening with
    a physical device. (Already recorded, expanded by Fusion/Muse.)

## General issues

- **`BrowserCoordinator.publish()` still does two–three Room reads per stateful
  event** (`activeTab()`, `openTabs().size`). PR #24 (and #66) skip inert
  events; the remaining hot path is fine at current event volume but is the
  first place to look if chrome updates ever show up in profiling.
- **The coordinator's command channel is unbounded**
  (`Channel<suspend () -> Unit>`). Bursts (fast tab switching during a load)
  queue stale work that each runs to completion. A conflated publish-only lane
  or a bounded channel with a deliberate overflow policy would bound latency.
- **`Retention()` is instantiated in three places** (default constructor in the
  coordinator, `SettingsSheet.kt:160`, none shared). When retention becomes a
  setting, the Settings label and maintenance will diverge. Pass one configured
  instance through `AppGraph`. (Offered in #110.)
- **Process-lifetime maps grow slowly**: `HisterIntegration.forgotten`
  tombstones are never pruned; `LiveView.documentVisits` is bounded by the
  six-live-view cap. Harmless today; worth a sweeping prune when retention
  becomes configurable.
- **`onEngineError` with a live view but no request id is silently dropped**
  (`BrowserCoordinator.kt:735`). A refused `goBack`/`reload` gives the user no
  signal. (Covered by the consolidated lifecycle-edges item.)
- **`WorkManagerScheduler` uses `ExistingWorkPolicy.REPLACE`.** A schedule
  posted while the unique worker is between its final `drain()` and return can
  request cancellation of a worker that is already finishing. Benign today (the
  mutex serializes drains in-process); worth a comment or `APPEND` if
  multi-process dispatch ever arrives. (Folded into ANALYSIS.md.)
- **Privacy posture is genuinely good** — credentials are Keystore-sealed and
  fingerprint-bound, `allowBackup=false`, logs carry categories only, page
  content never reaches the browser's own UI as markup. No findings against
  the plan's section 7 rules; keep it that way.

## Performance

- Recent re-subscribes Room per keystroke — PR #25 (and #67) address this; the
  deeper win (observe once, filter in memory) also removes the 500-row
  `sortedByDescending` on every emission.
- Day headers allocate a `Calendar` per row (`RecentSheet.startOfDay`); reuse
  one instance or precompute boundaries. (Already recorded.)
- Cold start does Keystore + Room work on the main thread (`AppGraph`
  construction). Not measurable on the emulator; revisit with a physical
  device. (Already recorded, corrected by the Muse pass.)
- `publish()` Room reads per event (above).
- Six-live-view cap drops back stacks and scroll — inherent until Servo grows
  session serialization; per-tab scroll persistence is the cheap partial
  mitigation.
- Touch events are forwarded per-pointer per-move with no coalescing
  (`EngineSurfaceView.sendPointer`). Fine today; keep in mind if scroll jank
  appears on a real device. (Already recorded.)
- No stuttering observed or predicted above the engine; the real risk remains
  the engine's own SwiftShader/WR behavior that only a physical device can
  show.

## Missing features

Ordered by value-to-effort for a single user:

1. **Share should include the page title** (`title\nurl`). (Offered in #51 and
   #102; owner picks one.)
2. **Quick tab switch**: long-press the Recent button to focus the previously
   focused tab. (Offered in #109.)
3. **Close-all / close-others in Recent**, plus swipe-to-close (tabs) and
   swipe-to-forget (history rows). (#53 offers the swipe half.)
4. **Reader view font-size control** (A-/A+). (Offered in #52 with a three-way
   setting.)
5. **Copy-address affordance**: long-press the address chip copies the full
   URL. (Offered in #97.)
6. **Theme choice (system/light/dark)** and Material You dynamic color.
   (Offered in #52/#98/#55/#26/#62 in overlapping slices.)
7. **Pending indicator on Save when the outbox is non-empty**.
8. **Stop/abort load** — blocked upstream. Revisit per engine update; do not
   fake it.
9. **Find-in-page, text selection, pinch zoom verification, downloads** —
   check what the embedder API exposes before promising any of them. (#86/#87
   explore some of this.)
10. **Home surface**: the current home is two centered buttons; the "Recent
    home" alternative remains untried. (#42/#46 explore it.)

## Visual issues and layout

- The Material palette is incomplete outside primary/secondary-container; the
  purple-tinted M3 baseline shows on badges, chips and focus rings against the
  pine/ivory identity. (Offered across #26/#55/#62/#98.)
- Empty states are bare text; a small glyph or one-line call to action would
  carry the design further. (Already recorded.)
- Recent rows are typographically flat: no favicon/shape anchor, so many open
  tabs read as a wall of text. A 4 dp leading marker or the letter-tile idea
  would structure the list without new data. (Letter tiles are in ANALYSIS.md.)
- The More sheet conditionally hides Forward while Reload is always shown; the
  height changes between tabs and hurts muscle memory. Consider fixed rows
  with disabled states. (Folded into ANALYSIS.md.)
- Snackbar offset and engine-starting flash (bugs 6 and 5 above).
- Landscape and IME inset behavior passed the emulator checks; real-device
  keyboard focus timing and dark-mode contrast remain open verification gaps.

## Interface, aesthetics, theming

The product idea — quiet chrome, bottom controls, one Recent, the page central
— is right, and the discipline about not faking state (no decorative lock, no
fake Stop) is a strength to protect. Where it under-delivers is warmth and
feedback:

- **The address chip is the app's face** and it is a plain
  `secondaryContainer` pill. A subtle tone shift while loading, or the site's
  favicon slot reserved at 16 dp, would give it identity without noise.
- **Motion is absent**: sheets and dialogs pop. Small predictable tweens
  (row add/remove animate) would make tab close/reopen feel physical. Keep it
  off the page surface and respect reduced motion.
- **Reader view is the best-looking surface in the app** — measured line
  length, honest truncation note, RTL-aware quote bars. Use it as the
  typographic benchmark for the rest of the chrome.
- **The icon artwork (pine/ivory folded C) is strong**; the in-app palette
  should keep deriving from it. A matching dark-mode splash background avoids
  the white flash on cold start. (#78 offers the restored splash.)
- **Copy is clear and human** ("Reader view found no article on this page.").
  Keep the string file's tone; it is a feature.

## Delightful, novel, quirky ideas

- **Session trails**: group Recent history rows by originating tab ("how did I
  get here?") — the data model already keeps the tab linkage, so this is a
  projection, not a schema change. (In ANALYSIS.md.)
- **Offline-flush toast**: when the outbox drains to empty after being
  non-empty for a while, say so once. Guard it against announcing every
  foreground save. (In ANALYSIS.md.)
- **"Resume" row on Home**: the most recent visit plus open tabs. (In
  ANALYSIS.md; #42/#46 explore the surface.)
- **Finer time buckets in Recent** ("this morning", "last evening"). (In
  ANALYSIS.md.)
- **Long-press Save → choose list + note** before enqueueing. (In ANALYSIS.md.)
- **Reading position memory for reader view**, invalidated when content is
  replaced. (In ANALYSIS.md as the "reading seam".)
- **A "dwell" counter in page info**: approximate time spent on the page this
  visit, derived from focus transitions the coordinator already records.
  Quirky, private, and very Combray: it treats browsing as reading, not
  clicking. (Folded into ANALYSIS.md.)
- **Keyboard-friendly address chip swipe** to switch tabs; safe, unlike
  pull-to-refresh, which would fight Servo's surface. (In ANALYSIS.md as the
  Return ticket.)
- **Hister "back to this day"**: one-tap recall for pages visited exactly N
  days ago. (Covered by the Madeleine idea's "on this day" variant.)

## What this session implemented

Seven small, verified improvements, each its own branch and PR against main,
chosen to avoid the files/claims of the then-open PRs. All were green on
`testDebugUnitTest lintDebug`, `scripts/check_docs.py` and `git diff --check`,
and left open for the owner; review follow-ups (extracted `shareTitle()`,
whitespace collapse including Unicode separators, clearing the captured
page-info address on all dismissal paths, a `%1$d+` badge label derived from
its limit) were applied on the branches:

- #51 `glm/share-title` — share includes the page title.
- #105 `glm/page-info-dismiss` — no state write during composition in Page Info.
- #106 `glm/save-guard-order` — configuration check before the sensitive guard.
- #107 `glm/retry-after-overflow` — clamp `Retry-After` before multiplication.
- #108 `glm/tab-badge-cap` — badge caps at a derived "99+" label.
- #109 `glm/quick-tab-switch` — long-press Recent focuses the runner-up tab.
- #110 `glm/retention-graph` — one `Retention` through `AppGraph`.

## Verification notes

- `./gradlew testDebugUnitTest lintDebug`: green on `origin/main` (4105c28)
  before any changes; each PR re-ran the relevant module suites green.
- Device checks were not run for this review (emulator work belongs to the
  feature PRs that need it); engine and native claims cite
  `docs/ENGINE_SPIKE.md` and the plan's recorded evidence.
- `python3 scripts/check_docs.py` and `git diff --check` pass on every branch.
