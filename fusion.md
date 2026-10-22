# Combray - Fusion review

Date: 21 October 2026. Source baseline: `4105c28f29a52af571d8ca780e9a992e7d8cf5a4`.

What this repo does: Combray is a personal Android browser built around Servo,
native bottom controls, and a unified Recent list. Karakeep owns deliberate
saves, Hister owns long-term content recall, and Kagi handles ordinary web search.
I assume one person, intermittent connectivity, a process that can live for
days, hundreds of open tabs, and more than 500 visits within the seven-day
history window. This is not an audit for a public multi-user browser service.

## Scope and evidence

Core shorthand expands to `core/src/main/kotlin/ch/lkmc/combray/core/`, app
shorthand to `app/src/main/kotlin/ch/lkmc/combray/`, and engine shorthand to
`engine/src/main/kotlin/ch/lkmc/combray/engine/`. UI basenames are under the
app's `ui/` directory. Line references use the audited baseline.

- I traced Android launch and lifecycle, navigation and tab identity, Room
  persistence and retention, engine ownership and input, capture and reader
  extraction, credentials, HTTP routing, outbox dispatch, Compose screens, and
  build/release gates. I read the associated tests and integration contracts.
- Findings below distinguish source-proven behavior, concurrency gaps, product
  omissions, and things that still need device measurements. A code path that
  could cause stuttering is not a measured frame-rate regression.
- The isolated baseline passes
  `ANDROID_SDK_ROOT=/home/paseo/android-sdk ./gradlew --offline :core:testDebugUnitTest`.
  Its raw XML results are under `core/build/test-results/testDebugUnitTest/`.
- The baseline
  `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :engine:testDebugUnitTest`
  passed; its log is `build/fusion-audit/gradle-baseline-build.log`. Of the UI
  captures only `build/fusion-audit/01-home.png` was visually inspected: it
  shows the engine-unavailable chrome, not a rendered webpage. The screenshot
  matrix and native/device behavior were not validated; no frame or power
  numbers are claimed.
- The design HTML is a concept with sample data, not evidence that the Android
  interface or integrations behave that way.
- No other agents' PRs were read or changed. Their existing edits were left
  alone. The review uses a separate worktree and an immutable source revision.
- The initial audit and report were completed before application code changed; implementation results are recorded separately.

## Must fix

### 1. Encoded credential names bypass the save/capture guard

**What this is:** `SensitiveUrls.looksSensitive` protects Karakeep and Hister
from obvious login/session links. Evidence:
`core/.../integration/SensitiveUrls.kt:17-27`; its callers are
`Integrations.save` and `HisterIntegration.eligible`.

**Problem:** It compares raw parameter names. For example,
`https://example.org/?%74oken=secret` and
`https://example.org/#access%5Ftoken=secret` do not match its credential-name
set, although a server or page can decode them to `token` and `access_token`.
The complete address can then be sent to a service.

**Fix:** Decode each query/fragment parameter name once before matching, not the
whole URL or parameter value. Preserve the deliberately narrow guard; do not
pretend it detects arbitrary secrets. Treat malformed encodings conservatively.

**Proof to add:** Plain, encoded, mixed-case, and fragment credential names are
blocked by both save and capture; ordinary encoded search parameters remain
allowed. No token values enter assertions' failure messages or logs.

**If skipped:** An existing privacy promise has an avoidable, easy bypass.

### 2. Pausing capture can still dispatch an already-claimed job

**What this is:** Capture pause should stop unsent work at the last safe moment
before transport. Evidence: `HisterIntegration.kt:132-142,242-250`,
`IntegrationDao.kt:53-68`, and `OutboxDispatcher.kt:39-65`.

**Problem:** The due-job query checks `captureEnabled`, but `dispatch` does not.
Claim a job, pause capture through Settings, then dispatch that claim: its new
service snapshot can have capture disabled and still send. The dispatcher
switches to an IO context between claiming and invoking the handler, so this
is not protected by the browser command queue.

There is also an admission-boundary risk: `captured` checks policy generation
before several suspending calls, while Settings exclusions/configuration can
change independently. Revalidate at enqueue rather than assuming the initial
check remains current. However, normal Forget and capture-result handling are
serialized by `BrowserCoordinator`; this review does not claim that Forget can
finish inside an ongoing production `captured` call. A direct concurrent sink
test alone would not establish that production interleaving.

