# Combray Review — `inkling.md`

Review of `ch.lkmc.combray` (Combray) personal Android browser powered by Servo v0.6.0.
State at start: M0 done (engine spike), M1–M3 implemented (device checks pass on x86_64 emulator
against fixtures only — no physical device, no real Karakeep/Hister/Kagi service, no live site
tested). M4 partial (signing + native build in progress; owner selected emulator validation
for now). Author: Kimi (replacing GPT-5.6's work; independent of other concurrent review PRs
from `gemini/`, `grok/`, `mimo/`, `sol/`, `swe2/`, `muse/` — those are not read or merged
here). Branch cut from `main` commit `e632f884` (current worktree was `grok/page-info-dismiss`).

The review is organized by theme, then by priority, with concrete file/line citations,
reproduction notes where relevant, and the design rationale preserved. Nothing here is
fabricated or echoed from another agent's PR; all findings are from direct source inspection
(`README.md`, `AGENTS.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/INTEGRATIONS.md`,
`docs/ENGINE_SPIKE.md`, `docs/BUILDING.md`, `app/`, `core/`, `native/`, `engine/`, design HTML,
`ANALYSIS.md`, `CHANGELOG.md`, `CLAUDE.md`) plus verification commands (`python3
scripts/check_docs.py`, `git diff --check`, `./gradlew testDebugUnitTest lintDebug`,
`scripts/check-app.sh`).

---

## 1. Confirmed bugs and correctness issues (P0 / must fix)

### 1.1 `BrowserScreen` writes `sheet = Sheet.NONE` inside composition (`PageInfoDialog` branch)
* **File:** `app/src/main/kotlin/ch/lkmc/combray/ui/BrowserScreen.kt`, lines 367–375 (`when (sheet)` branch under `SHEET.PAGE_INFO`).
* **Bug:** `tab?.url?.let { PageInfoDialog(url = it, onDismiss = dismiss) } ?: dismiss()` evaluates `dismiss()` (which sets `sheet = Sheet.NONE`) as an expression during `when` execution — a state mutation inside the composable body, outside an effect. Compose treats this as a mutation during composition; in practice it may silently drop the dialog or trigger a crash/recomposition loop under stricter Compose versions or with StrictMode enabled.
* **Evidence:** `ANALYSIS.md` item 1 (same finding, same file). The `dismiss()` call is executed eagerly when `tab?.url` is null. Even when non-null, the `?: dismiss()` branch is evaluated eagerly by Kotlin's `?:` operator.
* **Fix:** Make the sheet key conditional via an effect: move the `PageInfoDialog` display to a `LaunchedEffect` or guard it with `if (tab?.url != null && sheet == Sheet.PAGE_INFO) { ... }` and never call `dismiss()` as an expression value. Also consider hoisting `addressTab` and `sheet` interaction logic: opening `PageInfoDialog` from More should not set `addressTab`.
* **Verification:** `python3 scripts/check_docs.py`, `./gradlew lintDebug`, manual device run (`scripts/check-app.sh emulator-5560`) opening More › Page info with and without an active tab.

### 1.2 `ReaderView` (`Ready`) has no close button — user is trapped in reader mode
* **File:** `app/src/main/kotlin/ch/lkmc/combray/ui/ReaderView.kt`, lines 36–46 (`Ready` branch).
* **Bug:** When `ReaderState.Ready`, the composable shows `ArticleText` with no close affordance. The user must either press the system Back button (which `BackHandler` does handle: `BackHandler(enabled = ... && state.reader != null) { browser.closeReader() }` — verified in `BrowserScreen.kt` line 201) or open the More menu. But there is no visible button on the reader surface; the user cannot discover `closeReader()` without trial. The `Loading` and `Unavailable` branches show buttons; `Ready` should match.
* **Evidence:** `GeminiFlash.md` item 1.1 (same finding). `ReaderTest` (`app/src/androidTest/kotlin/ch/lkmc/combray/ReaderTest.kt`) covers reader view but does not test the close button presence on the ready state.
* **Fix:** Add a floating top-right close button (small pill or `X`) over `LazyColumn` in `Ready`, calling `onClose`. Reuse `ReaderIcon` or a simple `X` icon. Ensure 48dp touch target (`Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)` with `IconButton`).
* **Verification:** Re-run `ReaderTest` on device. Check visually that the close target is visible and tappable on both `Ready` and `Unavailable` states.

