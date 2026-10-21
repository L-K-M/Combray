# MiMo.md: Combray review and improvement plan

A full review of Combray as it stands after M4 work (commit `e5cf8ed`): bugs,
general issues, performance, missing features, visual and layout problems,
interface polish, theming, and ideas worth stealing from nowhere in particular.
Written without changing code. Section 9 says which ideas were implemented now
and which are left as shovel-ready work.

Sources read: `README.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/INTEGRATIONS.md`,
`docs/design/README.md` and the interactive concept, `docs/ENGINE_SPIKE.md`,
`docs/BUILDING.md`, all of `app/`, `core/`, `engine/` and `native/src`, plus the
test and fixture suites. Two parallel reviews covered `core/` and `engine/` +
`native/` line by line; their findings are folded in below. No device or
emulator was available here, so nothing in this document is a device
observation.

## 1. Bugs

Ordered by how much they would hurt in daily use.

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| B1 | `app/.../ui/AddressSheet.kt:48` | Reopening the address editor shows the previous draft instead of the current address, and it is not selected. `rememberSaveable(target)` survives dismissal, so the plan's "select the current address for replacement" only holds for the first open. | Key the saved draft to a per-open id (survives rotation, resets on reopen), or reinitialize from `currentUrl` on open. |
| B2 | `app/.../ui/HisterSearchPanel.kt:47`, `KarakeepPanel.kt:48` | `initialQuery` is read once when the panel first composes; after leaving and returning to a server scope the field keeps stale text instead of the current local filter. | Re-seed the field when the panel reopens (same per-open key as B1). |
| B3 | `core/.../browser/BrowserCoordinator.kt:198,740` | `navigateTab` (tab without a renderer) and `onEngineError` rewrite `tab.url` but keep `currentVisitId`, so the previous page's visit is suppressed from Recent as "currently shown" even though it is not. It can vanish from Recent until the next commit, or forever if the load never commits. | Clear `currentVisitId` wherever `url`/`title` are rewritten without a commit. |
| B4 | `core/.../integration/OutboxDispatcher.kt:38`, `OutboxWorker.kt:20` | One exception in prune/claim/finish escapes `drain`, aborting the loop and skipping `scheduler.schedule`; WorkManager then marks the unique work FAILED, so remaining due jobs stall until a foreground kick or restart. | try/catch per job, `schedule` in `finally`, `Result.retry()` from the worker. |
| B5 | `core/.../integration/Outbox.kt:125` | `attempts` only caps on a `Retry` result; a job that dies before `finish` is reclaimed every 60 s forever. | Enforce `maxAttempts` at claim time. |
| B6 | `core/.../integration/Outbox.kt:83` | Re-saving a URL whose job FAILED requeues the old row with its stale payload: a re-titled page is sent under its old title. | Refresh `payload`/`payloadBytes` on the retried row. |
| B7 | `core/.../integration/Outbox.kt:232` | `resume()` re-queues `AUTH_REQUIRED` jobs without resetting `attempts`, unlike `retryNow()`: a resumed job can die to FAILED on its first new failure. | Reset `attempts` in `resume`. |
| B8 | `core/.../integration/OutboxDispatcher.kt:71` | `LEASE_MS = 60 s` can be shorter than one real dispatch (two calls x two roots x up to ~23 s each), so a live claim expires mid-send and a duplicate can go out. | Raise the lease above worst-case dispatch or cap per-job dispatch time. |
| B9 | `core/.../integration/HisterIntegration.kt:207` | When the dedupe hash matches an earlier capture, `captured` returns early without checking whether that job FAILED: identical content after a failed send is never re-queued. | Skip the early return when the matching job left the queue unsuccessfully. |
| B10 | `core/.../integration/HisterIntegration.kt:208` | `budgetReached` latches true and only clears on a later accepted capture: after the queue drains it stays stuck, and `wantsCapture` keeps paying for engine captures whose text is then dropped. | Recompute the flag when queued bytes drop; short-circuit `wantsCapture` while over budget. |
| B11 | `core/.../integration/Integrations.kt:323` | With a list configured, a `createLink` success without a parseable id reports Done, so the bookmark is saved but never added to the list, silently. | Treat missing `remoteId` with a configured list as retryable, not Done. |
| B12 | `core/.../browser/BrowserCoordinator.kt:423` | `view.controls` is not cleared on `DOCUMENT_COMMITTED`, so a dialog from the previous document can be shown attributed to the new page's host when the engine does not emit `ControlHidden`. | Dismiss pending controls when the document identity changes. |
| B13 | `core/.../browser/BrowserCoordinator.kt:631` (native `engine.rs`) | `select_tab` leaves `ime_owner` on the previous tab: keyboard state sticks to a hidden tab. | Clear IME ownership and hide on tab switch. |
| B14 | `core/.../browser/BrowserCoordinator.kt:99` | `start()` is not idempotent; a second call starts a second work-queue consumer and breaks the single-consumer ordering invariant. | Guard with a started flag. |
| B15 | `core/.../browser/BrowserCoordinator.kt:78,710` | `pendingCaptures`/`pendingArticles` are removed only when a result arrives; a crashed view leaks them for the process lifetime. | Purge per view in `forgetView` and on `Crashed`. |
| B16 | `core/.../browser/BrowserCoordinator.kt:126` | `recent()` fixes `since` at flow creation: a long-lived Recent sheet's retention window never slides. | Compute `since` inside the transform. |
| B17 | `core/.../browser/BrowserCoordinator.kt:732` | `onEngineError` ignores refusals without a request, so a refused `selectView`/`closeView` leaves the coordinator believing a dead view is on screen. | Treat request-less refusals on live views as view-fatal and reconcile. |
| B18 | `core/.../browser/BrowserCoordinator.kt:894` | `SERVO_ERROR_TITLES` marks a visit failed by title alone: a real page titled "Error loading page" is hidden from Recent and never captured. | Prefer an engine failure signal; only trust the title after a load error. |
| B19 | `core/.../integration/SensitiveUrls.kt:12` | The sensitive-parameter set misses `code` (OAuth authorization codes), `signature`, `key`, `ticket`, so callback URLs can be saved to Karakeep or captured to Hister. | Extend the set. |
| B20 | `core/.../integration/SecretStore.kt:114` | Two concurrent first-time configures can race Keystore `generateKey`; one credential write fails or orphans the other's ciphertext. | Serialize key creation (shared mutex / `containsAlias`). |
| B21 | `native/src/engine.rs:411` | `surface_created` overwrites `self.surface` even when `surface_destroyed` failed to unbind, dropping a still-bound `NativeWindow` EGL may reference. | Keep or `mem::forget` the old window on failed unbind. |
| B22 | `engine/.../EngineSurfaceView.kt:124` | `forwardKey` consumes every non-Back key: the volume rocker is dead while browsing. | Return false for key codes the engine cannot map. |
| B23 | `engine/.../EngineSurfaceView.kt:220` | `ACTION_MULTIPLE` is mapped to `KEY_RELEASE`, emitting bogus releases. | Handle or ignore it explicitly. |
| B24 | `native/src/engine.rs:465` | `start_servo` continues after `create_dir_all` fails: the app runs with an unwritable profile and silently loses cookies/HSTS. | Fail startup on an unwritable profile. |
| B25 | `native/src/jni_api.rs:192` | On a `start()` race the losing engine thread spins forever on `recv_timeout` because its waker clone keeps the channel alive. | Send `Command::Shutdown` before dropping the losing sender. |