**Fix:** Recheck capture-enabled state immediately before dispatch and retain
paused work without consuming a network-attempt budget or falsely succeeding.
Serialize/revalidate policy with durable capture admission. Keep the limit
explicit: bytes already sent cannot be recalled. This is separate from making
local history deletion and cancellation one transaction.

**Proof to add:** Pause a claimed-but-unsent capture and assert no HTTP request;
resume and send it without a false authentication failure or lost attempt.
Gate an actual Settings policy change during suspended capture admission and
assert stale work is not newly persisted. Test the production coordinator
ordering rather than inventing a concurrent Forget call path.

**If skipped:** A completed Pause action can still be followed by a new upload
from a claim that had not reached transport yet.

### 3. A rendering-context startup failure leaves the engine "starting"

**What this is:** Servo starts when the first Android surface binds. Evidence:
`native/src/engine.rs:454-462`,
`core/.../browser/BrowserCoordinator.kt:730-741`, and `BrowserScreen.PageOverlay`.

**Problem:** Rendering-context creation failure emits an engine error without a
tab ID. The coordinator immediately returns for such an error and never leaves
`STARTING`. With a restored/nonblank tab, the user can be left looking at a
permanent startup message instead of an honest failure state.

**Fix:** Surface an unscoped startup failure as `EngineStatus.FAILED` without
closing or rewriting logical tabs. Keep request-scoped invalid-address errors
distinct. A later genuine `EngineReady` must still be able to recover.

**Proof to add:** Before readiness, inject an unscoped engine error and assert
FAILED plus preserved session/URLs; inject readiness later and assert recovery.
An unrelated tab-scoped refusal must not fail the whole engine.

**If skipped:** A graphics/driver problem looks like endless loading and gives
the user no useful explanation.

### 4. The page keyboard bridge is not a complete editable-text bridge

**What this is:** `EngineInputConnection` bridges Android keyboards to Servo.
Evidence: `engine/.../EngineSurfaceView.kt:176-234` and
`native/src/engine.rs:745-821`.

**Problem:** Composing text is held only in a Kotlin string, so the page shows
nothing until commit. Deletion is sent to the page without updating that held
composition. `closeConnection` commits any remaining composition to whichever
view is active then. The bridge also ignores cursor placement and surrounding
text. This is a real usability limit for predictive, gesture, accented, and CJK
keyboards; the existing comment acknowledges only part of it.

**Fix:** Carry composition start/update/end and selection through a
field/document-identified engine contract. Do not flush old text into a newly
selected tab or field. Handle Unicode deletion through the proper IME contract.
Do not solve this by substituting another rendering engine.

**Proof to add:** Visible composition, replace/delete composition, emoji,
accented text, CJK input, textarea newlines, switching fields/tabs, navigation
during composition, and keyboard dismissal without an accidental commit.

**If skipped:** Basic typing can feel broken even while synthetic key-event
tests pass. Reproduce on the owner's actual keyboard before daily use.

### 5. Page-clicked external links bypass the address policy

**What this is:** Typed `mailto:`/`tel:` input gets an explicit external-app
decision. Page-initiated navigation follows a different path. Evidence:
`native/src/delegate.rs:165-172`, `AddressPolicy.withScheme`, and
`BrowserCoordinator.onEngineEvent`.

**Problem:** The native delegate allows every navigation request and reports it.
The coordinator ignores `PageNavigationRequested`. Thus clicking a mail or
telephone link cannot reach the external-intent UI used for typed input.
Disallowed schemes also lack an explicit embedder policy at this boundary.
I have not demonstrated a local-file read or a sandbox escape.

**Fix:** Establish a native navigation decision that permits normal HTTP(S),
refuses unsupported schemes, and routes explicit user-activated external links
to a confirmation in Android. Do not let a script launch arbitrary intents.

**Proof to add:** Fixture links for mail, telephone, unsupported schemes,
redirects, popups, and script-initiated requests. An ordinary web link remains
fast and does not wait for an unnecessary Compose round trip.