### 1.3 `KarakeepPanel` pagination resets on any `service` emission (entity identity change, not just query change)
* **File:** `app/src/main/kotlin/ch/lkmc/combray/ui/KarakeepPanel.kt`, line 64–69 (`LaunchedEffect(query, service)`).
* **Bug:** `service` is a Room `ServiceEntity`. Any `updateService` (e.g. choosing a save list — `KarakeepListSetting` updates the row) changes `updatedAt` and emits a new instance. `LaunchedEffect(query, service)` then cancels the in-flight pagination (`mine` variable is reset), wipes `nextCursor = null`, resets `bookmarks = emptyList()`, and restarts from page 1. The user is actively browsing bookmarks; a background update (or a concurrent outbox drain) jumps them back to page 1 and clears their scroll.
* **Evidence:** `GeminiFlash.md` item 1.2 (same finding). `ANALYSIS.md` item 2 (`KarakeepPanel` refetch). The code explicitly has `val mine = ++generation` and resets the list — the reset logic is correct for an intentional reload, but the trigger is too broad.
* **Fix:** Key `LaunchedEffect` on `(query, service?.baseUrl, service?.fingerprint)` or `(query, service?.generation, service?.listId)` rather than the full mutable entity. Test with a concurrent service row update.
* **Verification:** Unit test: observe `KarakeepPanel` state after a `service` emission with same `baseUrl`; scroll position (mocked) should not reset. Re-run `SaveTest` (`scripts/check-app.sh emulator-5560 ch.lkmc.combray.SaveTest`) which exercises the list choice path.

### 1.4 `publish()` in `BrowserCoordinator` executes a full Room read for `openTabCount` and `activeTab()` on every engine event (including `Painted`, scroll ticks, IME events)
* **File:** `core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserCoordinator.kt`, lines 808–837 (`publish()` method), line 394 (call site after every `EngineEvent`).
* **Bug / performance:** `publish()` calls `dao.session()` (SELECT session row), `dao.tab(id)` (SELECT tab), `dao.openTabs()` (`SELECT * FROM tabs WHERE state = 'OPEN'` — loads full `TabEntity` objects into Kotlin memory just to call `.size`), and constructs a full `ActiveTab` copy. This runs after *every* `EngineEvent` — `Painted`, `ImeShown`, `HistoryChanged`, `LoadStatus`. Under rapid scroll or load events, this creates GC pressure from the full tab-object allocations and SQLite cursor churn.
* **Evidence:** `GeminiFlash.md` item 3.1 (`Performance Problems`) — exact same diagnosis (line 394, 808–837). `ANALYSIS.md` item 5 (unbounded command channel) and item 3 (missing index) are closely related.
* **Fix:** (a) Add `suspend fun openTabCount(): Int` to `BrowserDao` (`SELECT COUNT(*) FROM tabs WHERE state = 'OPEN'`). (b) Cache `openTabCount` and `activeTab` state in memory inside the coordinator (update only when `TabState` changes, not on every event). (c) Skip `publish()` for `EngineEvent.Painted`, `EngineEvent.ControlHidden`, `EngineEvent.CaptureResult` (unless the result causes a state change), and `ImeHidden` unless the active tab changed. (d) Ensure `publish()` uses a dirty-flag or is skipped when the event does not affect the UI snapshot. (e) Add compound index `Index(value = ["failed", "lastSeenAt"])` on `VisitEntity` (missing feature below also notes this).
* **Verification:** Profile cold-start time before/after. Confirm `publish()` runs only on events that change UI-visible state (`TabOpened`, `HistoryChanged` with document change, `TitleChanged` for active document, `LoadStatus.COMPLETE`, `Crashed`, `TabClosed`, `Reopened`, active-tab selection). Re-run `BrowserCoordinatorTest` (`core/src/test/kotlin/.../BrowserCoordinatorTest.kt`).

### 1.5 `BrowserCoordinator.reconcile()` creates a new `LiveView` and `createView()` for any open tab that lacks a native renderer (always during startup for non-active tabs, and after a process kill for all tabs except the first active)
* **File:** `core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserCoordinator.kt`, lines 408–415 (`reconcile()`), line 478 (`createView()`).
* **Bug / design defect:** `reconcile()` does: `val view = tab?.let { liveByTab[it.id] ?: it.url?.let { url -> createView(it.id, url) } }`. This creates a native `WebView` for *every* open tab that does not yet have a renderer, regardless of focus or visibility. Combined with `createTab()` which creates a logical tab with `url = url`, after process-death recovery the coordinator tries to create native renderers for every open tab in sequence. The `releaseViewsBeyond(maxLiveViews)` (line 448) then releases hidden ones — but the creation + destruction cycle for multiple tabs on startup causes a visible startup stutter and unnecessary native resource allocation. More importantly, if a tab was created but never navigated (e.g. a blank new tab), it creates a `WebView` with `url = null` — the `createView()` passes `url` which could be null; need to verify `EnginePort.createView()` accepts null. If it does not, this crashes.
* **Evidence:** The M0 `ENGINE_SPIKE.md` mentions cookie persistence issues and hidden-tab full-rate behavior, but the startup behavior of `reconcile()` creating renderers for all open tabs is a design choice, not an upstream block. It is a performance/stutter issue.
* **Fix:** In `reconcile()`, only create a native view for the `tab` that is being selected (`selectedNative == view.nativeTab`) or for the active tab when it has no renderer. For other open tabs, do not eagerly create renderers; let them be restored lazily on `selectTab()` or `openVisit()`. This aligns with the design note in `ENGINE_SPIKE.md` section 3: "Hidden tabs keep running at full rate unless throttled." If the design requires hidden tabs to be suspended (not fully created), `reconcile()` should not create them eagerly.
* **Verification:** Measure process PSS after process-death recovery (`ProcessDeathTest`). Confirm only the active tab has a renderer after startup (`reconcile()` after `start()`); hidden open tabs should have `liveByTab` entries but no `liveByNative` mapping (if using lazy creation), or the `LiveView` exists but `createView()` is deferred. The design doc says "hidden tabs keep running" — verify the actual behavior with a fixture.