## 2. General issues and robustness

- G1 `core/.../integration/ServiceUrls.kt:50` — fingerprints do not canonicalize
  the host: `https://h`, `https://h:443` and `https://h.` count as different
  servers, forcing credential re-entry and re-binding queued work to a "new"
  destination. Strip default ports and trailing dots.
- G2 `core/.../integration/ServiceRoutes.kt:22` — the "exactly one request per
  attempt" contract is a comment only; a lambda that sends twice can re-send on
  failover. Enforce it in `AttemptTransport`.
- G3 `core/.../data/IntegrationDao.kt:121` — the capture budget counts FAILED
  payloads, so one permanently failed large capture blocks new captures for up
  to seven days.
- G4 `core/.../integration/HisterIntegration.kt:54` — forget tombstones never
  shrink; `configure()` bumps the policy generation even when nothing changed,
  needlessly dropping in-flight captures.
- G5 `native/src/delegate.rs:221` — `ImeShown` drops `allow_virtual_keyboard`,
  so any page can summon the keyboard with `focus()` and no user gesture.
- G6 `native/src/delegate.rs:165` — `request_navigation` fires for iframes too
  and the event carries no frame identity; the app sees navigations it cannot
  attribute.
- G7 `core/.../policy/AddressPolicy.kt:99` — `myapp://deep-link` is rejected
  outright; a syntactically valid custom scheme could be offered to Android as
  an explicit external open (with confirmation, like `mailto:`).