**If skipped:** A common browser action fails, and address-bar validation is
mistaken for a policy that covers every navigation.

## Should fix

### 6. Recent cannot find retained history older than its newest 500 rows

**What this is:** Local search should search locally retained titles/URLs.
Evidence: `BrowserCoordinator.kt:125-137`,
`core/.../data/BrowserDao.kt:41-58`, and `RecentSheet.kt:88-92`.

**Problem:** SQL applies `LIMIT 500` before Kotlin applies the query. Visit 501
can still be inside retention and in the database but returns "no matches".
The sheet also recreates its Room flow on every keystroke.

**Fix:** Separate a bounded display page from the searchable retained corpus.
Search before the result limit; preserve Unicode case-insensitive behavior
rather than silently replacing it with SQLite's ASCII-only default `lower`.
Keep one subscribed local projection or use a cancellable query path.

**Proof to add:** A unique title older than 500 newer visits is found; all
results remain bounded; duplicate live tabs remain distinct; literal `%`/`_`,
accented titles, blank queries, and expired rows behave correctly.

**If skipped:** "I know I visited this" becomes an unreliable operation.

### 7. Backgrounding the app does not throttle the selected renderer

**What this is:** Hidden tabs are throttled, but Android app visibility is a
separate state. Evidence: `CombrayApplication.kt:49-79`,
`BrowserCoordinator.kt:748-767`, and `native/src/engine.rs:492-508`.

**Problem:** `onStop` persists cookies; surface destruction only unbinds EGL.
Neither throttles the selected view. It remains the shown, unthrottled Servo
view while the app is out of sight. A live/animated site therefore receives no
embedder background-throttling signal. Battery cost has not been measured.

**Fix:** Feed process foreground/background state to the coordinator and use
the existing `EnginePort.setThrottled`. Resuming unthrottles only the selected
view. Selecting/restoring a tab while backgrounded must not unthrottle it.
Keep logical tabs, cookies, and the page surface lifecycle intact.

**Proof to add:** Foreground/background transitions, tab switching and new
views while backgrounded, home, delayed engine readiness, and resume. Later
compare a synthetic animation's CPU/frame activity on a physical device.

**If skipped:** "Gets out of the way" does not include resource use when hidden.

### 8. State-inert engine events still trigger database reads

**What this is:** `onEngineEvent` always ends in `publish()`.
Evidence: `BrowserCoordinator.onEngineEvent` and `BrowserCoordinator.kt:808-835`.

**Problem:** Paint progress, surface echoes, and other diagnostics can cause
session lookup, active-tab lookup, and a full open-tab query despite changing no
browser state. StateFlow equality can suppress recomposition, but not those
database reads. Real stateful events also read every tab just to count them.

**Fix:** Enumerate inert event types explicitly and return before publication.
For remaining events, use a count query or retained logical snapshot if a trace
shows the read cost matters. Do not remove document-identity checks or serialize
database work onto Android's main thread.

**Proof to add:** Inert events cause no publication queries; stateful events
still update chrome and attribution. Inspect a fixture-only trace before
claiming any frame-rate improvement.

**If skipped:** The bridge spends work proving that nothing changed.

### 9. Upload staging has no byte budget or startup cleanup coordination

**What this is:** Selected content URIs are copied into private files for Servo.
Evidence: `app/.../UploadStaging.kt:22-49` and `CombrayApplication.kt:40`.

**Problem:** Copies have no streaming size limit and stay until next process
start. Repeated large picks can consume disk; an unknown-length provider can
keep copying. Startup launches recursive cleanup asynchronously, so an early
new pick can race deletion of the same root.

**Fix:** Finish cleanup before admitting new picks, or separate process-scoped
staging generations so cleanup never touches current files. Add a documented
per-pick/total budget with bounded streaming and rollback, plus an in-progress
indicator and cancellation where safe. Do not trust provider-reported length.

**Proof to add:** Block a provider while startup cleanup/picking interleave,
select duplicate filenames, exceed limits with known/unknown lengths, simulate
disk-full, and ensure failure leaves no partially attached files.

**If skipped:** A browser file upload can break unrelated saves/session writes
by exhausting storage or deleting a new staged file.

### 10. Connection and load-failure UI rely on guesses