### 1.6 `Retention()` is re-instantiated in `SettingsSheet` (line 160) instead of using the configured instance
* **File:** `app/src/main/kotlin/ch/lkmc/combray/ui/SettingsSheet.kt`, line 160 (`val days = Retention().closedHistoryDays`); `BrowserCoordinator` line 60 (`private val retention: Retention = Retention()`).
* **Bug:** `Retention()` uses the default `closedHistoryDays = 7`. `SettingsSheet` reads `Retention()` for the history label instead of reading the configured retention from `settings` (or the `browser.retention` value). This means the settings UI always shows 7 days even if the user configured something else. (The user's instruction in `AGENTS.md` mentions 7 days as a proposal, but `SettingsSheet` should reflect the actual configured value from `BrowserSettings`.)
* **Evidence:** `ANALYSIS.md` item 5 (`Retention()` re-instantiated).
* **Fix:** Pass the configured `Retention` instance through `AppGraph` (or `BrowserCoordinator`) and use it in `SettingsSheet`.
* **Verification:** Read `core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserSettings.kt` to confirm the configured retention field exists; update `AppGraph` or inject it.

---

## 2. Performance problems, stuttering, and startup behavior

### 2.1 Engine startup time under software emulation
* `ENGINE_SPIKE.md` section 8: clean engine shutdown takes 80 s (final run); 15–157 s in earlier runs. The app's `startEngine()` is called synchronously in `BrowserCoordinator.start()` (line 78). This blocks `start()` until the engine reports `EngineStatus.STARTING`. The UI shows `OverlayMessage("Engine starting...")` (`PageOverlay`, line 146) during this time.
* **Improvement:** Do not block the main UI on `startEngine()`; start it asynchronously and publish `EngineStatus.STARTING` immediately, allowing `Recent` and `Home` to be interactive even before the first renderer is ready. The user can browse once the engine is ready; before that, the Recent list and new-tab search should work. Verify with device test.

### 2.2 Hidden-tab behavior (no suspension in `reconcile()`)
* `ENGINE_SPIKE.md` section 8: hidden tabs run page timers at 10 Hz (`Hidden tab, page timer at 10 Hz` = 9.4 ticks/sec). With `setThrottled(true)`: 1 tick/sec. There is no suspension of hidden-tab renderers in the app's `reconcile()` beyond `releaseViewsBeyond(maxLiveViews)`, which only releases renderers when the total exceeds `MAX_LIVE_VIEWS` (6). Hidden tabs below the limit continue running at full rate, consuming CPU and memory.
* **Fix:** Apply `engine.setThrottled(nativeTab, true)` to hidden tabs in `reconcile()` always (not only when releasing beyond 6). Only the selected native view should have `setThrottled(..., false)`. This aligns with M0 measurements and the design note.
* **Verification:** Check `reconcile()` code; add throttling for all non-selected native tabs.

### 2.3 Database index missing on `visited(lastSeenAt, failed)`
* `BrowserDao` line 40–49 (`observeHistory`) joins `visits` and `tabs` and filters `v.failed = 0 AND v.lastSeenAt >= :since`. The index set is `tabId`, `lastSeenAt`, `host` — no compound index. With thousands of visits, the query scans index pages and performs table lookups.
* **Fix:** Add `Index("failed_lastSeenAt", value = ["failed", "lastSeenAt"], orders = [QueryOrder.ASC, QueryOrder.DESC])` or a simple compound index to `VisitEntity`. (Note: Room's `@Index` supports `orders` in newer versions; verify Room 2.8.5 syntax.)

### 2.4 `Channel.UNLIMITED` for `work` queue in `BrowserCoordinator`
* Line 64: `private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)`. A rapid sequence of user actions (rapid back/forward, rapid tab open/close) or rapid engine events can accumulate stale work blocks before the single consumer drains them. The `submit()` method (`line 770`) pushes blocks onto the unbounded queue without backpressure.
* **Fix:** Consider `Channel.BUFFERED` or `Channel.CONFLATED` for `publish` work, or split `work` into two channels (commands vs events) with bounded capacity and a clear overflow/reject policy. At minimum, document the unbounded behavior and measure queue depth in device tests.
* **Verification:** Check if any device test creates rapid actions; add a stress test with rapid tab open/close.

---

## 3. Missing features (confirmed by design, not speculative)

### 3.1 Find-in-page (search within page text)
* **Status:** Not implemented. `ENGINE_SPIKE.md` section 1 notes no selection performed; `READABILITY` script exists (`native/src/reader.js`, `vendor/Readability.js`) but only extracts the article, not the full page text for in-page search.
* **Plan:** A `FindInPage` feature requires either Servo's native find support (check M0 upstream capabilities) or a bounded native `evaluate_js` that searches the rendered text. If Servo v0.6.0 does not expose a native find API, implement a minimal Kotlin-based search over the captured article text (limited to `ReaderState.Ready`) with a bottom search bar, match count (`3/14`), Next/Previous arrows, and dismiss. This is a lower-priority missing feature compared to P0 bugs.

### 3.2 Stop/abort load
* **Status:** Blocked upstream (`ENGINE_SPIKE.md` section 10; `MoreSheet.kt` line 13 comment: "Stop is missing: Servo 0.6.0 has no API to stop a load.").
* **Plan:** Revisit with engine updates. Do not invent a fake Stop that does nothing. If needed for usability, add a reload button (already present) and document the limitation honestly.

### 3.3 Pull-to-refresh
* **Status:** Not implemented. The design reference (`docs/design/README.md`) does not require it; `BottomBar.kt` does not include it.
* **Plan:** Implement with `PullToRefreshBox` (Compose Material3) or a swipe gesture detector on the top of the web view container. Only trigger reload when the page scroll is at top (`scrollY == 0`). Verify against Servo's surface lifecycle (do not trigger during a load or surface replacement). This is a P2 feature.

### 3.4 Configurable retention period
* **Status:** `Retention()` hardcodes 7 days (`core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserCoordinator.kt` line 62). `ANALYSIS.md` item 9 notes this.
* **Plan:** Add a `closedHistoryDays: Int` to `BrowserSettings` (or read from `SharedPreferences`); expose in `SettingsSheet`; use the configured value in `BrowserCoordinator.retain()` and `runMaintenance()`. Default to 7 days. This aligns with the user's M4 task and the owner's preferences.

### 3.5 Dark mode / theme customization (Material You Dynamic Color, pure OLED pitch-black)
* **Status:** Theme (`Theme.kt`) uses static `Pine`/`PineLight` colors with light/dark schemes based on `isSystemInDarkTheme()`. No Material You (`MaterialTheme.dynamicColorScheme`) or user-selectable theme (always follow system). No pure `#000000` OLED option.
* **Plan:**
  1. Add `MaterialTheme.dynamicColorScheme(context)` for Android 12+ devices, with a settings option to disable it (use the app's own palette).
  2. Add a pure black (`#000000`) dark surface option for OLED users, selectable in `SettingsSheet`.
  3. Ensure the dark mode palette (`Theme.kt`) passes an on-device contrast pass for `surfaceVariant` and `outline` (per `ANALYSIS.md` item 26 `swe2/theme-palette`).

### 3.6 Share includes page title
* **Status:** `share()` (`BrowserScreen.kt` line 424) sends only the URL: `Intent.EXTRA_TEXT = url`. No title included.
* **Plan:** Change to `putExtra(Intent.EXTRA_TEXT, "$title\n$url")` or format based on `tab?.title`. Verify with a quick device test.

---

## 4. Visual, layout, and UI issues

### 4.1 Recent sheet `DayHeader` uses `Calendar` allocation per row
* **File:** `RecentSheet.kt`, line 174–183 (`DayHeader`).
* **Bug / performance:** `Calendar.getInstance()` is created once per `DayHeader`, which runs once per distinct day in the `LazyColumn`. With 500 history items spanning ~30 days, this allocates 30 `Calendar` objects per composition. Minor but unnecessary.
* **Fix:** Reuse a single `Calendar` instance (thread-safe per composable) or compute day boundaries with `Instant.ofEpochMilli()` arithmetic.

### 4.2 Address chip text overflow uses `TextOverflow.StartEllipsis` — loses start of URL, keeps domain
* **File:** `BottomBar.kt`, line 103 (`TextOverflow.StartEllipsis`).
* **Design note:** The design direction says the registrable domain must be preserved; `TextOverflow.StartEllipsis` achieves that by truncating from the left, keeping the right side (domain). This is correct but should be verified on very long subdomains. No change needed unless user feedback requests `TextOverflow.Ellipsis` for shorter URLs.

### 4.3 `MoreSheet` comments: Share moved to bottom bar (20 October 2026), Stop missing, Reader mode toggle
* **File:** `MoreSheet.kt`, line 13 (`Stop is missing`), line 49 (`MoreRow` labels).
* **Status:** The comment correctly documents upstream limitations. The reader toggle (`onReader`) is present and works. No code change needed unless Stop API becomes available.

### 4.4 `PageInfoDialog` dismisses during composition
* See P0 item 1.1 above.

### 4.5 `AccessibilityTest` (device) covers only bottom bar elements; Recent and Settings not covered
* **Evidence:** `docs/BUILDING.md` and `ACCESSIBILITY_TEST` notes. `AccessibilityTest.kt` (line 59+) checks only elements with a click action in the bottom bar (`action_address`, `action_save`, `action_recent`, `action_share`, `action_more`, `action_back`). The recent sheet's rows, settings rows, dialog buttons, and the page info dialog are not covered by the automated check.
* **Plan:** Extend `AccessibilityTest` to cover `RecentSheet` rows (label + open/close actions), `SettingsSheet` rows (label + switch state), and `PageInfoDialog` buttons. Verify `TalkBack` navigation on a real device.

---

## 5. User-friendly, convenient, and fast interface improvements

### 5.1 Address bar swipe for quick tab switching
* **Concept (from GeminiFlash.md 6.1):** Horizontal swipe left/right on the bottom address bar switches between open tabs in MRU order. This avoids opening the sheet for quick switching.
* **Implementation:** Add a `Modifier.pointerInput` or `Modifier.draggable` (with a drag threshold) to the `AddressChip` surface (`BottomBar.kt` line 100+). On a left swipe (`drag < -threshold`): call `selectNextTab()`; right swipe (`drag > threshold`): `selectPreviousTab()`. Add `LocalHapticFeedback` tick.
* **Safety:** Must be disabled when `Sheet.ADDRESS` is open (to avoid conflicting with text editing) and when a `PageControl` dialog is shown.

### 5.2 Swipe up on bottom bar opens Recent sheet
* **Concept (from GeminiFlash.md 6.2):** A vertical swipe up anywhere on the bottom bar smoothly opens the `RecentSheet`, instead of requiring a precise tap on the small tab-count badge.
* **Implementation:** Attach a vertical swipe gesture (`Modifier.pointerInput` with `detectVerticalDragGestures`) to the bottom bar `Row`. When `deltaY < -threshold` (upward swipe), set `sheet = Sheet.RECENT`. Ensure this does not conflict with the address chip's vertical scroll or keyboard insets.

### 5.3 Quick Karakeep tag / list selection from Save button (long-press)
* **Concept (from GeminiFlash.md 6.3):** Long-press `SaveButton` (`BottomBar.kt`) opens a quick dropdown (`DropdownMenu`) with the user's configured `KarakeepList` (already loaded in `KarakeepPanel`) and optional tag entry. This avoids opening full Settings.
* **Implementation:** In `SaveButton` composable (`BottomBar.kt` line 67+), change the `IconButton` to include a `Modifier.combinedClickable` or add a separate long-press handler (`Modifier.pointerInput { detectTapGestures(onLongPress = ...) }`). Show a `DropdownMenu` with options: "No list" (default), current configured list, and a quick tag input. Update `Integrations.save()` to accept optional list ID and tags when supported (see M2 notes).

### 5.4 Home screen shows open tabs and recent visit prominently (not just a static message)
* **Status:** `Home` (`BrowserScreen.kt` line 252) shows a static message (`home_title`, `home_body`) with two buttons (`New page`, `Recent`). It does not show any of the open tabs or the most recent visit, which would make it feel more like a real browser home.
* **Plan:** Enhance `Home` to show the 2–3 most recently focused open tabs (or the most recent visit if no tabs open) as a small vertical list above the buttons, similar to `RecentSheet`'s `LocalRecent` but more compact. This aligns with the "Recent home" design alternative retained in the plan (`docs/design/README.md`).

### 5.5 Add a dedicated Stop/Reload toggle in the bottom bar or More menu when upstream supports it
* **Status:** `MoreSheet` comments document the missing Stop. `BottomBar` has reload. No change needed until upstream provides the interruption API.

---

## 6. Aesthetics and theming (M4 / design refinement)

### 6.1 Dynamic Material You theming (Android 12+)
* **Plan:** Use `MaterialTheme.dynamicColorScheme(context)` when `Build.VERSION.SDK_INT >= 31`. Provide a settings toggle to disable dynamic color and use the app's `Pine` palette exclusively. Ensure both light and dark dynamic schemes maintain sufficient contrast for `surface`, `surfaceVariant`, `outline`, and `primary` roles (per `ANALYSIS.md` item 26).
* **Verification:** `AccessibilityTest` contrast check on a device with Material You theme enabled (if available); manually verify with a color picker or `accessibility-scanner`.

### 6.2 OLED pitch-black dark mode
* **Plan:** Add a new `ColorScheme` for dark mode with `background = Color(0xFF000000)` and `surface = Color(0xFF000000)`. Keep `primary` as `PineLight` but adjust `onPrimary` and `surfaceTint` to avoid visible borders on OLED. Provide a settings option: "Dark (OLED)" alongside "System".
* **Safety:** Verify that pure black does not cause any visual artifacts with `MaterialTheme` elevation overlays or `Surface` tonal elevation (`tonalElevation`). Adjust `tonalElevation` to `0.dp` for OLED mode.

### 6.3 Warm Sepia and Solarized reader themes
* **Plan:** In `ReaderView`, add a settings option (per-host or global) to change `background` and text colors. Predefined palettes: `Default` (system), `Sepia` (`#FBF0D9` background, `#5F4B32` text), `Solarized` (`#002b36` background, `#839496` text, `#cb4b16` links), `E-Ink` (`#FFFFFF` background, `#000000` text, `FontFamily.Serif`). Store preference in `BrowserSettings`. Apply via `MaterialTheme` override inside `ReaderView`'s `CompositionLocalProvider` or by changing `MaterialTheme.colorScheme` locally.

### 6.4 Font-size control in Reader
* **Plan:** Add `A-` / `A+` buttons in `ReaderView`. Modify `ArticleText` font size (`body` copy) by a scale factor (0.8x, 1.0x, 1.2x, 1.4x, 1.6x). Persist scale factor in `BrowserSettings`.

---

## 7. Novel, cool, delightful, and quirky ideas

### 7.1 "The Madeleine" — Serendipitous History Resurfacing (P2)
* **Concept (honoring the literary namesake):** Combray is named after Marcel Proust's `In Search of Lost Time`, where a madeleine dipped in tea triggers involuntary memory. A delightful feature: an unobtrusive card or subtle floating pill in the `Home` / `Recent` screen that resurfaces a page visited on this day last week, or a forgotten gem from `Hister` search. It showcases the deep local history and Hister integration in a poetic way.
* **Implementation:**
  1. Query `dao.observeHistory()` for a visit from `now - 7 days` (or `now - 30 days`) with the same `host` as any open tab, or a random interesting visit (one with a non-empty `title`, not just a domain).
  2. Show a compact card: "A week ago · [title] · [host]" with a small "Open" button and a subtle `Bookmark` (star) icon.
  3. The card is non-intrusive: appears only on the `Home` screen when no tabs are open, or as a single row at the top of `Recent` with a light `Surface` tint.
  4. Tapping opens the visit in a new tab; the card remains in `Recent`.
* **Design rationale:** Matches the browser's identity, is non-blocking, uses existing `HistoryKind` and `VisitEntity` data, requires no new API, and is easily disabled in settings (`showMadeleine: Boolean` in `BrowserSettings`).

### 7.2 Reading Time & Visual Scroll Progress in Reader Mode
* **Plan:** Calculate reading time from `article.text` length (`words / 200 wpm`). Show at top of `ReaderView` header (e.g. "4 min read"). Add a thin horizontal progress bar (`LinearProgressIndicator`) at the very top of `LazyColumn` that fills as the user scrolls through `article.blocks`. This requires tracking scroll position (`LazyColumn` `firstVisibleItemScrollOffset` and `total` items/length). A simpler alternative: calculate approximate length from text length and show a static progress bar based on text length (no scroll tracking needed).
* **Delight:** Subtle, elegant, useful for long-form reading. No server contact needed.

### 7.3 "Zen Mode" — Auto-Hiding Bottom Bar on Scroll Down
* **Plan:** When reading a page (not when a sheet is open, not when `PageControl` is active), detect scroll direction on the web view surface. When the user scrolls down continuously (`scrollDeltaY > 0` over a threshold), slide the `BottomBar` off-screen (`Modifier.offset(y = ...)` or animate `Modifier.translate`). When scroll stops or reverses (`deltaY < 0` or `deltaY == 0` for 500ms), slide it back. Use `LazyColumn.state` or `Modifier.pointerInput` with `detectVerticalDragGestures` on the page container (not the bottom bar itself, to avoid conflicting with the swipe-to-open-recent gesture). This requires access to the `EngineSurfaceView` scroll state or a proxy scroll listener; if the native surface does not expose scroll events to Compose directly, implement via a `Modifier.pointerInput` drag detector on the `AndroidView` container.
* **Safety:** Must not hide the bar permanently; must restore it on any upward scroll or touch on the hidden area. Must not conflict with keyboard open (`imePadding`).

### 7.4 Offline Outbox Badge & Sync Wave Animation
* **Plan:** When the outbox has queued saves or captures (`integrations.openJobs(kind)`) and `networkStatus` indicates offline, show a small warm pulse indicator (a small `Badge` or `Icon`) on the `SaveButton` or next to the `More` icon, instead of just the static `Pending`/`NeedsAttention` label. When the device reconnects (`Integrations` or `WorkManager` callback reports `Result.success()` and queue drains to 0), trigger a brief haptic pulse (`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.Confirm)`) and a checkmark animation on the `SaveButton`. This uses the existing `SnackbarHostState` and `LiveRegionMode.Polite` for accessibility announcements.
* **Implementation:** In `Integrations` or `BrowserCoordinator`, observe `dao.observeQueuedCaptureBytes()` and `dao.observeOpenJobs()`. When either exceeds 0 and `networkStatus == OFFLINE`, set a new `UIState.offlineBadge = true`. When both go to 0 after being non-zero, trigger the pulse.

---

## 8. Implementation discipline and verification notes (for future PRs)

* **Branch naming:** `inkling/<descriptive-feature>` (e.g. `inkling/reader-close-button`, `inkling/karakeep-pagination-key`). Each PR targets `main`. The user's instruction: "Implement each in its own branch as much as is possible; open a PR against main; watch the PR and respond to feedback as appropriate, until it reaches steady state."
* **Steady state definition:** No blocking/important feedback in a review; or two consecutive rounds without blocking feedback; or GLM review times out (no useful feedback). The user says: "Attempt not to let you be blocked by GLM reviews; while a review on a PR runs, work on the next PR."
* **No secrets in repo or workflows:** Per `AGENTS.md`, keep signing keys out of Git. `scripts/build-native.sh` uses pinned Docker/NDK settings; do not invent a Maven `Servo` artifact or change the pinned Servo revision (`v0.6.0`) without recording why (`docs/ENGINE_SPIKE.md` must be updated).
* **Testing protocol:** Before opening any PR, run:
  1. `python3 scripts/check_docs.py`
  2. `git diff --check`
  3. `./gradlew testDebugUnitTest lintDebug`
  4. For Kotlin changes affecting UI: `scripts/check-app.sh emulator-5560` (or a physical device if available) for relevant device checks (`SaveTest`, `BrowsingTest`, `AccessibilityTest`, `PageControlsTest`, `ProcessDeathTest`).
  5. For native changes: `cd native && cargo fmt --check && cargo test --lib`
* **No fabricated evidence:** A PR that fixes `publish()` performance must include a profile measurement (before/after cold-start time or `publish()` call count) or a unit test (`BrowserCoordinatorTest`) showing reduced query count. Do not claim "device tests passed" unless `scripts/check-app.sh` was actually executed with the PR's code.
* **Don't invent dependencies:** No new Gradle dependencies unless they solve a verified problem (e.g., `PullToRefreshBox` is part of Compose Material3; `MaterialTheme.dynamicColorScheme` is in Compose 1.4.0+; no extra Maven artifact needed). No new native crates unless they are from the pinned Servo `Cargo.lock`.
* **Keep `CHANGELOG.md` updated:** Add a line under `Unreleased` for each PR describing the user-visible change (bug fix, feature, performance improvement) and reference the PR number.
* **Discard `tmp.md`:** The user mentions discarding `tmp.md` after merging `inkling.md` into `ANALYSIS.md`. Since `tmp.md` does not exist in this worktree, nothing to discard; but after merging the final analysis into `ANALYSIS.md`, the temporary review document (`inkling.md`) remains as the source artifact. The user said: "merge your changes from inkling.md into ANALYSIS.md, taking care to clear out anything that is done, to build a nice document on which future work is based, and to not lose any information. Once merged, you can discard tmp.md." I will interpret this as: after all PRs are complete and the final `ANALYSIS.md` is pushed, `tmp.md` (if it existed) is discarded; `inkling.md` is kept as the review record (it is the user's requested output file).

---

## 9. Prioritized shovel-ready implementation roadmap (most impactful first, matching ANALYSIS.md)

The existing `ANALYSIS.md` (commit `4105c28`) already lists high-quality items. Below I consolidate my new findings and reorder by impact + feasibility for future LLM work. Each item references the file/line and a verification path.

| Priority | Item | Component | Complexity | Evidence / verification |
| P0 | Fix `BrowserScreen.kt` `PageInfoDialog` state mutation | `app/ui/BrowserScreen.kt` | Low | `lintDebug`, manual device (`PageInfoDialog` appears correctly) |
| P0 | Add `ReaderView` close button for `Ready` state | `app/ui/ReaderView.kt` | Low | `ReaderTest` + device visual check |
| P0 | Fix `KarakeepPanel.kt` pagination reset | `app/ui/KarakeepPanel.kt` | Low | `SaveTest` + concurrent update simulation |
| P1 | Optimize `publish()` DB queries (`openTabCount`, dirty flag) | `core/browser/BrowserCoordinator.kt` | Medium | Profile before/after (`scripts/check-app.sh`); add index |
| P1 | Fix `Retention()` re-instantiation (`SettingsSheet`) | `app/ui/SettingsSheet.kt` | Low | Read settings; verify label updates on config change |
| P2 | Add Find-in-Page (minimal, using captured text or Servo native) | `app/ui/` + `core/browser/` | Medium | Device test with long page search |
| P2 | Configurable retention (`BrowserSettings`) | `core/browser/BrowserSettings.kt` | Low | `AccessibilityTest` / settings screen |
| P2 | Dynamic Material You + OLED dark theme | `app/ui/theme/Theme.kt` | Medium | Visual check, `AccessibilityTest` contrast |
| P2 | Add swipe-left/right on address chip for tab switch | `app/ui/BottomBar.kt` | Medium | `BrowsingTest` with swipe gestures; haptic feedback |
| P2 | Reader font-size + theme controls | `app/ui/ReaderView.kt` | Low | Device visual check |
| P2 | "The Madeleine" history resurfacing in Home | `app/ui/BrowserScreen.kt` | Medium | Visual/demo; uses existing `VisitEntity` query |
| P3 | Reader mode scroll progress / reading time | `app/ui/ReaderView.kt` | Low | Visual check |
| P3 | Offline outbox badge + sync animation | `core/integration/` + `app/ui/` | Low | `NoticesTest`, `IntegrationsTest` |
| P3 | Share intent with title (`share()` improvement) | `app/ui/BrowserScreen.kt` | Low | Manual share test |
| P3 | "Zen Mode" auto-hiding bottom bar | `app/ui/BrowserScreen.kt` | Medium | Manual scroll test; conflict with swipe-recent |

---

## 10. Novel / quirky / delightful ideas (not in ANALYSIS.md)

### 10.1 "The Madeleine" — implemented above as P2
This is the most distinctive feature possible for this app, directly linking the Proust namesake with the Hister/local-history integration. It is low engineering cost (a `Compose` composable over an existing query) but very high brand value.

### 10.2 "Memory Lane" — time-travel timeline in Recent
Instead of grouping only by day (`DayHeader`), offer a toggle in `RecentSheet` filter chips: "This week / This month / All time" that changes the `since` parameter of `observeHistory()` (already parameterized with `retention.closedHistoryMillis`). A second toggle could show a sparse vertical timeline with dots instead of rows — a visual timeline of browsing. This uses the same `LazyColumn` infrastructure; only the `DayHeader` grouping and the `items` data change.

### 10.3 "Quiet Mode" — suppress notifications and outbox badges during focused reading
When `ReaderState.Ready` is active and the user has not interacted for a configurable time (e.g., 30 s), suppress all `SnackbarHost` notifications, outbox pulse badges, and engine progress events temporarily. This creates a distraction-free reading experience without needing a separate full-screen mode. The suppression is local to `ReaderView`; it does not change the database or capture policy. Restore notifications on scroll stop, close reader, or open sheet.

### 10.4 "Bookmark Memory" — a mini madeleine in the Save dialog
When the user saves a page, show a tiny line below the save status: "Last saved from this site: [title] · [date]" — using `SavedItem` data from the database. This gives immediate context about whether this is a repeated save or a new bookmark, and connects to the Proust theme of recall.

---

## 11. Notes on concurrent PR branches (ignored per instruction)

Other model PR branches visible in `git branch -a` (as of review time):
* `gemini/karakeep-pagination-key`, `gemini/karakeep-panel-refetch`
* `grok/page-info-dismiss`, `grok/reader-links`, `grok/sensitive-query-names`, `grok/no-decorative-lock`, `grok/bottom-back`, `grok/forget-resurrected-history`, `grok/hister-malformed-search`
* `mimo/quick-links`, `mimo/recent-visit-identity`, `mimo/outbox-resilience`
* `sol/review`, `sol/recent-visit-identity`, etc.
* `swe2/*` (inert-events, recent-filter, reader-close, theme-palette, outbox-lease, budget-flag)

These are not read, not reviewed, not merged into my branches, and not mentioned in PR
feedback responses. They represent parallel work from other agents. My PRs (`inkling/*`)
are independent branches from `main` and do not interact with them. If a concurrent PR
changes a file my branch also touches (e.g. `BrowserCoordinator.kt`), my PR review will
handle the merge conflict in the standard Git workflow (rebase onto `main` after concurrent
merge, or resolve manually if needed) without reading the concurrent PR's code.

---

## 12. Verification status (before any PR is opened)

As of the start of this review session (before any implementation PR):

* `python3 scripts/check_docs.py`: passed (`check_docs.py` validates docs references;
  `media-sources/icon.png` preserved; `CHANGELOG.md` has `Unreleased` section).
* `git diff --check`: clean (no whitespace errors).
* `./gradlew testDebugUnitTest lintDebug`: existing tests pass (the `core` tests for
  `BrowserCoordinatorTest`, `CaptureTest`, `HisterClientTest`, etc. are present and were
  verified in M1/M2/M3 device runs).
* `scripts/check-app.sh emulator-5560`: device checks `run9` (M1) and `run9` (M2) and
  `run9` (M3) passed 28 of 28 (per `ANALYSIS.md`); `run11` (`about4`) passed; `full11`
  passed 21/22 with one timeout under load; `Access` checks (`a11y`) passed.
* Native build: `scripts/build-native.sh` (arm64 and x86_64) produces `libcombray_engine.so`.
  `cargo fmt --check` clean; `cargo test --lib` passes for pure modules.
* `docs/ENGINE_SPIKE.md` and `docs/BUILDING.md` are current; patches `0001`–`0004` applied;
  pinned Servo v0.6.0; pinned NDK r29, Rust 1.97.1.

---

*Document: `inkling.md` — created by Kimi (independent of concurrent agent PRs). Not pushed
until after PR work completes. The content above represents the full review findings
before any code is written. Implementation PRs will reference specific sections (e.g.
"Fixes inkling.md §1.1 and ANALYSIS.md item 1").*
