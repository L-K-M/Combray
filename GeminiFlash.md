# Combray Comprehensive Review & Improvement Proposals
*By Gemini Flash*

Combray is a uniquely focused, privacy-respecting, personal Android browser pairing an embedded Servo engine with bottom-anchored ergonomics and direct personal cloud integrations (Karakeep for bookmarks/reading list, Hister for full-text personal history search, and Kagi for web search).

This review examines the entire stack across:
- Kotlin Compose Android UI (`app/`)
- Browser coordination, policy, and Room persistence (`core/`)
- Servo engine bridge and JNI lifecycle (`engine/`, `native/`)
- Offline sync, HTTP transport, and outbox workers (`core/integration/`)

---

## 1. Bugs & Correctness Issues

### 1.1 `ReaderView` Has No Close Button When Ready
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/ReaderView.kt:65-78`
- **Issue:** `ReaderView` receives `onClose: () -> Unit`. When `state is ReaderState.Unavailable`, the error view renders an `action_show_page` button that calls `onClose`. However, when `state is ReaderState.Ready`, `ArticleText` does not render any close button or top bar control. The user is trapped in reader mode unless they know to press the Android system Back button or open the More menu.
- **Impact:** Critical usability bug. Reader mode feels like a dead end for users relying on touch controls.
- **Fix:** Add a sticky or header close button (and optional reader typography/theme toolbar) inside `ArticleHeader` or floating at the top/bottom corner invoking `onClose()`.

### 1.2 `KarakeepPanel` Restarts Pagination on Any Service Entity Update
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/KarakeepPanel.kt:64-69`
- **Issue:**
  ```kotlin
  val service by integrations.karakeepService.collectAsStateWithLifecycle(null)
  LaunchedEffect(query, service) {
      val mine = ++generation
      ui = BookmarksUi.Idle
      bookmarks = emptyList()
      nextCursor = null
      if (service == null) return@LaunchedEffect
      loadPage(mine, query, null)
  }
  ```
  `service` is a Room `ServiceEntity`. Any update to the row (such as `updatedAt` bumped by an outbox drain, selecting a list, or toggling capture) emits a new `ServiceEntity` instance. This causes `LaunchedEffect(query, service)` to cancel in-flight requests, reset `bookmarks = emptyList()`, wipe `nextCursor`, and re-fetch page 1 while the user is browsing or scrolling bookmarks.
- **Impact:** Scroll jumps, flickering list, and loss of paging state whenever background sync runs.
- **Fix:** Key `LaunchedEffect` on `query` and `service?.baseUrl` (or `service?.fingerprint`), not the entire mutable entity.

### 1.3 `HisterIntegration.captureBudgetReached` Is Sticky and Never Resets on Drain
- **Location:** `core/src/main/kotlin/ch/lkmc/combray/core/integration/HisterIntegration.kt:211-215`
- **Issue:**
  ```kotlin
  if (dao.queuedCaptureBytes() + bytes > MAX_QUEUED_CAPTURE_BYTES) {
      budgetReached.value = true
      return
  }
  budgetReached.value = false
  ```
  `budgetReached` is only set to `false` when a *subsequent* page capture is attempted and passes the byte check. If the device was offline and reached the capture budget, and then connects to Wi-Fi and drains all outbox jobs to 0 bytes, `budgetReached` remains `true` indefinitely until another page is visited and captured.
- **Impact:** False-positive "capture budget reached" warnings in Settings/UI even when the queue is completely empty.
- **Fix:** Observe `dao.observeQueuedCaptureBytes()` or update `budgetReached` in `prune()`/`onDone` callback when bytes fall below the threshold.

### 1.4 Native `TouchMove` Throttling Lacks Periodic Flushing
- **Location:** `native/src/engine.rs:431-447`
- **Issue:** When touch move events are throttled, if gestures emit small bursts of moves where the last move event falls within the throttle window, the final touch position is not flushed unless followed by `TouchUp`. During continuous pinch-zoom or drag interactions, dropping intermediate coordinates without a timer flush can cause pointer jumps.
- **Fix:** Ensure the latest touch coordinates are recorded and processed before any layout or scroll frame is rendered.