- G8 `engine/.../EngineSurfaceView.kt:203` — hiding the keyboard commits the
  half-typed composition as final text, inserting stray partial words.
- G9 `engine/.../EngineSurfaceView.kt:155` — `showKeyboard` skips `restartInput`
  when the field kind is unchanged, which can reuse a dead `InputConnection`.
- G10 `engine/.../NativeEngineHost.kt:33` — a throwing app listener leaves a
  pending Java exception on the engine thread and breaks later events; wrap and
  clear.
- G11 `native/src/reader.js:121` — reader/capture DOM walks recurse by nesting
  depth; a pathological page throws `RangeError` and the whole capture fails as
  `script_error`. Use an explicit stack or depth cap.
- G12 The address chip's security icon comes from the URL scheme string, not
  engine data. The plan asks for engine-derived state; today `loadFailed` is the
  only engine truth in that chip.
- G13 Untested on hardware per plan: text selection, pinch zoom, TalkBack, dark
  mode rendering, landscape at scale. These are honest "not run", but they are
  the checks most likely to expose real problems next.

## 3. Performance

- P1 `core/.../integration/Outbox.kt` / `BrowserCoordinator.kt:68` — the event
  channel and job queue are unbounded; a chatty page can grow memory without
  limit while the consumer does database work. Bound or coalesce.
- P2 `native/src/jni_api.rs:243` — `surfaceDestroyed` blocks the UI thread up to
  5 s when the engine is wedged: that is the input-ANR budget. Shorten the wait.
- P3 `native/src/engine.rs:639` — `persist_to_disk` (cookie/HSTS flush) blocks
  the engine thread: every tab freezes during the write.
- P4 `engine/.../NativeEngineHost.kt:34` — engine event JSON (a capture can be
  ~256 KiB) is parsed on the engine thread; parse on a dispatcher instead.
- P5 `native/src/commands.rs:150` — the command channel is unbounded: touch and
  wake bursts pile up while the engine is stalled and are delivered stale late.
  Bound it and coalesce moves.
- P6 `native/src/capture.rs:56` — every capture re-expands the ~100 KiB script
  template with `.replace`; cache the expansion per kind/budget.
- P7 `native/src/engine.rs:1475` — `<select>` collects every option before the
  2000 cap applies; a pathological select burns engine-thread memory. Cap while
  collecting.
- P8 `app/.../ui/HisterSearchPanel.kt`, `KarakeepPanel.kt` — every keystroke
  clears the visible results before the debounced search returns: the list
  flickers and empty space flashes. Keep the last answer until the new one
  arrives (loading line already communicates the wait).
- P9 `app/.../ui/RecentSheet.kt` — the whole list is re-derived and re-sorted on
  every emission of any of the three flows (up to 500 rows); fine today, but SQL
  ordering would remove the per-emission sort and `startOfDay` work.
- P10 `native/src/engine.rs:1364` — a persistent `make_current` failure retries
  and warns at 4 Hz forever; back off after N failures.
- P11 `native/src/engine.rs:187` — `held` capture reports are silently dropped
  at `MAX_HELD_REPORTS`, so the app can await a report that never comes.

## 4. Missing features

Ranked for a personal browser that "gets out of the way".

1. Appearance choice (System / Light / Dark) in Settings. The theme follows the
   system only; a personal browser should let the owner override it.
2. Reader view text size. Reader is Combray's own type, so the type size is
   Combray's business; one setting or a small stepper.
3. Swipe gestures in Recent: swipe a tab row to close (with the existing Undo),
   swipe a visit row to remove. The whole row is a target already; touch-first
   cleanup is what a Recent list wants.
4. Quick links on the new-tab page: most visited hosts from local history, one
   tap away. The home page is currently two buttons and a sentence.
