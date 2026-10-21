# Combray — analysis and future work

Shovel-ready list for the next session. Merged 21 October 2026 from the earlier
roadmap and a second read (`Grok.md`, discarded after this merge). Read
`AGENTS.md` and [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) before
editing. Do not rebuild private browsing, extensions, ad blocking, or a second
engine. Do not fake Stop, downloads, or a verified lock: Servo 0.6.0 does not
report those.

State on `main`: M0–M3 are implemented and device-checked on an x86_64 emulator
with fake services only. No physical device, real Karakeep, Hister, or Kagi, or
live site has been exercised. Servo is pinned at v0.6.0. There is no interruption
API, so there is no Stop.

## Already in open pull requests

Leave these alone unless the owner declines them. Rationale stays in the PR.

Earlier session:

- #24 skip `publish()` on state-inert engine events. New `EngineEvent` subtypes
  must be placed or the when fails to compile.
- #25 Recent filters in memory. `RecentItem.matches` is on the model.
- #26 full Material 3 roles for both modes. Light `outline` is about 3.5:1.
- #27 floating reader close button.
- #29 outbox lease 60 s to 150 s. Worst-case dispatch is about 62 s plus DNS.
- #30 capture-budget flag derived from the live queue.

This session, not merged:

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

## Correctness still open

1. **Forgetting a capture does not survive a kill.** `HisterIntegration.forgetHost`
   writes an in-memory marker, then cancels outbox rows. If the process dies
   between those steps, restart can still send the page. Persist the tombstone in
   the same transaction as the cancellation, and check it again at dispatch.
   Files: `HisterIntegration.kt`, `BrowserCoordinator.forgetSite`. Test: enqueue a
   capture, forget the host, drop the in-memory map, drain, and assert the job
   was not sent.
2. **A failed list add hides a bookmark Karakeep already stored.** After
   `createLink` succeeds, a list PUT that is not `Done` becomes the job result,
   and the saved-item row is written only on final `Done`. Record the bookmark
   when create succeeds. Retry only the list step. A `404` stays a visible
   failure. File: `Integrations.dispatchSave`. The plan says a list step must not
   mark the link unsaved.
3. **A capture older than a week can still be sent if it was `RUNNING` when the
   process died.** `expireCapturesBefore` skips `RUNNING`. `nextDue` reclaims a
   dead lease and does not recheck age. Expire a capture past the cutoff once its
   lease is not live, and do not claim one past the cutoff. Files:
   `IntegrationDao.kt`, `Outbox.kt`.