**What this is:** The address chip/Page Info derive HTTPS from the URL; failed
visits are recognized by two English page titles. Evidence:
`BottomBar.AddressChip`, `SettingsSheet.PageInfoDialog`, and
`BrowserCoordinator.onTitle`/`SERVO_ERROR_TITLES`.

**Problem:** A title is page-controlled, not trusted transport metadata.
A legitimate article titled "Certificate error" is treated as an engine error.
A renamed/localized engine error page can be recorded/captured as successful.
Page Info can simultaneously describe an HTTPS URL as encrypted while the
engine is displaying a certificate error for that URL.

**Fix:** Introduce document-attributed load/transport/error metadata from Servo.
Until that exists, describe the URL's scheme without implying independently
verified certificate status; do not show contradictory security messaging.

**Proof to add:** A legitimate page with either reserved title, network failure,
certificate failure, successful HTTPS, redirects, reload, and back/forward.

**If skipped:** History, capture eligibility, and connection UI depend on
untrusted presentation text.

### 11. Pagination failures are silent, and repeated taps are unguarded

**What this is:** The Hister and Karakeep scopes fetch additional result pages.
Evidence: `HisterSearchPanel.kt:139-151` and `KarakeepPanel`'s cursor button.

**Problem:** Hister casts the next response to Results and returns for every
error, so a failed page gives no explanation. Neither panel disables Load more
while its request runs. Double taps can issue the same cursor concurrently;
late answers can move the cursor backward or waste requests.

**Fix:** Give pagination an in-flight state tied to query, destination, and
cursor. Disable that cursor's button until completion, retain existing rows on
failure, and display an inline Retry. Continue rejecting old query generations.

**Proof to add:** Two immediate taps send one request; delayed old-query replies
cannot append; an unauthorized/offline next page preserves rows and offers a
visible retry. A service list-setting change must not reset unrelated results.

**If skipped:** A useful integration feels unresponsive on a slow connection.

### 12. The narrow toolbar leaves too little room to identify the site

**What this is:** Five 48dp icon targets share a row with the weighted address
chip. Evidence: `BottomBar.kt:66-86,133-159`.

**Problem:** At a 320dp width, the icons and outer padding leave roughly 72dp
for the chip before its own padding, security icon, and spacer. The visible
host can collapse to a suffix. Start ellipsis preserves the end of a string,
not necessarily the entire registrable domain, despite the comment's claim.
Large fonts and an uncapped numeric tab badge worsen the pressure.

**Fix:** Test an adaptive two-tier bottom layout at constrained widths/font
scales, retaining every routine action at the bottom and Share as a primary
action. Preserve a meaningful site identity, not just `.com`. Cap the visual
badge at `99+` while accessibility announces the real count. Do not shrink
hit targets to fit more chrome into one row.

**Proof to add:** 320/360dp, landscape/cutout, 200% font size, RTL, long IDNs,
several subdomains, and 1/99/1000 tabs. Check rendered text, not just semantics:
a 48dp hit target with an unreadable host is not a complete accessibility pass.

**If skipped:** The most important browser label becomes the least readable.

### 13. The pine-and-paper theme covers too few Material roles

**What this is:** Both color schemes override five roles. Evidence:
`app/.../ui/Theme.kt:15-29` and `docs/design/README.md`.

**Problem:** Other container, outline, secondary, tertiary, and inverse roles
inherit Material defaults. Sheets/dialogs and the page chrome therefore need
not belong to the same pine/ivory palette. System mode is the only theme choice.

**Fix:** Complete semantic light/dark roles using warm paper, pine, muted
mineral greens, and a restrained warm accent. Keep system typography in chrome,
as the design brief requests; use expressive type only in optional reader
presentation. Test every foreground/background pair rather than sampling one
primary button. Offer System/Light/Dark only after the base palette is coherent.

**Proof to add:** More, Recent, Settings, dialogs, error states, disabled
controls, keyboard transitions, and reader in both modes. Verify text and
non-text contrast and real screenshots, not just hex values.

**If skipped:** The app has a memorable icon but an inconsistent native surface.