5. "Open copied link" on the new-tab page when the clipboard holds a URL
   (Android shows its own clipboard notice; do it only while the home page is
   visible).
6. Long-press affordances on the bottom bar: long-press Share copies the
   address; long-press Save saves and offers the Karakeep list; long-press Back
   lists the tab's recent pages. Cheap, discoverable, no chrome growth.
7. Local history completion in the address editor (titles and hosts from
   Recent). The plan's "no autosuggest" covers search typing staying local and
   no server contact; on-device history completion is local and the plan's own
   "search typing stays local until submit" allows it. Keep it clearly local.
8. Hardware keyboard shortcuts (Ctrl+T/W/L/R, Ctrl+F) once the engine forwards
   modifiers (engine finding: `metaState` is dropped today).
9. Find in page. Needs an engine find API; check Servo 0.6.0 before promising.
10. Downloads and file handling; `Stop` while a page loads (Servo 0.6.0 has no
    API; recorded in the plan). `mailto:`/`tel:` already open externally.
11. Recent home layout (the second design concept) as an optional layout; both
    concepts remain unselected by the owner.
12. Karakeep tags/notes on save (a later sheet per plan), and "save to list"
    chosen at save time.
13. Close all tabs; "reopen last closed" beyond the Undo snackbar.
14. `ACTION_SEND` into Combray: share a link to the app to open or save it.
15. Per-page text zoom; two-column Recent in landscape.

## 5. Visual and layout problems

The design reference (`docs/design/combray-concepts.fragment.html`) specifies a
palette and geometry the implementation only partly follows:

- V1 **Surfaces do not separate.** `Theme.kt` sets `surface = background`, so
  sheets, cards and rows share one color; the concept uses a distinct panel
  (`#ffffff` light, `#242929` dark) over the background (`#f7f6f2` / `#191c1c`)
  and a soft tint (`#e9f0e9` / `#303f37`) for chips, pressed rows and icon
  tiles.
- V2 **Palette drift.** The app's warm ivory `#FFFBF4` and pine `#0E452E` are
  close in spirit but the concept's tokens (accent `#215e4a` / `#b5ddc9`, line
  `#e3e7e1` / `#3e4844`, muted `#66736d` / `#adbab3`) are calibrated for
  contrast and pair with the checked artwork. Adopt them as the source of truth
  and keep the icon's exact greens for the launcher only.
- V3 **Shapes and type are stock Material 3.** The concept uses 12 px rounded
  rectangles for chips, address field and buttons, 23 px sheet tops, 44 px
  rows with 32 px rounded icon tiles, and a restrained type scale (20/500 sheet
  titles, 12/11 px meta). `Shapes` and `Typography` are unset today.
- V4 **Save is a star.** Karakeep is a bookmark manager; the concept uses
  `bookmark` / `bookmark-check`. The custom `StarBorder` path hack would
  disappear with bookmark icons from the same icon set.
- V5 **The new-tab page is a stub**: a title, a sentence, two buttons. It is the
  first thing the owner sees dozens of times a day.
- V6 **Open-tab rows** signal state with the words "Open"/"Current tab" and a
  headline color; the concept uses a small live marker and a soft-tinted row.
  A tinted row for the current tab would read faster.
- V7 **Snackbar offset** is a fixed `72.dp` above the bar; at the largest font
  scale the bar grows and the snackbar can overlap it. Anchor the snackbar to
  the bar's actual height instead.
- V8 The loading line is a bare 2 dp `LinearProgressIndicator`; tint it with
  the accent so the quiet chrome stays quiet but alive.
- V9 Reader view is clean but static: no size control (feature 2), and its
  surfaces could use the same panel/soft distinction.

## 6. Interface: fast, convenient, friendly

- I1 The address editor is one tap away and one tap back: good. Tapping the
  chip could also reveal the full URL in place (privacy-friendly display stays
  in the bar; the editor already shows everything).
- I2 Long hosts ellipsize from the start (`TextOverflow.StartEllipsis`): the
  registrable domain survives. Good; keep.
- I3 Save states are spoken and iconned. Good. The pending spinner over a
  star is busy; a morph (star to check) with `AnimatedContent` would read
  calmer.