4. **Failed visits are detected by Servo error-page titles** ("Error loading
   page", "Certificate error") in `SERVO_ERROR_TITLES`. If Servo renames or
   localizes them, a failure becomes a history row. Prefer an engine load-failure
   signal. Until then, a device check must assert those titles.
5. **The coordinator command channel is unbounded** (`Channel<suspend () -> Unit>`).
   A burst queues stale work. Bound it, or conflate publish work, and name the
   overflow policy.
6. **`KarakeepPanel` refetches when any service row changes.**
   `LaunchedEffect(query, service)` restarts pagination when `updatedAt` bumps
   (choosing a save list). Key on fingerprint or generation, not the whole row.
7. **`Retention()` is built again in `SettingsSheet` for the history-days label.**
   Pass the configured instance from `AppGraph`, or the label will drift when
   retention becomes a setting.
8. **Settings save and connection check share one `message`.** A slow check can
   finish after a save and replace "Settings saved" with the old result. Give
   each request a generation, or show the two statuses apart.
   File: `SettingsSheet.kt`.
9. **Failed save rows are never pruned.** `observeOpenJobs` loads every `FAILED`
   row. Page the list. Keep the rows until the user retries or discards them.
   File: `IntegrationDao.kt`.
10. **File-picker paths are not checked in the Rust bridge.** Kotlin copies
    uploads into an app directory. Rust forwards the returned `PathBuf`s to
    Servo. Reject a path outside that directory. Files: `PageControls.kt`,
    `native/src/controls.rs`.

## Performance and engine

Do not claim a device reproduction for these. They are from the bridge and
`docs/ENGINE_SPIKE.md`.

1. **Surface teardown can wait five seconds on the UI thread, and a failed
   unbind can be reported as success.** `EngineSurfaceView.surfaceDestroyed`
   calls `send_and_wait` with `ACK_TIMEOUT` of 5 seconds. Acknowledge release
   only after detach. Do not bind the next window until that is true. A timeout
   is a failure, not a release. Files: `EngineSurfaceView.kt`,
   `native/src/engine.rs`, `native/src/jni_api.rs`.
2. **Touch moves are not coalesced.** Every move of every pointer is queued on an
   unbounded channel. The engine drains 64 commands per turn. Merge pending
   moves per pointer. Keep down, up, and cancel in order. Files:
   `EngineSurfaceView.kt`, `native/src/commands.rs`.
3. **Reader mode clones the whole document before the node budget.**
   `native/src/reader.js` calls `document.cloneNode(true)`, then Readability's
   `maxElemsToParse`. Refuse an oversized tree before the clone. The script
   deadline does not interrupt a running script.
4. **Keyboard text goes to whichever tab is active when the command runs.**
   `ImeCommit` carries only the string. `ime_commit` uses `active_webview()`.
   Carry the view generation and drop the commit if that view is no longer the
   one with the field.
5. **`publish()` still does three Room reads per stateful event.** A snapshot or
   a dirty flag helps only if event volume grows. Inert events are already
   skipped in #24. Do not undo that.
6. **Recent day-grouping allocates a `Calendar` per row.** Reuse one instance, or
   compute the day boundaries once. The list is capped at the newest 500 visits.
7. **`ServiceStore.create()` touches `AndroidKeyStore` on the main thread** in
   `Application.onCreate`. Make it lazy if cold start regresses.
8. **The six-live-view cap drops back-stack and scroll.** Restoring a tab reloads
   the URL only. Servo cannot restore session history today. Do not invent a
   session format. A stored scroll offset is optional and must say what is lost.
9. **Emulator cost, not a phone budget.** About 141 MiB for two small tabs, 156
   MiB for ten, 840 MiB after roughly fifteen cached documents and two large
   pages. A 20,000-node capture took 10 to 20 seconds. Shutdown has taken 15 to
   157 seconds. SwiftShader on x86_64. No budget has been set.
10. **A long-press timer starts when Android sees the press.** A main-thread stall
    turns a tap into a long-press menu. This has shown up in device checks.
11. **Profile writes block the engine thread** (patch 0004, every five minutes and
    on leaving the app). A write during a fling is a hitch.
12. **IntersectionObserver is missing.** Some Next.js sites do not start. A
    separate change may already be in flight. Do not start a second one, and do
    not commit a stray `native/src/engine.rs` edit that is not yours.

## Chrome still open

- Recent chips (Open tabs, All, Hister, Karakeep) are one horizontal scroller
  with no overflow cue. On a narrow phone the last chips start off-screen. Wrap
  to two lines under a narrow width, or fade the trailing edge.
  File: `RecentSheet.kt`.
- Tab badge shows the raw count. Cap the display at "99+".
- Share sends only the URL. Send the title and the address (`title` then the
  URL). File: `share()` in the app module.
- Long-press the address chip to copy the full URL, with a short "Copied" and a
  TalkBack announcement.
- Recent row menu has Remove and Forget. Add Copy link and Share there, using
  the clipboard and chooser the page UI already uses.
- The IME bridge does not implement `getTextBeforeCursor` or `getCursorCapsMode`.
  Autocorrect sees an empty field. Harden it if typing on a real page is wrong.
- Address-sheet focus is requested in `LaunchedEffect` and may run before the
  sheet window is laid out. Confirm the keyboard opens and the field clears it
  on a device.
- Empty states (home, empty Recent) are plain text. One line of useful copy is
  enough. Do not add decoration that competes with the page.
- Theme roles are set in #26. Contrast of `surfaceVariant` and `outline` still
  needs a look on a real screen in both modes. Landscape and large font were not
  device-checked for Recent or Settings.
- A theme override (system / light / dark) is not built. System appearance is
  the default. Add an override only if the owner wants the phone and the room
  to disagree.

## Ideas, not Chromium parity

- Long-press Recent to focus the previously focused tab. `lastFocusedAt` already
  orders them.
- A pending mark on Save when the outbox still has work, separate from the
  per-page star.
- On the new-tab surface, one line: the last page you can reopen, and the open
  tabs. That is the Recent-home idea in miniature. Do not retire the sheet.
- Reader text size, A− / A+, remembered locally. Body text is fixed at 18 sp.
  Not a theme system.
- A snackbar when a drain finishes a queue that was waiting offline. The outbox
  already knows the count.
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
  (on, paused, excluded). Do not imply the page was indexed before Hister
  acknowledges it.

Skip: pull-to-refresh (it fights the surface), edge-swipe as the only close,
and any second renderer.

## Blocked on the pinned engine

- Stop. More shows Reload on purpose.
- Downloads. The reviewed bridge has no download event. Confirm a Servo hook
  before adding a JNI method. Do not invent one.
- Camera, microphone, location. Every permission request is denied. That is the
  safe default. A prompt needs a tagged request, the origin, and the Android
  runtime permission first.
- Text selection, pinch zoom, find in page. Not exercised. Do not add a gesture
  that fights the surface.

## Verification still open

- arm64 physical device: the stale-frame check (seen only under SwiftShader),
  pinch zoom, keyboard on a real page, dark mode, landscape, large font.
- One deliberate save and capture against the real services, then clean up.
  Do not export personal history to validate a fixture.
- Accessibility pass on Recent and Settings. Other screens have device checks.
- Personal signing key and a native build in CI before any release tag.

## How to check

- `python3 scripts/check_docs.py` and `git diff --check` always.
- Kotlin: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- `native/`: `cargo fmt --check` and `cargo test --lib`.
- Set `ANDROID_HOME` (`/home/paseo/Android/Sdk`) for Gradle on this machine.
- A worktree may hold another model's uncommitted files, including
  `native/src/engine.rs` or `ReaderView.kt`. Do not stage them.
- Other models have open pull requests. Do not read or edit those unless the
  owner asks. Do not start a second patch for an item listed under open pull
  requests above.
