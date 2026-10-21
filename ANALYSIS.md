# Combray — analysis and future work

Consolidated roadmap for the next implementation session. Produced from a full
codebase review (recorded in `SWE2.md`, 2026-10-21); read `AGENTS.md`, the
[implementation plan](docs/IMPLEMENTATION_PLAN.md) and `SWE2.md` for rationale and
the project's working agreements. Items are intended to be shovel-ready: each
names files, constraints and a verification path.

State today: M0–M3 implemented, device-checked on an x86_64 emulator with fake
services only. No physical device, real Karakeep/Hister/Kagi endpoint, or live
site has been exercised. Servo v0.6.0 pinned; no interruption API (no Stop).

## In flight (PRs open for owner review, not merged)

- #24 `swe2/inert-events-no-publish` — skip `publish()` on state-inert engine
  events (Painted, surface/selection echoes, diagnostics). All subtypes are
  enumerated so a new `EngineEvent` fails to compile until placed.
- #25 `swe2/recent-filter` — Recent collects once and filters the list in memory;
  `RecentItem.matches` is now a shared member on the model.
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

## Correctness items (not yet done)

1. **`PageInfoDialog` dismisses during composition.** `BrowserScreen` does
   `tab?.url?.let { PageInfoDialog(...) } ?: dismiss()` — a state write inside
   composition. Hoist into an effect or make the sheet key on an existing target.
   File: `app/src/main/kotlin/ch/lkmc/combray/ui/BrowserScreen.kt`.
2. **`KarakeepPanel` refetches on any service-row change.** `LaunchedEffect(query,
   service)` reruns when `updatedAt` bumps (e.g. choosing a save list), restarting
   pagination from page 1. Key on the fields that matter (fingerprint/generation).
3. **Failed-visit detection keys on Servo error-page titles** ("Error loading
   page", "Certificate error"). If Servo renames/localizes them, failures become
   successful history rows. Options: engine-level load-failure signal upstream,
   or a device-check asserting those titles. Coordinator `SERVO_ERROR_TITLES`.
4. **Coordinator command channel is unbounded** (`Channel<suspend () -> Unit>`).
   Bursts queue stale work; consider conflation of publish work or a bounded
   channel with a deliberate overflow policy.
5. **`Retention()` re-instantiated in SettingsSheet** for the history-days label —
   diverges the day retention becomes configurable. Pass the configured instance
   through `AppGraph`.
6. **Process-lifetime map growth** (small): `HisterIntegration.forgotten`
   tombstones accumulate per forget; `LiveView.documentVisits` is bounded by the
   six-live-view cap in practice.
7. **Tab badge renders the raw count** — cap display at "99+".
8. **IME bridge is minimal**: `EngineInputConnection` lacks
   `getTextBeforeCursor`/`getCursorCapsMode` etc. Autocorrect and gesture
   keyboards see an empty field. Functional today; harden if users hit it.
9. **`dismiss()`/focus race candidates on sheets**: `AddressSheet` requests focus
   in `LaunchedEffect` possibly before the sheet window is laid out; modal sheets
   also historically ignored IME insets. Verify keyboard opens reliably and the
   field clears the keyboard on a real device.

## Performance

- `publish()` still runs three Room reads per *stateful* event; a snapshot
  short-circuit or dirty-flag would help if event volume grows. The inert set is
  already skipped (#24).
- `RecentSheet` day-grouping allocates a `Calendar` per row per list composition;
  reuse one instance or compute boundaries once. Filtered lists see at most the
  newest 500 visits (`RECENT_LIMIT`) — pre-existing cap, unchanged by #25.
- `ServiceStore.create()` touches `AndroidKeyStore` on the main thread during
  `Application.onCreate`; make lazy if cold start regresses.
- Six-live-view cap evicts back stack and scroll position. Persisting per-tab
  scroll offset or revisit-on-restore would soften it; Servo cannot restore
  session history today.

## Missing features (worth having; mostly cheap at the UI layer)

- Stop/abort load — blocked upstream (no Servo interruption API in v0.6.0).
  Revisit per engine update. Text selection, pinch zoom, downloads, find-in-page:
  same class — verify what the embedder API exposes before promising.
- Share should include the page title (`title\nurl`) — `share()` in the app
  module sends only the URL today.
- Long-press the address chip → copy the full URL.
- Recent: "Close all tabs" / "Close other tabs"; swipe-to-close or swipe-to-forget
  rows.
- Quick tab switch: long-press Recent → focus the previously-focused tab (the
  runner-up by `lastFocusedAt` is already queryable).
- Reader view: font-size control; per-host "always open reader" list (reuses the
  exclusion-list settings idiom).
- Home surface: show open tabs or the most recent visits — the "Recent home"
  design alternative can be trialed here without committing to it.
- Favicons in Recent rows and the address chip — only if the embedder API
  surfaces them; do not fetch page icons through another stack.
- Settings: theme choice (system / light / dark) and optionally Material You
  dynamic color on Android 12+.

## Visual and layout notes

- Theme is complete as of #26; still worth an on-device contrast pass in both
  modes, especially `surfaceVariant`/`outline` on actual panels.
- Empty states (Recent empty, Home) are plain text — a light glyph or tighter
  copy would carry the design further.
- Sheets: verify IME insets and focus timing on device (above).
- Landscape: bottom bar is fine; check page controls' dialog sizing.

## Delightful/novel ideas

- Pending-dot on the Save icon when outbox saves/captures are queued.
- Long-press Save → choose list + note before enqueue (Karakeep lists already
  loaded in settings).
- "Resume" row on Home: most-recent visit plus open tabs.
- Session trails: group history rows by originating tab ("how I got here").
- Time buckets finer than days in Recent ("this morning").
- Offline-flush summary: a snackbar when a drain empties the queue after being
  offline — the outbox already knows counts.
- Swipe left/right on the address chip to switch tabs (safe; pull-to-refresh is
  not — it fights Servo's surface).

## Verification gaps to close with the owner

- arm64 physical device: stale-frame check (M0 SwiftShader artifact), pinch zoom,
  keyboard behavior on real pages, dark mode + landscape sweep.
- Live Karakeep/Hister/Kagi: one deliberate save and capture, then clean up.
- Accessibility pass on Recent and Settings (device checks cover other screens).
- Personal signing key + native build in CI before any release tag.

## Process notes for the next agent

- `python3 scripts/check_docs.py` and `git diff --check` always;
  `./gradlew testDebugUnitTest lintDebug assembleDebug` for Kotlin;
  `cargo fmt --check && cargo test --lib` for `native/`.
- A worktree may carry an uncommitted `native/src/engine.rs` change enabling
  IntersectionObserver (the `enable-intersection-observer` branch's in-flight
  work — not ours). Leave it out of unrelated commits.
- `ANDROID_HOME` must be set (`/home/paseo/Android/Sdk`) for Gradle locally.