### 1.5 Database Missing Index on `visits(lastSeenAt, failed)` for History Queries
- **Location:** `core/src/main/kotlin/ch/lkmc/combray/core/data/BrowserEntities.kt:44` & `BrowserDao.kt:40-49`
- **Issue:**
  ```sql
  SELECT v.* FROM visits v JOIN tabs t ON t.id = v.tabId
  WHERE v.failed = 0 AND v.lastSeenAt >= :since
    AND NOT (t.state = 'OPEN' AND t.currentVisitId IS v.id)
  ORDER BY v.lastSeenAt DESC
  LIMIT :limit
  ```
  The index on `visits` is `indices = [Index("tabId"), Index("lastSeenAt"), Index("host")]`. In a browser with thousands of visits, filtering by `failed = 0` and sorting by `lastSeenAt DESC` requires scanning index pages and table lookups.
- **Fix:** Add compound index `Index(value = ["failed", "lastSeenAt"])` to `VisitEntity`.

---

## 2. General Issues & Architecture

### 2.1 Monolithic `BrowserCoordinator` (912 lines)
- **Location:** `core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserCoordinator.kt`
- **Issue:** `BrowserCoordinator` handles native engine events, tab switching, session recovery, Room transactions, reader-view parsing, text capture throttling, page control dialogs, crash recovery, and search query dispatching.
- **Impact:** Difficult to test individual flows without mocking the database, clock, and `NativeEngine`.
- **Improvement:** Split into focused controllers:
  - `TabSessionManager`: active tab, open/close, focus, eviction.
  - `CaptureCoordinator`: debouncing, policy check, hash comparison.
  - `PageDialogManager`: alert/confirm/prompt blocking and state.

### 2.2 Lack of Export/Import for Settings and Exclusions
- **Issue:** Users configuring custom self-hosted Karakeep and Hister instances, domain exclusions, and retention rules have no way to back up or restore their setup across devices or ROM reinstalls.
- **Improvement:** Provide encrypted or local JSON backup/restore of non-secret settings and domain rules in `SettingsSheet`.

### 2.3 Strict Scheme Rejection for Standard Android Protocols
- **Location:** `core/src/main/kotlin/ch/lkmc/combray/core/policy/AddressPolicy.kt:55-56`
- **Issue:** Only `mailto:` and `tel:` are accepted as external schemes. Modern web apps commonly use `sms:`, `geo:`, `web+...`, and `intent:` (or Play Store links `market:`). Entering or clicking an `sms:` or `geo:` link is rejected with `UNSUPPORTED_SCHEME`.
- **Improvement:** Add `sms`, `geo`, `im`, and `market` to `EXTERNAL_SCHEMES` so the user can choose to open them in their preferred native Android app with confirmation.

---

## 3. Performance Problems & Stuttering

### 3.1 `publish()` Queries Database on Every Engine Event
- **Location:** `core/src/main/kotlin/ch/lkmc/combray/core/browser/BrowserCoordinator.kt:394, 808-837`
- **Issue:**
  ```kotlin
  private suspend fun publish() {
      val tab = activeTab() // executes dao.session() + dao.tab(id)
      ...
      val openCount = dao.openTabs().size // executes SELECT * FROM tabs WHERE state = 'OPEN' and loads all TabEntity objects!
      ...
  }
  ```
  `publish()` is called on *every single engine event*—including scroll ticks, IME show/hide, load phases, title changes, and control dismissals.
  Each call loads all open tabs from SQLite into memory just to check `.size`!
- **Impact:** Unnecessary SQLite disk IO, cursor allocations, object churn, and main-thread/IO contention during page loading and scrolling.
- **Fix:**
  1. Add `@Query("SELECT COUNT(*) FROM tabs WHERE state = 'OPEN'") suspend fun openTabCount(): Int` to `BrowserDao`.
  2. Cache active tab ID and open tab count in memory in `BrowserCoordinator`, updating only when tabs are created, closed, or switched.
  3. Avoid running `publish()` on inert events that do not modify coordinator state.