- I4 Everything destructive already has Undo or a dialog. Keep that invariant
  when adding swipe gestures (D3/feature 3): swipe must be undoable, never
  instant loss.
- I5 Recent correctly refuses to type into Hister/Kagi until the scope is
  explicit; when the server scopes keep stale results (P8) they should also
  keep the query they ran visible (B2), so the field and results always agree.
- I6 When the engine library is missing, the message is a wall of text; the
  same message with the app icon and one action ("Show build help") would be
  friendlier and on-brand.

## 7. Theming and aesthetics

- T1 Adopt the concept tokens as `ColorScheme` entries: `background`,
  `surface`, `surfaceVariant` (soft), `outline`/`outlineVariant` (line/muted),
  `primary`/`onPrimary` (accent pair), `secondaryContainer` (soft chip).
- T2 Set `Shapes`: 12 dp for chips/buttons/fields, 23 dp top for sheets,
  16 dp for dialogs. Compose sheets already round; align the rest.
- T3 Set a small `Typography` scale matching the concept: sheet titles
  20/500, row titles 14/500, meta 11–12, keeping the system typeface in chrome.
- T4 Dark theme should use the concept's charcoal (`#191c1c` background,
  `#242929` panel), not the current near-black green; it pairs with the
  checked muted/accent values and keeps row hierarchy visible at night.
- T5 Reader view can take a paper tone in light mode (the concept's ivory)
  with pine links; it is content, so a serif heading there is a tasteful nod to
  the concept's article mockup without touching chrome typography.
- T6 The folded-C monogram belongs on the new-tab page at a modest size: it is
  the one piece of identity the owner chose.

## 8. Novel, cool, delightful, quirky

- D1 **Open copied link** chip on the new-tab page (feature 5): the most
  used "browser as a verb" flow on a phone, and it feels like magic when it
  just appears.
- D2 **Haptic tick when a save lands.** One `HapticFeedbackConstants.CONFIRM`
  on the Saved transition; quiet, physical, done.
- D3 **Kagi bangs hint.** Queries already go to Kagi, so `!w`, `!gh` etc. work
  for free. One muted hint line under the address button on the new-tab page:
  "Kagi shortcuts like !w work here". Quirky, zero maintenance.
- D4 **Reading time** in reader view's meta line ("The Atlantic · 6 min read").
  Three lines of code and an actual answer to "how long is this?".
- D5 **Undo snackbar names the tab**: "Closed 'Article title'" instead of
  "Tab closed". The data is already in `Notice.TabClosed`.
- D6 **New-tab page quick links** (feature 4) with the concept's 32 px icon
  tiles: first letter or monogram tint, host under it. Warm, useful, local.
- D7 **Long-press the Recent button** opens Recent straight on the Hister
  scope: a shortcut the owner (whose memory lives in Hister) will use daily.
- D8 **Reader view remembers its size per session** and animates the change,
  so trying sizes feels like a slider, not a setting.
- D9 **Save button morph**: star fills and a checkmark draws over it
  (`AnimatedContent` or a path morph), so success is visible peripherally.
- D10 **Engine-missing art**: the monogram with a small "fold" shadow, plus the
  existing honest message. Even the failure state can look like Combray.
- D11 **Tab badge animates** (scale tick) when the count changes; one
  `animateIntAsState` away.

## 9. What was implemented now

Implemented as separate branches/PRs after this review (each with tests or
honest verification notes; no device was available):

1. **Fix the address editor's stale draft** (B1) and the server-scope field
   staleness (B2): editors open on the current value, drafts survive rotation.
2. **Design-token theme refresh** (V1–V4, V8, T1–T4): the concept's palette,
   shapes and type scale, bookmark icons for Save, accent-tinted loading.
3. **New-tab page quick links** (D6, feature 4): most visited hosts from local
   history with unit-tested aggregation, monogram (D/T6).
4. **Appearance setting** (feature 1) and **reader text size** (feature 2,
   D8): System/Light/Dark and a reader size, persisted in `BrowserSettings`.
5. **Recent server scopes keep their results while typing** (P8) and the
   **swipe-to-close/remove rows** (feature 3, D-style with Undo).

Everything else above is shovel-ready for the next session; see
[ANALYSIS.md](ANALYSIS.md) for the living backlog that survives this review.