_Later verification observation, not a measured visual defect:_ lint also flags
the missing monochrome layer in `app/src/main/res/mipmap-anydpi/ic_launcher.xml`.
If themed icons matter, generate a separate monochrome asset; keep the source
artwork unchanged.

### 14. The capture-budget warning can outlive the full queue

**What this is:** Capture sets a process-memory `budgetReached` flag.
Evidence: `HisterIntegration.kt:209-220` and `SettingsSheet.HisterCaptureSettings`.

**Problem:** The flag is cleared only by a later successful capture attempt.
If uploads drain while no new eligible page is captured, Settings can continue
saying capture is paused for capacity. Restart can produce the opposite stale
state because the flag starts false independently of durable queue size.

**Fix:** Derive budget presentation from observed durable bytes and the actual
admission decision. Make room for replacement of an unsent capture when checking
its projected byte cost; do not count replaced content twice.

**Proof to add:** Reach budget, drain/discard/expire content, and observe the
warning clear without opening another page. Restart with a full queue; replace
an old body with an equal-sized newer version near capacity.

**If skipped:** A healthy queue can look broken, and fresher content can be
rejected even when it would replace content already counted.

### 15. Long-lived state and request timing need explicit bounds

**What this is:** The coordinator uses an unlimited command channel and per-view
document maps; Hister retains forget tombstones in memory. Evidence:
`BrowserCoordinator.work`/`LiveView`, `HisterIntegration.forgotten`, and
`OutboxDispatcher.LEASE_MS`/`HttpTransport`.

**Problem:** Six live views do not bound the number of documents visited in one
long-lived tab. A script can generate title/history events faster than durable
work consumes them. Also, per-connect/per-read timeouts are not an overall HTTP
deadline: a slow trickle or DNS lookup can outlast a fixed job lease. Increasing
the lease alone does not prove a live request cannot expire.

**Fix:** First record a fixture-only event/heap trace. Bound or coalesce only
replaceable events, never commits, closes, or durable user actions. Prune
bookkeeping when its document can no longer matter. Treat lease expiry as
ambiguous completion; use a real deadline/renewal and fenced transitions rather
than promising exactly-once delivery.

**Proof to add:** A long single-tab session, title/pushState bursts with
navigation/close in the middle, slow-trickle transport, cancellation, lease
expiry/takeover, and a stale late result. Keep logs free of page bodies/URLs.

**If skipped:** A small renderer cache can still accumulate process-lifetime
work and memory, and an apparently generous timeout can give false confidence.

### 16. Essential page capabilities need an explicit acceptance matrix

**What this is:** Existing device checks emphasize synthetic navigation and
integration flows. The page itself is a SurfaceView.

**Problem:** Chrome accessibility does not establish page accessibility. There
is no native Android accessibility-tree bridge shown here. Pinch zoom, page
text selection/copy, downloads, find-in-page, password-manager/autofill behavior,
and essential-site compatibility do not have convincing acceptance evidence.
Do not infer that Servo cannot support a feature merely because it is untested.
Stop remains explicitly blocked by the pinned Servo API.

**Fix:** Pick the owner's essential-site matrix and assess each capability
against the existing Servo API. Start with find-in-page and reliable text
selection if those sites need them. Route downloads through a deliberate,
user-visible Android flow only after engine response/body ownership is known.
Keep unsupported capabilities honest; do not add a WebView fallback.

**Proof to add:** Real keyboard, TalkBack reading/focus, zoom and selection,
large/failed downloads, login and cookie persistence, and Kagi navigation on an
arm64 phone. Live-service writes require a separate explicit test action.

**If skipped:** Fixture success can be mistaken for a usable daily browser.

### 17. Release CI cannot yet produce a usable signed engine APK

**What this is:** Kotlin CI and native browser delivery are different gates.
Evidence: `.github/workflows/ci.yml`, `release.yml`, `scripts/verify-apk.py`, and
`docs/BUILDING.md`.

**Problem:** CI assembles Kotlin without building/staging Servo. The release
artifact gate correctly rejects missing engine libraries; it is not evidence
that a usable release already exists. Personal signing and native CI remain
pending, as do stronger Maven dependency verification guarantees.