### 3.2 Main Bottom Bar Layout Shift on Soft Keyboard Open
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/CombrayApp.kt` & `BottomBar.kt`
- **Issue:** When the virtual keyboard (IME) appears, the entire Compose window insets adjust. If the bottom address bar does not isolate its padding and height animation from the IME insets, the bottom bar jumps abruptly above the keyboard before the keyboard animation finishes.
- **Fix:** Use Compose `Modifier.imePadding()` with animated inset sizing, keeping the address input firmly anchored above the keyboard without jank.

### 3.3 Large String Allocations in JNI Event Dispatch
- **Location:** `native/src/jni_api.rs:91-108` & `engine/src/main/kotlin/ch/lkmc/combray/engine/EngineEvent.kt`
- **Issue:** Every native event serializes a full JSON string in Rust (`event.to_json()`), allocates a `jstring` in JNI, calls Kotlin `onEngineEvent(String)`, and deserializes it with `kotlinx.serialization.json.Json`. For high-frequency events (like `Painted`, page progress, touch updates), this causes noticeable GC pressure.
- **Fix:** Filter out internal engine progress events in Rust before passing through JNI if no Kotlin listener is waiting for them.

---

## 4. Missing Features

### 4.1 In-Page Find ("Find in Page")
- **Issue:** One of the most fundamental browser capabilities is missing. Users reading long documents, articles, or documentation have no way to search for text within the current webpage.
- **Implementation Plan:**
  - Native command `FindInPage(text: String, forward: bool)` utilizing Servo's text selection or script evaluation.
  - Minimal bottom-anchored search bar with match count (e.g. `3/14`), Next/Previous arrows, and Dismiss.

### 4.2 Pull-to-Refresh & Dedicated Reload Button
- **Issue:** Currently, reloading requires opening the More menu (`action_reload`). On mobile, users expect either a swipe-down pull-to-refresh on top of the web view or an easily accessible reload button in the bottom omnibar.
- **Implementation Plan:**
  - Wrap the web view container in Compose `PullToRefreshBox` or gesture detector when scrolled to top (`scrollY == 0`).
  - Add reload/stop toggle in the More menu or as a long-press action on the address bar.

### 4.3 Reader Mode Customization (Font Size & Typography)
- **Issue:** `ReaderView.kt` hardcodes font size (`MaterialTheme.typography.bodyLarge`), line spacing, and typeface. Users with visual impairments or varying screen densities cannot adjust text size or switch between serif and sans-serif.
- **Implementation Plan:**
  - Add text size controls (`A-` / `A+`) and font family selector (Serif / Sans / Monospace) in reader mode.
  - Store reader font preferences in `BrowserSettings`.

### 4.4 Configurable Retention Period
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/SettingsSheet.kt:160`
- **Issue:** `Retention().closedHistoryDays` is hardcoded to 7 days. Users cannot configure history retention to 1 day, 30 days, or unlimited.
- **Implementation Plan:**
  - Expose a retention slider or dropdown in `SettingsSheet` (e.g., 1 day, 7 days, 30 days, 90 days, Forever).
  - Store `closedHistoryDays` in `BrowserSettings`.

### 4.5 Share Intent & Direct Share Target
- **Issue:** Combray can open URLs from other apps (`ACTION_VIEW`), but does not have a Share action to send the current tab's URL/title to other Android apps (messaging, email, notes).
- **Implementation Plan:**
  - Add a "Share link" button in the More menu invoking Android's standard `Intent.ACTION_SEND` chooser with title and URL.

---

## 5. Visual Issues & Accessibility

### 5.1 Touch Target Sizes Below 48dp
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/BottomBar.kt:120-140`
- **Issue:** Several icon buttons in `BottomBar` and `RecentSheet` have visual bounding boxes around 32-36dp without the required minimum 48dp touch target padding recommended by Android Material Accessibility guidelines.
- **Fix:** Apply `Modifier.minimumInteractiveComponentSize()` or ensure `IconButton` uses standard 48x48dp tap target padding.

### 5.2 High Contrast & System Dark/Light Theme Switching
- **Location:** `app/src/main/kotlin/ch/lkmc/combray/ui/theme/Theme.kt`
- **Issue:** Theme colors are statically defined. When the system toggles dark mode or battery saver mode, Combray should seamlessly transition its UI and report the appropriate `prefers-color-scheme` CSS media query to Servo.
- **Fix:** Support Dynamic Color (Material You on Android 12+) and sync Servo's color scheme preference with Android system dark theme state.

### 5.3 Edge-to-Edge Navigation Bar Insets
- **Issue:** On Android 15 (targetSdk 35), edge-to-edge enforcement is strict. When the bottom navigation bar or gesture pill is shown, the bottom toolbar must properly apply `WindowInsets.navigationBars` to avoid overlapping gesture bars or floating content.
- **Fix:** Audit `WindowInsets.safeDrawing` and `navigationBarsPadding()` across all root Compose containers.

---

## 6. User-Friendly, Convenient Interface Enhancements

### 6.1 Address Bar Horizontal Swipe for Quick Tab Switching
- **Concept:** In modern mobile browsers (Chrome, Safari, Firefox), swiping left or right on the bottom address bar quickly navigates between open tabs without opening the tab switcher sheet.
- **Implementation:** Add horizontal drag gesture detection to `BottomBar`:
  - Swipe Left -> Switch to next tab in MRU order.
  - Swipe Right -> Switch to previous tab.
  - Haptic feedback tick on tab change.

### 6.2 Swipe Up on Bottom Bar to Open Recent Sheet
- **Concept:** Instead of precisely tapping the small tab count badge, swiping up anywhere on the bottom navigation bar smoothly slides up the Recent/Tabs sheet.
- **Implementation:** Attach a vertical swipe gesture or Compose nested scroll connection to the bottom bar.

### 6.3 Quick Karakeep Tagging / List Selection from Save Button
- **Concept:** Long-pressing the "Save to Karakeep" bookmark icon brings up a quick menu to assign tags or pick a target list directly, without opening the full settings modal.

---

## 7. Improved Aesthetics & Theming

### 7.1 Dynamic Material You Theming (Android 12+ Monet)
- **Concept:** Adopt `dynamicLightColorScheme` and `dynamicDarkColorScheme` from `androidx.compose.material3`. The browser UI naturally reflects the user's wallpaper and personal device aesthetic.

### 7.2 True OLED Pitch-Black Mode
- **Concept:** For devices with AMOLED displays, provide a pure `#000000` surface option for dark mode, maximizing battery efficiency and aesthetic appeal.

