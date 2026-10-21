# Combray review (SWE-2)

Second full read of the codebase, 2026-10-21, on top of the existing docs. Scope:
`core/`, `engine/`, `app/`, `native/` (skimmed), build files, `docs/`, manifest and
resources. ~7.8k lines of Kotlin plus ~4.2k lines of Rust/JS. Verdict up front: the
coordinator, outbox and integration layers are unusually disciplined (durable jobs,
identity-tagged events, fenced completion, honest pending states). The gaps are in
the UI layer, a handful of small correctness wrinkles, and a few perf habits that
matter on a phone. Findings are ordered by confidence and impact.

## Bugs and correctness

1. **Outbox lease is shorter than the worst-case dispatch.** `LEASE_MS = 60_000`,
   documented as "longer than one request can take". But `dispatchSave` issues two
   `routes.call` invocations (create bookmark, then add-to-list), and each `call`
   may try up to two roots at connect 8 s + read 15 s. Worst case is ~4 x 23 s = ~92 s.
   A second drain (WorkManager wakeup or another `kick`) can then claim the
   RUNNING job while the first dispatch is still in flight and send it again.
   The fence counter keeps the DB state correct and both endpoints are
   idempotent-ish, but double POSTs of page payloads to Hister are real.
   Fix: raise the lease to comfortably exceed the worst case (~150 s) or bound
   dispatch attempts per lease.

2. **`publish()` runs after every engine event, including inert ones.** Every
   `Painted` event (emitted once per second per tab while painting), every
   `SurfaceBound`, `TabSelected`, `LateCaptureResult`, `Other`,
   `ProfilePersisted`, `ShutdownComplete` runs `dao.session()` + `dao.tab()` +
   `dao.openTabs()` on the serialized coordinator queue. Cheap per call, but it
   is pure churn during continuous painting and it occupies the same queue real
   work waits on. Early-return for provably UI-inert events.

3. **Hister `budgetReached` flag sticks after the queue drains.** It is set when
   a capture is refused (`queuedCaptureBytes + bytes > 20 MiB`) and cleared only
   when a later capture fits. If the owner stops browsing while over budget, the
   "capture is paused" warning in Settings persists indefinitely even after the
   queue has drained far below the cap. Auto-clear it when
   `observeQueuedCaptureBytes` drops back under the limit.

4. **Failed-visit detection keys on Servo's error-page titles.** Titles like
   "Error loading page" are matched to mark visits failed. If Servo renames or
   localizes these, failures silently become successful history rows. Documented
   hack; the durable fix is an engine-level load-failure signal. Worth a code
   comment pointing at the Servo revision that produces these titles, and a
   device-check that would catch a rename.

5. **`Retention()` is re-instantiated inside `SettingsSheet`.** The settings UI
   reads `Retention().closedHistoryDays` rather than the coordinator's configured
   instance. Identical today; a lie the day retention becomes configurable.
   Pass the configured `Retention` down through `AppGraph`.

6. **In-process maps grow for the process lifetime.** `HisterIntegration.forgotten`
   accumulates one entry per forgotten site/URL; `LiveView.documentVisits` grows
   per view until eviction (bounded by 6 live views, so bounded in practice);
   `pendingCaptures`/`pendingArticles` are cleaned on every `Capture` event
   including `LateCaptureResult`'s predecessors — verified, no leak there. All
   minor; note the `forgotten` map if a long-running process matters.

7. **`KarakeepPanel` refetches on any service-row change.** `LaunchedEffect(query,
   service)` reruns when the service row bumps `updatedAt` (e.g. picking a save
   list), restarting pagination from page 1. Key the effect on the fields that
   matter (fingerprint/generation), not the whole row.

8. **`dismiss()` called during composition.** `BrowserScreen` does
   `tab?.url?.let { PageInfoDialog(...) } ?: dismiss()` — a state write inside
   composition. Works today, but it is fragile; hoist into `LaunchedEffect` or
   restructure so sheets only exist when their target exists.

9. **Coordinator `Channel` is unbounded.** A burst of engine events queues
   unlimited suspend lambdas; each still ends in `publish()` reads. Together with
   finding 2, an animated page under scroll keeps the queue busy with stale work.
   After (2) this becomes theoretical; a `CONFLATED`-style coalescing of
   publish work would be the belt-and-braces version.

## Performance and responsiveness

- **Per-event Room reads.** Besides `publish()` above, every navigation produces
  several `onHistory`/`publish` queries. Fine today; keep them on
  `Dispatchers.Default`/Room executors as they are, but batch or dirty-flag the
  publish if load events get noisier.
- **Recent filter re-subscribes per keystroke.** `remember(query) {
  browser.recent(query) }` recreates the flow — three Room invalidation
  observers — on every typed character. The query is applied in memory anyway
  (`RecentItem.matches`); collect once and filter the already-collected list in
  the composable. Same result, zero re-subscription. Note the pre-existing cap:
  filtering only sees the newest 500 visits.
- **Day-group `Calendar` churn.** `RecentSheet` builds `Calendar.getInstance()`
  per row per composition. Hoist `now`/day boundaries out of the `LazyColumn`
  builder; trivial.
- **`EngineSurfaceView` / long-press / IME** look clean: `restartInput` only when
  the kind changes, pointer-indexed motion events, surfaces released before
  destroy returns. The known stale-frame risk on SwiftShader stays documented,
  not fixed here.