**Fix:** Establish a reproducible pinned arm64 native build or independently
verified immutable native artifact, include its patches/notices, provision
signing through the owner's approved secret channel, and verify the final APK.
Generate dependency checksums on the supported host configurations. Do not
weaken the artifact gate or commit a signing key to make a release green.

**Proof to add:** Clean build, ELF/ABI and 16KiB page compatibility as applicable,
signing verification, install/upgrade without session loss, and actual browsing
on arm64. Exercise a release tag only after those prerequisites are satisfied.

**If skipped:** A green Kotlin build remains very different from shipping this
browser. No release should be advertised as ready on that basis.

### 18. Unsupported page permissions disappear without a useful explanation

**What this is:** Camera/microphone/location requests are denied by default.
Evidence: `native/src/delegate.rs:189-195`; DefaultHandled events do not become
user-facing capability feedback.

**Problem:** Denial is the safe policy, but a site's feature can fail with no
Combray explanation. The user cannot tell unsupported embedding from a broken
site or a setting they can change.

**Fix:** Keep denial as the default. Add a document-attributed, rate-limited
notice explaining the unsupported capability and a small Page Info capability
section. Only add actual per-origin permission UI after engine support and
Android permission lifecycles are designed.

**Proof to add:** One notice for repeated requests in a document, no stale-tab
notice, accurate origin, no automatic grant, and correct dismissal/navigation.

**If skipped:** Safety is preserved, but normal users experience unexplained
breakage rather than a deliberate limitation.

## Nice to have

### 19. Reader needs a visible return path and a personal reading rhythm

**What this is:** Reader is a native plain-text overlay. Evidence:
`ReaderView.kt:64-112` and the More menu's Leave reader action.

**Problem:** A ready article has no visible close action in the reading surface;
Back or another menu trip is required. Body text has a fixed 18sp/28sp style.
Long reading sessions have no reader-specific text-size/line-width preference
or retained article position across recreation.

**Fix:** Add a quiet, accessible bottom-reachable return-to-page action; then
small text-size/measure controls with persisted preferences. Preserve the
original page behind the overlay and key scroll state by tab/document. A warm
paper reader option fits Combray; a separate visual theme framework does not.

**Proof to add:** Small screens, 200% font scale, RTL, long code/list blocks,
rotation, tab switches, article failure/truncation, and return without reload.

**If skipped:** The cleanest reading surface still takes an extra step to leave
and cannot match the owner's reading preferences.

### 20. Make copying and returning genuinely one-handed

**What this is:** The address chip edits; Share sends a URL; Recent finds tabs.
Evidence: `BottomBar.AddressChip`, `BrowserScreen.share`, and `RecentSheet`.

**Problem:** Copying the current address needs several interactions, sharing
omits its title, and returning to the previous tab requires opening a list.
These are opportunities, not correctness defects.

**Fix:** First ship long-press address to copy the exact current tab URL, with an
accessible Copy address action and brief confirmation; tap must still edit.
Then, as separate changes, include a deliberate title with shares and offer
long-press Recent to switch to the last distinct tab. No clipboard reads, no
automatic network requests, and no gesture that steals page scrolling.

**Proof to add:** Long-press copies once without opening the editor; blank tabs
have no copy action; a normal tap still edits; TalkBack discovers the action.
Previous-tab switching must use tab IDs, never equal URLs.

**If skipped:** The interface remains simple, but the most repeated tiny tasks
are not as convenient as they could be.

## Small ideas with a Combray personality

- **A madeleine, not a dashboard:** One optional Resume row on Home: the last
  distinct tab's title, domain, and relative time. Read the existing Recent
  projection; do not create another history store or populate a recommendation
  feed. Keep search as the primary action.
- **Quiet receipts:** A short, non-blocking "Saved" or "3 offline saves sent"
  receipt only after durable/remote acknowledgement. No confetti, endless
  spinner, or claim that saved means crawled/searchable. Keep existing error
  and retry paths.
- **A capture compass:** Page Info says "Not captured: excluded site",
  "Paused", or "Queued for Hister" for this document. Derive this locally from
  the real policy/outbox; never make a background search merely to fill a badge.
- **Trails, not tab soup:** An optional Recent grouping by original logical tab
  shows how one page led to another. It is a projection of Visit identities;
  duplicate live tabs must stay independently selectable.