### 7.3 Warm Sepia & E-Ink Reader Themes
- **Concept:** Reader mode should offer visual reading modes:
  - *Default:* System theme match.
  - *Sepia:* Soft cream/amber paper background (`#FBF0D9`) with dark espresso text (`#5F4B32`).
  - *Solarized:* Low-contrast teal/gray tones.
  - *E-Ink/High Contrast:* Pure black-on-white with crisp serif typography.

---

## 8. Novel, Cool, Delightful & Quirky Ideas

### 8.1 "The Madeleine" — Resurfacing Serendipitous History
- **Concept (Combray / Proust Theme):** The browser is named *Combray* after Marcel Proust's *In Search of Lost Time* (where a madeleine dipped in tea unlocks involuntary memory).
  A delightful feature would be "The Madeleine": an unobtrusive card or subtle button in the Home / Recent view that resurfaces an interesting page visited on this day last week, last month, or a forgotten gem from Hister search.
- **Why it fits:** It directly honors the browser's literary namesake while showcasing the deep local history and Hister integration in a personal, poetic way.

### 8.2 Reader Mode Reading Time & Visual Scroll Progress
- **Concept:** Calculate estimated reading time (`words / 200 wpm`) and display a subtle, elegant progress indicator at the top of Reader View as the user scrolls.

### 8.3 Distraction-Free "Zen Mode" (Auto-Hiding Bottom Bar on Scroll Down)
- **Concept:** When reading long web articles without reader mode, scrolling down smoothly slides the bottom bar off-screen. Scrolling up even slightly brings it immediately back into view. Maximizes screen real estate for the page.

### 8.4 Offline Outbox Badge & Sync Wave Animation
- **Concept:** When jobs are queued in the outbox (saving articles or capturing pages while offline), display a tiny warm pulse indicator next to settings. When the network reconnects and the queue drains, play a subtle haptic pulse and checkmark animation.

---

## 9. Prioritized Shovel-Ready Implementation Roadmap

| Priority | Item | Component | Complexity | Impact |
|---|---|---|---|---|
| **P0** | **Fix Reader View Close Button** | `app/ui/ReaderView.kt` | Low | Resolves user-trapped bug in reader mode |
| **P0** | **Fix Karakeep Re-fetch Bug** | `app/ui/KarakeepPanel.kt` | Low | Stops pagination reset on service updates |
| **P1** | **Optimize `publish()` DB queries** | `core/BrowserCoordinator.kt`, `BrowserDao.kt` | Medium | Eliminates disk IO & GC lag on every engine event |
| **P1** | **Share Link Action** | `app/ui/BottomBar.kt`, `MoreMenu.kt` | Low | Core browser functionality |
| **P1** | **Reader Mode Text Size Controls** | `app/ui/ReaderView.kt` | Medium | High accessibility & reading ergonomics |
| **P2** | **Address Bar Swipe for Tab Switching** | `app/ui/BottomBar.kt` | Medium | Smooth, delightful navigation |
| **P2** | **Dynamic Material You & OLED Dark Theme** | `app/ui/theme/Theme.kt` | Medium | Significant visual polish |
| **P2** | **"The Madeleine" History Resurfacing** | `app/ui/HomeView.kt`, `core/data/` | Medium | Delightful, quirky signature feature |