- **App startup touches `AndroidKeyStore` on the main thread**
  (`ServiceStore.create()` inside `CombrayApplication.onCreate` via `AppGraph`).
  Small one-time cost; consider lazy init if cold start ever regresses.

## Missing features (engine-permitting)

- **Stop / abort load** — no Servo interruption API in the pinned release.
  Documented; revisit when the embedding API grows. Until then a disabled-state
  "Stop" in More could be omitted honestly.
- **Text selection, pinch zoom, downloads** — recorded as not supported / not
  checked upstream. Device verification needed for zoom.
- **Find in page** — Servo may not expose it to embedders yet; worth one check
  of the embedding API before writing it off.
- **Reader view has no on-screen close control.** Only Back and More › Leave
  reader view exit it. Add a close affordance; also font-size control would be
  genuinely useful for a reader.
- **No "Close all / Close other tabs"** in Recent; no quick-switch gesture
  (long-press Recent → jump to last tab).
- **Share sends only the URL** — include the title (`title\nurl`) when known.
- **Long-press the address chip → copy URL.** Cheap, expected behavior.
- **Badge overflow**: open-tab badge renders raw count; cap at "99+".
- **Link-menu copy gives no feedback** — toast/snackbar "Link copied".
- **Home is a dead end.** Two buttons and text. Even without picking between the
  two design layouts, open tabs or recent rows on home would shorten the most
  common path (resume something).
- **No favicons anywhere.** If the embedder API surfaces icons, Recent rows and
  the address chip want them; if not, leave it.
- **IME bridge is minimal**: `getTextBeforeCursor`/`getCursorCapsMode` etc. are
  unimplemented, so autocorrect/gesture keyboards see an empty field context.
  Functional today; harden only if users hit it.

## Visual, layout, theming

- **Theme covers ~7 of ~30 Material3 color roles.** `surfaceVariant`,
  `surfaceContainer*`, `outline`, `onSurfaceVariant`, `tertiary`,
  `primaryContainer`, `scrim` and friends fall back to M3 defaults — neutral
  purples/greys that visibly clash with the pine/ivory palette, most obviously
  on menus, chips' unselected state, snackbar, and dark-mode tonal surfaces
  (defaults sit at #1D1B20-ish while Combray surface is #101512). The design
  fragment carries a full token set (`#f7f6f2` bg, `#215e4a` accent,
  `#e9f0e9`/`#edf1ee` panels, `#cbd4cc` lines, `#66736d` muted; dark greys
  `#191c1c`–`#26382b` family). Cheap, high-payoff fix.
- **Material You dynamic color** as an opt-in theme choice would suit a personal
  browser on Android 12+; keep the pine scheme as the "Combray" option.
- **Reader view typography**: no size/serif controls; the article sheet is a
  plain scrollable column.
- **Address sheet** lifts for the IME only if this Compose version applies
  `imePadding` to modal sheets — verify on device; historically it did not and
  the field could sit under the keyboard.
- **`focusRequester` before layout** in `AddressSheet` may race the sheet's
  window; if the keyboard doesn't reliably open, request focus in
  `onGloballyPositioned` or after `sheetState.show()`. Verify on device.
- **Snackbar/notice flow is good** (shared `SnackbarHostState` between main
  screen and Recent), dialogs are per-document counted with block support,
  sign-in warns on cleartext — the solid parts are solid.

## Novel / delightful ideas

- **Pending-dot on Save** when queued saves exist — the outbox is durable but
  invisible outside Settings; a small dot makes offline saves feel accounted
  for.
- **Long-press Save** → choose list + add a note before enqueue. Karakeep
  supports notes; the UI already knows the lists.
- **Long-press Recent** → switch straight to the previously-focused tab
  (`lastFocusedAt` runner-up is already queryable).
- **"Resume" row on Home**: the single most-recent visit + open tabs, matching
  the "Recent home" design alternative without committing to it.
- **Session trails in Recent**: group history rows by originating tab so "how I
  got here" survives tab closes.
- **Always-reader site list**: per-host pref to auto-open reader view; fits the
  existing exclusion-list settings idiom.
- **Swipe between tabs** on the address chip (left/right), pull-to-refresh on
  the page — both gesture conflicts with Servo's surface need care; chip-swipe
  is safe, pull-to-refresh is not.
- **Offline flush summary**: one-line snackbar "N saves sent to Karakeep" when a
  drain empties the queue after being offline — the outbox already knows.
- **Time buckets in Recent** beyond day boundaries ("this morning") — the visit
  timestamps exist.

## What I would implement now

Ordered by value-per-risk, all unit-testable, no device required:

1. Skip `publish()` on inert engine events (finding 2) — pure coordinator change.
2. Recent filter without per-keystroke re-subscription (perf) — `RecentSheet`.
3. Complete the M3 palette from the design tokens (theming) — `Theme.kt`.
4. Reader-view close affordance (UX gap) — `ReaderView.kt`.
5. Outbox lease ≥ worst-case dispatch (finding 1) — `Outbox.kt` + comment.
6. Auto-clear Hister `budgetReached` under the cap (finding 3).

Everything else above is catalogued for `ANALYSIS.md`.