- **Paper at night:** Reader-only warmth, line measure, and a restrained serif
  heading can echo the icon's folded paper. Keep chrome system-native and do
  not recolor arbitrary websites or download a font to render the toolbar.
- **No motion tax:** If adding a reveal or close transition, animate a small
  chrome surface only, respect reduced-motion/animator settings, and do not
  capture/rasterize Servo merely to animate a tab thumbnail.

## Implementation selection and stop conditions

The first independent slices I would implement are #1 (encoded credential-name
privacy guard), #3 (honest startup failure), #7 (background renderer throttling),
and the first part of #20 (address-copy shortcut). They fix bounded problems or
add a high-frequency convenience without changing renderer, schema, service
contracts, or the unselected Recent-home versus Recent-sheet product choice.
Each should start from current main and have its own branch/PR where practical.

The concurrency work in #2 and the editable-text contract in #4 deserve their
own careful sessions. They are more important than cosmetic polish, but should
not be rushed into a mixed UI PR. Palette, inert-event, reader-close, and budget
work already described in the existing roadmap must be reconciled against
main before treating it as unimplemented; no other agent's PR needs inspection.

For each selected fix, add the smallest failing regression first, implement at
the responsible boundary, then run the affected tests. Run documentation checks
and diff whitespace checks for every branch, and the repository Kotlin/lint/APK
gates once as a final gate rather than rebuilding unchanged work repeatedly.
Native/device claims require native/device evidence; a fake engine proves
coordinator decisions, not measured Servo power consumption.

Leave code PRs open for the owner. Judge review findings by demonstrated impact;
track the user's two-round steady-state rule or an explicit reviewer timeout.
Consolidate the remaining work into ANALYSIS.md without deleting unresolved
items merely because someone opened a PR. Keep completed work out of the
future implementation queue only after it is actually present on main.

## Implementation Results (22 October 2026 Snapshot)

Initial publication snapshot; these hashes are the tips at publication, not a
promise they stay latest.

| PR / branch | Status and frozen regression evidence |
| --- | --- |
| [#95](https://github.com/L-K-M/Combray/pull/95) `fusion/encoded-credential-keys` | Fusion 1; initial published tip `4032736` (original `6e57bd8`). Failing-first, then green percent-decode policy, Karakeep-save and Hister-capture regressions. |
| `fusion/startup-error-state` / `54d752f` | Fusion 3; 41 coordinator tests passed on the original branch. Superseded: an equivalent fix is on main via #65 (commits `79c42b9`, `3a5f759`, `abdd972`); no duplicate PR opened. |
| [#96](https://github.com/L-K-M/Combray/pull/96) `fusion/background-throttling` | Fusion 7; initial published tip `d22f1c3` (original `fbd3dfa`). 43 coordinator tests passed on the original branch; updated coordinator and app checks passed 22 October. |
| [#97](https://github.com/L-K-M/Combray/pull/97) `fusion/copy-address` | First part of Fusion 20; initial published tip `3cd454f` (original `f908d84`). 3 new plus 2 existing emulator chrome tests passed historically; updated app/assembly checks green 22 October. |

The 21 October pre-publication combined Kotlin/lint/assemble gate passed
offline. Network approval arrived 22 October; the branches were merged with
locally checked main `752d7da`, pushed, and opened as PRs #95/#96/#97, each
green on the prescribed narrow offline checks; CI/review status is tracked on
the PRs, no steady-state claim is made. Findings describe the audited `4105c28`
baseline, not a re-audit of current main.
Native engine, physical device, real gesture/clipboard, live services and
battery/FPS remain unvalidated.

## Verdict and limits

Verdict: keep Servo, bottom controls, the single Recent mental model, and the
small integration boundary. First close privacy/typing/failure gaps, then prove
background behavior and essential-site usability, then polish the already
recognizable pine-and-paper direction. A replacement engine or a broad new
architecture is not the improvement this project needs.

Not checked: owner's phone/keyboard, real accounts or browsing history, live
Karakeep/Hister/Kagi writes, real-world frame timing/battery use, a fresh native
build, or a signed arm64 release. No FPS, startup-time, or battery-saving number
is claimed. Existing fixture evidence is useful but does not close those gaps.

