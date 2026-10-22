# Combray — analysis and future work

Shovel-ready list for the next session. Consolidated 21 October 2026 from the
earlier roadmap (`SWE2.md`), `Grok.md` (discarded after its merge),
`GeminiFlash.md`, the [Sol review](https://github.com/L-K-M/Combray/pull/28),
the MiMo review (`MiMo.md`, folded in below), and the Muse review
(`MuseSpark.md`, folded in below and discarded). Sol's and Muse's source
citations refer to `e5cf8ed`/`4105c28`; later handoffs record re-verification.
This version also incorporates the independent Fusion review in `fusion.md`
(source baseline `4105c28`; its three code slices were published as PRs
#95-#97 on 22 October 2026 and remain open for the owner). Its source findings
are not phone benchmarks. Earlier contributors' PR descriptions are retained
as provenance, not re-verified PR status; no other contributor's PR was
inspected during this pass. This pass also folds in the Inkling review
(`inkling.md`, kept as the review record); its five code slices were published
as PRs #98-#102 on 22 October 2026 and remain open for the owner. The GLM
review (`glm.md`, kept as the review record) is folded in below; its seven
code slices were published as PRs #51 and #105-#110 on 21/22 October 2026
and remain open for the owner. Read
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
not inspected during the Sol pass or the Muse pass (titles only).

Earlier session:

- #24 skip `publish()` on state-inert engine events. New `EngineEvent` subtypes
  must be placed or the when fails to compile.
- #25 Recent filters in memory. `RecentItem.matches` is on the model.
- #26 full Material 3 roles for both modes. Light `outline` is about 3.5:1.
- #27 floating reader close button.
- #29 outbox lease 60 s to 150 s. Worst-case dispatch was estimated at about
  62 s plus DNS; that figure is an earlier estimate, not a proven worst-case
  bound. Per-read/connect timeouts are not an overall request deadline: a slow
  trickle or DNS can outlast a fixed lease. Test a slow live request,
  expiry/takeover and its stale late completion; use deadlines/renewal and
  fenced transitions, never an exactly-once promise (Fusion 15).
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

MiMo fixes, also left open for the owner:

- #36 `mimo/recent-visit-identity` — a tab without a renderer keeps its page in
  Recent: navigating an evicted/restored tab, or one an address refusal sends
  home, clears `currentVisitId` at both detach sites (one
  `TabEntity.withDetachedCurrentPage` helper); the commit path re-attaches it.
  Tests pin both halves and the recovery URL. Complements #41 and Sol #32,
  which cover the forget path.
- #37 `mimo/outbox-resilience` — one broken job cycle (storage error, recorder
  bug) no longer strands the queue or skips its wakeup (guarded steps,
  `NonCancellable` wakeup booking); a job whose worker dies stops at the attempt
  limit instead of being reclaimed forever; re-saving a failed job sends the
  fresh payload; `resume()` restarts the attempt budget; and `claim` marks
  captures past the age limit EXPIRED at the send point. Overlaps Sol #31's
  abandoned-claim expiry: pick one of the two, do not merge both blindly.
- #42 `mimo/quick-links` — the launcher monogram and host quick links on the
  new-tab page (`BrowserCoordinator.topSites()` ranks hosts by visits in the
  retention window; pages open tabs show count). Offers the home-surface idea's
  quick-links half.
- #52 `mimo/appearance-and-reader-size` — Settings › Display: appearance
  (follow the system / light / dark) and reader text size (small / default /
  large), persisted in `BrowserSettings` with unknown-name fallback. Offers the
  theme-override and reader-size items below.
- #53 `mimo/swipe-close-tabs` — swipe a tab row in Recent to close it, with the
  same Undo as the close button. History rows keep their explicit menu:
  removing a visit has no undo. Offers the swipe part of the close-all item.

Inkling fixes, also left open for the owner. Each is an independent
implementation of a topic already recorded above, so merge one of the pair, not
both. They were written without reading the concurrent branches and do not
change them:

- #98 `inkling/theme-aesthetics` — Settings › Theme: follow the system (Material
  You dynamic color on Android 12+), light, dark, or a pitch-black OLED dark
  surface, persisted in `BrowserSettings` and driving `CombrayTheme` via a
  `ThemeMode` enum. `BrowserSettingsTest` pins persistence. Covers the
  theme-override and OLED-pitch-black items in the chrome section (and #52's
  appearance half).
- #99 `inkling/page-info-fix` — the page-info sheet dismisses from a keyed
  effect only when there is no URL, and is gated at the source so no empty sheet
  flashes. Same topic as #48 and correctness item 1.
- #100 `inkling/karakeep-pagination-key` — the Karakeep panel keys its load on
  the service fingerprint and config `generation`, not the mutable entity, so a
  transient update no longer resets pagination. Same topic as #40.
- #101 `inkling/reader-close-button-v2` — a floating reader close button with a
  rounded surface scrim, held at the physical top-right for RTL articles and
  inset below the status bar. Same topic as #27.
- #102 `inkling/share-title` — sharing a page sends the title then the address
  and sets `EXTRA_SUBJECT`. Same topic as the chrome section's share-title item
  (and #51).

GLM fixes, also left open for the owner. Where a topic already has an open
PR above, the pair are independent implementations: merge one, not both.

- #51 `glm/share-title` — the bottom bar's Share sends the page's title with
  the address: the chooser's `EXTRA_SUBJECT` and the shared text's first line,
  with title whitespace collapsed so a page title cannot add lines. A
  long-press link share keeps the bare address. Same topic as #102.
- #105 `glm/page-info-dismiss` — Page Info keys on the address captured when
  it was opened (cleared on every dismissal path), removing the `?: dismiss()`
  state write during composition. Same topic as #48 and #99.
- #106 `glm/save-guard-order` — an unconfigured Save answers "set Karakeep
  up" even when the address looks credential-bearing, instead of claiming the
  address was withheld for safety. Regression-tested.
- #107 `glm/retry-after-overflow` — `Retry-After` seconds are clamped before
  the millisecond multiplication, so `Long.MAX_VALUE` no longer wraps negative
  into an immediate retry (correctness item 14). Tests cover the wrap, cap,
  negative, HTTP-date and beyond-Long inputs.
- #108 `glm/tab-badge-cap` — the Recent tab-count badge caps its visible label
  at a `%1$d+` string derived from the limit; TalkBack keeps the exact count.
  Covers the chrome badge item below.
- #109 `glm/quick-tab-switch` — long-press the Recent button to focus the
  runner-up tab (`focusPreviousTab()`), as an accessible labeled long-click
  beside the sheet's rows. Offers the ideas-section quick-switch item.
- #110 `glm/retention-graph` — one `Retention` instance flows from `AppGraph`
  to maintenance and the Settings label (correctness item 4's plumbing half;
  no new configuration).

## Completed On Main

| PR / branch | Scope and verification |
| --- | --- |
| #65 `muse/engine-failed-status` | **Merged 21 October.** An engine-level error (native `error()`, no tab) received while `STARTING` moves the status to `FAILED` instead of showing "starting" forever; logs ERROR only for the fatal startup phase; classification keys on the missing tab alone, and `EngineEvent.EngineError` documents that contract. Four coordinator tests incl. a request-carrying tab-less error and post-ready non-failure. Deferred from its round-3 thread: include `event.request` in the engine-level log line. Fusion's equivalent startup branch `54d752f` was superseded by this; no duplicate PR was opened. |

## Open pull requests (Muse)

Muse session (21 October 2026). Six remain open for the owner's review and
merging:

| PR / branch | Scope and verification |
| --- | --- |
| #55 `muse/theme-roles` | Full Material3 role ladder in pine/ivory for both modes: sheets, checkboxes, radios, switches and snackbars stop falling back to baseline purple. `ThemeContrastTest` pins WCAG 4.5:1 on every text pair the UI can show, 3:1 outline contrast against all six surface steps in both schemes, and ladder ordering incl. `surfaceDim`/`surfaceBright`. Unit + lint green; no device screenshot yet. Overlaps #26 and #62 by title; owner picks one. |
| #60 `muse/key-whitelist` | `forwardKey` sends only printable input and editing/navigation keys (performance item 16). `KeyForwardingTest` rejects volume/menu/search/launcher-Home/back modifiers and accepts the named page keys; bare Ctrl/Alt/Meta remain undocumented-as-known-limit on the KDoc. Overlaps #56 by title. |
| #66 `muse/inert-event-publish` | `changesPublishedState` is an exhaustive `when` over all twenty-five `EngineEvent` kinds (a new kind is a compile error), gating `publish()`'s three Room reads off eleven inert events; `openTabCount()` is `COUNT(*)`. Both test lists mirror the classifier. Overlaps #24 by title. |
| #67 `muse/recent-single-flow` | `recent()` subscribes once per sheet open and the sheet filters a remembered list (overlaps #25); `RecentItem.matches` moved onto the model; `RecentItemMatchTest` covers the matcher. The retention `since` is now fixed for the sheet's life — recorded under correctness item 17. |
| #79 `muse/address-suggestions` | Local completion under the address editor while the text differs from what it opened with: newest first, one row per address, five max, local only, taps run the address policy. `AddressSuggestionsTest` (4 tests). Variant note: this **opens** the page like a Recent row; the chrome section's older proposal fills the field instead — the owner should pick one behavior. Overlaps #63 by title. |
| #85 `muse/keyboard-ownership` | While any sheet or page dialog is open, page `KeyboardRequest` show/hide is dropped instead of applied, so autofocus timers cannot yank the IME out of the address editor or a sign-in dialog (the UI half of correctness item 23). No existing IME device coverage; an emulator flow is the honest follow-up. Overlaps #90 by title (native activation gating is complementary). |

All six open ones: `testDebugUnitTest`, `lintDebug`, `scripts/check_docs.py` and
`git diff --check` green locally; CI green on each. Titles of other open PRs at
21 October, not inspected (metadata only, to keep future passes from
duplicating): #51 share title, #54 page dark mode, #57 back-to-opener, #58 more
close tab, #59 link menu, #61 share-to-Combray, #68 Hister paging, #69
home-server HTTP, #70 data: host display, #71 recent order, #72 history fixes,
#73 saves address change, #74 outbox patience, #75 CI checks, #76 icons and
haptics, #77 reader typography, #78 restored splash, #80 external app links,
#81 capture budget, #82 screen geometry, #83 media cancel, #84 popup blocking,
#86 find in page, #87 page zoom, #88 tab throttling, #89 sign-in userinfo, #90
keyboard activation.

## Fusion Changes

Three code PRs were published on 22 October 2026 and left open for the owner;
none is implemented on main. Live CI/GLM review status is tracked on the PRs,
not claimed here. Do not start a second implementation of these slices while
the owner decides.

| PR / branch | Implemented scope and evidence |
| --- | --- |
| [#95](https://github.com/L-K-M/Combray/pull/95) `fusion/encoded-credential-keys` | Fusion 1: percent-decode query/fragment names once before the shared sensitive-URL guard. Values and double encoding are not rewritten. Failing-first pure, Karakeep-save and Hister-capture regressions pass. Independent alternative to the already-recorded #39; choose one rather than merge both blindly. |
| [#96](https://github.com/L-K-M/Combray/pull/96) `fusion/background-throttling` | Fusion 7: process visibility reaches the serialized coordinator. New/restored views stay throttled while backgrounded; resume unthrottles only the selected view. Emitted hints, not measured battery savings. The roadmap also lists #88 by title, not inspected. |
| [#97](https://github.com/L-K-M/Combray/pull/97) `fusion/copy-address` | First part of Fusion 20: long-press address copies the exact current URL; tap still edits; a blank tab has no copy action. Three failing-first/new semantics regressions pass; Android 13+ uses its own clipboard confirmation. Title-aware sharing and previous-tab switching remain future work. |

The 21 October pre-publication combined source gate also passed Kotlin unit tests, lint,
debug/release assembly and five targeted emulator chrome checks, including
large-font/48dp/non-overlap regressions. The initial integration-only test
insertion conflict needed its missing closing brace and annotation restored;
no independent feature logic changed. Builds deliberately omitted Servo.
See Verification below and `fusion.md` for limits; an open PR is
not implemented behavior on main.

## Correctness still open

History/capture atomicity and abandoned-claim expiry are offered in #32 and #31;
do not implement them again. Connection labels, page-info dismissal, and
Karakeep-panel identity are offered in #43, #48/#105 and #40. Retry-After
clamping is offered in #107 and the Retention plumbing in #110. Validate the
listed source candidates before editing; none is a new device reproduction.

**First remaining privacy slice: capture pause and durable admission (Fusion 2).**
The due-job query checks `captureEnabled`; `HisterIntegration.dispatch` does not.
Pause a claimed-but-unsent job while the dispatcher switches to IO, and its
fresh disabled-service snapshot can still be sent. Recheck pause before
transport, retain paused work without spending a network-attempt budget, and
prove resume neither loses the job nor reports a false authentication error.
Also gate a real Settings exclusion/configuration change during suspended
`captured` admission and reject stale policy before persistence. Normal Forget
and capture-result handling use the same coordinator consumer; a direct
concurrent sink test is not a production Forget-race reproduction. This work
is distinct from the recorded local-deletion/cancellation transaction proposal.
Files: `HisterIntegration.kt`, `OutboxDispatcher.kt`, `Outbox.kt`,
`IntegrationDao.kt`; use controlled barriers at claim/dispatch/admission.

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
   speculative configuration plumbing. (The instance-sharing plumbing is
   offered in #110; exposing a setting remains open.)
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
    Staging lifetime/resource bounds (Fusion 9) are separate confirmed gaps:
    `UploadStaging.stage` streams without a byte budget and retains copies until
    process restart; `CombrayApplication` starts root cleanup asynchronously, so an
    early new pick can race that deletion. Finish cleanup before admission or
    isolate process generations. Choose documented finite per-pick/total limits
    with the owner, enforce them while streaming unknown-length providers, and
    roll back only the failed pick. Test a blocked provider racing cleanup, large
    repeated picks, duplicate names, cancellation and disk-full without deleting
    an active attachment.
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
    FAILED rows also keep their payloads inside the seven-day budget sum
    (`queuedCaptureBytes`), so one permanently failed large capture blocks new
    captures until it ages out.
    When admitting replacement content, account for the body being atomically
    superseded rather than counting old and new bodies as permanent extra usage.
    Test equal/smaller replacements near capacity and warning recovery after
    expiry, drain and discard without another capture (Fusion 14).
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
    (Offered in #107 with those tests; keep this entry until it lands.)
15. **External scheme handling for standard mobile protocols**:
    `AddressPolicy.EXTERNAL_SCHEMES` currently only allows `mailto:` and `tel:`.
    Adding standard mobile schemes (`sms:`, `geo:`, `market:`) allows opening them
    via user confirmation in external native apps instead of triggering `UNSUPPORTED_SCHEME`.
    Keep these optional. Test absent handlers and deliberate user activation;
    unsafe `intent:`, `javascript:`, file/content schemes stay rejected.
    The current end-to-end gap (Fusion 5) is not just missing scheme names:
    `native/src/delegate.rs::request_navigation` allows page requests and the
    coordinator ignores `PageNavigationRequested`, so clicked mail/tel links never
    reach the typed-input external decision. Carry view/document, frame level and
    user activation into an explicit decision: normal HTTP(S) stays fast;
    unsupported schemes are refused; deliberate external links get confirmation.
    Never launch an intent from arbitrary script or an iframe. Test mail/tel,
    redirects, popups, unsupported schemes and absent handlers. No local-file read
    or sandbox escape has been demonstrated.
16. **Page controls can be attributed to the wrong page.** `view.controls` is
    cleared on crash but not when a document commits, so a dialog from the
    previous document is presented under the new page's host when the engine
    does not send `ControlHidden`. Clear pending controls when
    `view.currentDocument` changes, answering them Dismiss. File:
    `BrowserCoordinator.kt`.
17. **Coordinator lifecycle edges.** `start()` is not idempotent (a second call
    starts a second work-queue consumer and breaks the single-consumer ordering
    invariant); `recent()` computes its retention `since` once per flow, so a
    long-lived collection's window never slides; `onEngineError` ignores
    refusals without a request, leaving the coordinator believing a dead view is
    on screen; and `pendingCaptures`/`pendingArticles` are removed only when a
    result arrives, so a crashed view leaks them for the process lifetime (fold
    the last one into item 13's pruning when measured). Each is small and
    unit-testable like the PR #36 cases. File: `BrowserCoordinator.kt`. The narrow
    unscoped-STARTING failure path is already fixed on main by #65 (see
    Completed On Main); it does not claim all runtime errors and is not a task.
18. **Destination fingerprints do not canonicalize the host.** `https://h`,
    `https://h:443` and `https://h.` count as different servers, forcing
    credential re-entry and re-binding queued work to a "new" destination. Strip
    default ports and trailing dots in `ServiceUrls.fingerprint` /
    `normalizeRoot`. Distinct from item 10's account-identity question.
19. **`ServiceRoutes`' one-request-per-attempt contract is a comment only.** A
    lambda that sends twice can re-send on failover to the alternate address.
    Give `AttemptTransport` a single-use guard so misuse cannot double-send.
    File: `ServiceRoutes.kt`.
20. **`SecretStore` can race its first Keystore key creation.** Two concurrent
    first-time configures (Karakeep and Hister hold different mutexes) can race
    `generateKey`: one write fails or replaces the key and orphans the other's
    ciphertext. Serialize key creation. File: `SecretStore.kt`.
21. **Identical content after a failed send is never re-queued.** When
    `HisterIntegration.captured` sees the dedupe hash matches an earlier
    capture, it returns early without checking whether that job FAILED or left
    the queue: the page is silently never captured again. Skip the early return
    when the matching job is not on its way to the server. File:
    `HisterIntegration.kt`.
22. **`SensitiveUrls` misses the commonest credential parameters.** `code`
    (OAuth authorization codes), `signature`, `sig`, `key`, `ticket`,
    `authcode` are not in `CREDENTIAL_PARAMETERS`, so an OAuth callback URL can
    be saved to Karakeep or captured to Hister. Extend the set (names, not #39's
    encodings). File: `SensitiveUrls.kt`.
23. **Any page can summon the keyboard without a user gesture.**
    `native/src/delegate.rs` drops `InputMethodControl.allow_virtual_keyboard`
    (and text/insertion point): a page-side `focus()` shows the keyboard at any
    time. Forward the flag and let the app suppress programmatic keyboards.
    The app-side half (dropping page show/hide while an overlay owns the IME)
    is offered in #85; the native flag still needs forwarding, and
    `ime_owner` is a single slot shared across views (performance item 4).
24. **Settings queue rows decode whole payloads during composition.**
    `Integrations.jobUrl` (`Integrations.kt:129-132`) JSON-decodes the full
    capture payload (up to 256 KiB of page text) to read one URL, and
    `SettingsSheet.kt:490` calls it per row inside composition;
    `observeOpenJobs` re-emits on every outbox write. The same decode runs
    inside `HisterIntegration.jobHost`'s transaction when forgetting a site.
    Persist a `url`/`host` column on the job at enqueue (schema, migration,
    backfill) and never parse payloads in composition. Tests: migration with a
    populated queue, a Settings screen over a full Hister queue stays responsive.
25. **Switching Recent's scope auto-sends the local filter to a server.**
    `RecentSheet.kt:132-135` seeds the Hister/Karakeep panels with
    `initialQuery = query`, and their debounced effect fires the seeded text
    with no further user action. The panels promise "only text typed here is
    sent"; the mental model does not include "my local filter is now a server
    query". Prefill the field but require an explicit Search for the first
    request; debounced typing only after the user types in the panel. Test that
    scope entry with a non-blank local filter sends nothing until submitted.
26. **The work-queue consumer survives `Exception` but not `Error`.**
    `BrowserCoordinator.kt:101-111` catches `Exception`; an `OOM` or
    `StackOverflowError` (article JSON is parsed on that same queue) cancels
    the consumer and the browser silently stops processing commands forever,
    while `CancellationException` on shutdown is logged as a failure and posts
    a spurious notice. Catch `Throwable`, rethrow cancellation, restart or
    degrade visibly. Test with a throwing job that the next command still runs.
27. **`lastFocusedAt` uses `now - 1`, collapsing ties and inverting recency.**
    Tabs losing focus in the same millisecond get identical values
    (`BrowserCoordinator.kt:669-676`), and a tab focused at exactly `now` is
    rewritten to a smaller value; `openTabs()` orders by that column, so Recent
    order and `closeLogically`'s successor choice become rowid-dependent when
    links open fast. Use a monotonic focus sequence or `max(old, now - 1)` with
    a tiebreaker. Test two same-millisecond focus changes.
28. **`onDone` runs inside the `finish` transaction.** `Integrations.kt:329-333`
    decodes the payload inside `Outbox.finish`'s `withTransaction`; a decode
    throw rolls the finish back, so the job stays RUNNING, its lease expires,
    and it is re-claimed and re-sent forever while success is never recorded.
    Decode defensively or run `onDone` after the commit. Overlaps #37's guarded
    steps — check what it already covers before implementing.
29. **Retention is wall-clock with no forward-jump guard.** `maintain()` deletes
    everything older than seven days at every start; a device clock set far
    forward (manual date, bad NTP) wipes all local history and closes unused
    tabs in one go. Clamp `now` against the newest stored timestamp, or record
    the assumption. Test with a clock an year ahead of the newest row.
30. **Page-opened windows become unbounded tabs.** `native/src/engine.rs:607-631`
    adopts `window.open` from the active view with no user-activation check
    (Servo 0.6 does not report activation) and each becomes a full logical tab;
    `releaseViewsBeyond` evicts renderers, not tabs. Cap adoptations per opener
    per time window, or require the coordinator's last real input time. #84
    targets the same surface from the native side — check it first.
31. **`BrowserSettings.apply()` can lose a just-toggled setting.** Process death
    right after the tap reverts it silently (`BrowserSettings.kt:18`). Use
    `commit()` for this tiny write, or document the loss.
32. **`onCheckIsTextEditor()` is always true while the engine runs.**
    `EngineSurfaceView.kt:134`: TalkBack announces the whole page area as an
    editable field, and the IME can re-show after a window refocus with no page
    field. Return whether the engine reports an active field (last ImeShown
    without ImeHidden).
33. **`WorkManagerScheduler` schedules with `ExistingWorkPolicy.REPLACE`.** A
    wakeup booked while the unique worker is between its final `drain()` and
    return can request cancellation of a worker that is already finishing.
    Benign today (the dispatcher mutex serializes drains in-process); worth a
    one-line rationale or `APPEND` semantics before any multi-process dispatch.
    File: `OutboxWorker.kt`.

## Performance and engine

Do not claim a device reproduction for these. They are from the bridge and
`docs/ENGINE_SPIKE.md`.

1. **Surface teardown can wait five seconds on the UI thread, and a failed
   unbind can be reported as success.** `EngineSurfaceView.surfaceDestroyed`
   calls `send_and_wait` with `ACK_TIMEOUT` of 5 seconds. Acknowledge release
   only after detach. Do not bind the next window until that is true. A timeout
   is a failure, not a release. Worse, `engine.rs` `surface_created` overwrites
   `self.surface` even when `surface_destroyed` failed to unbind: a still-bound
   `NativeWindow` is dropped under EGL (the `mem::forget` guard exists for a
   reason). Keep or forget the old window on failed unbind. Files:
   `EngineSurfaceView.kt`, `native/src/engine.rs`, `native/src/jni_api.rs`.
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
   `maxElemsToParse`. Refuse an oversized tree before the clone. Its DOM walks
   (`walk`, `blocksIn`, `blockOf`) also recurse by nesting depth: a pathological
   page throws `RangeError` and the whole capture fails as `script_error`. Use an
   explicit stack or a depth cap. The script deadline does not interrupt a
   running script. Prefer trusted engine extraction
   or a genuinely interruptible path; page-controlled getters are not trusted
   execution bounds. Measure ordinary and hostile capture/Readability separately.
4. **Keyboard text goes to whichever tab is active when the command runs.**
   `ImeCommit` carries only the string. `ime_commit` uses `active_webview()`.
   Carry the view generation and drop the commit if that view is no longer the
   one with the field. Related IME gaps in `EngineSurfaceView.kt`: hiding the
   keyboard commits the half-typed composition as final text (clear it instead);
   `showKeyboard` skips `restartInput` when the field kind is unchanged and can
   reuse a dead `InputConnection`; `ACTION_MULTIPLE` is mapped to `KEY_RELEASE`,
   emitting bogus releases; `event.metaState` is never forwarded, so hardware-
   keyboard modifiers type literal characters; `ImeShown.multiline` is parsed but
   unused, so a `<textarea>` gets single-line input; and `deleteSurroundingText`
   emits two JNI commands per character with no cap.
   Fusion 4 also confirms that `setComposingText` only buffers a string: no
   visible composing preview reaches the page, and deletion does not update that
   buffer. Surrounding-text/caps (`getTextBeforeCursor`, `getCursorCapsMode`) and
   selection synchronization are absent. Exercise AOSP/Gboard, CJK, correction,
   emoji/surrogates, large ranges, field/tab changes, navigation and dismissal.
   Use bounded view/field-attributed edits or decline unsupported requests; do not
   silently clip an edit or flush an old composition into the next active field.
5. **`publish()` Room work.** Reviewed main still publishes ignored events; #24
   offers the inert-event fix. Paint reports are already limited to one per
   second, not 60 per second. After that lands, measure stateful title/load bursts
   before adding a snapshot/dirty flag. Count queries and verify real navigation
   and title events still publish.
6. **Recent day-grouping allocates a `Calendar` per row.** Reuse one instance, or
   compute the day boundaries once if profiling warrants it. The list also
   re-sorts on every emission of any of its three flows; SQL ordering would
   remove the per-emission sort. Search truncation
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
    changing suspension, frame scheduling or the live-view budget. The missing
    visibility hint is offered by PR #96 `fusion/background-throttling`; remaining work
    is real-GPU validation, not another implementation of that hint. No measured
    CPU/FPS/battery numbers.
14. **History index candidate.** For `BrowserDao.observeHistory`, run EXPLAIN QUERY
    PLAN and timing on thousands of good/failed visits. Add a composite
    `visits(failed, lastSeenAt)` index in `BrowserEntities.kt` only if it helps
    beyond existing indexes. A Room migration must preserve tabs, visits and jobs.
15. **Engine-loop resilience and bounds** (`native/src/`): `start_servo`
    continues after `create_dir_all` fails and runs with an unwritable profile,
    silently losing cookies/HSTS (return false); on a `start()` race the losing
    engine thread spins forever on `recv_timeout` (send `Command::Shutdown`
    before dropping the losing sender); a persistent `make_current` failure
    retries and warns at 4 Hz forever (back off and emit one error event);
    `held` capture reports are silently dropped at `MAX_HELD_REPORTS`, so the
    app can await a report that never comes (emit an explicit drop); each capture
    re-expands the ~100 KiB script template with `.replace` (cache it); every
    pre-start/shutdown call logs at `error!`, flooding logcat during input spam;
    and `request_navigation` fires for iframe loads too with no frame identity
    (tag the event with the frame level or filter to top-level).
16. **`forwardKey` consumes every non-Back key** (`EngineSurfaceView.kt`): the
    volume rocker is dead while browsing, and mapped-key handling is a Kotlin-
    side whitelist. Return false for key codes the engine cannot map. Offered
    in #60 (with #56).
17. **Every state emission recomposes the whole screen, open sheet included,
    through freshly allocated lambdas.** `BrowserScreen.kt:75` collects one
    `browserState` at the top; each engine-event pass rebuilds `dismiss`,
    `notify`, the `onDecision` tree and every sheet callback, so an open
    Address/Recent/Settings sheet fully recomposes for events it does not
    display. The device suite already records Recent's first open exceeding
    Espresso's idle timeout on the software emulator. Hoist sheet content below
    the state read or give callbacks stable identities. Measure with the
    recomposition compiler report before and after.
18. **Small query waste beyond the history index.** `releaseViewsBeyond` runs
    one `dao.tab()` per live view after `openTabs()` (`BrowserCoordinator.kt:
    775-784`); `openTabs()` filters `state='OPEN' ORDER BY lastFocusedAt` with
    no composite index; `observeOpenJobs` has no index on `service`;
    `deleteVisitsForHost` mixes `host =` with `substr(host, …)` and defeats its
    index; `HisterIntegration.eligible()` runs service + exclusion queries on
    every capture decision and every dispatch. Cheap individually — fold into
    item 14's EXPLAIN pass or cache exclusions behind a flow snapshot.
19. **Every HTTP request pays a fresh handshake.** `HttpTransport.kt:77-84`
    `disconnect()`s per call, so each save or search is a new TCP+TLS setup;
    `HisterClient.boundedBody` rebuilds the full ~1 MiB string per truncation
    iteration. Reuse one connection per destination (still with redirects off
    and the existing caps) after measuring; keep credential isolation explicit.

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
- The Hister and Karakeep panels clear their results on every keystroke while
  the debounced search waits: the list flickers and empty space flashes. Keep
  the last answer visible (with the loading line) until the new one arrives.
  Files: `HisterSearchPanel.kt`, `KarakeepPanel.kt`.
  For Load more, Hister currently discards non-Results replies without an
  explanation, and neither panel guards the button while its cursor request is
  running (Fusion 11). Bind in-flight state to destination/query/cursor, disable
  duplicate taps, preserve rows with inline Retry on failure, and reject late
  old-generation pages. Test double taps, delayed old queries and unauthorized/
  offline later pages; #40's service-identity fix is related but distinct.
- Tab badge shows the raw count. Cap the display at "99+" but announce the real
  count. Check 0/1/99/100 without changing logical state. (Offered in #108.)
- Share sends only the URL. Send the title and the address (`title` then the
  URL). File: `share()` in the app module. Long-press Share can copy the address
  instead of opening the chooser. (The title half is offered in #51 and #102;
  the long-press copy stays open.)
- Address-copy long press is in open PR #97 `fusion/copy-address` (Fusion 20),
  with a labeled accessibility action, no copy action on home, and no duplicate
  Android 13+ confirmation. Do not implement that slice again. Still verify a
  physical long press, real clipboard contents and TalkBack on the owner's
  phone; the emulator tests invoked the exposed semantics action.
  Also verify link/image-address copying through the existing context menus on
  API 30-32; those are separate actions. Keep a
  visible copying route. Optional future experiments remain full-URL reveal
  without losing tap-to-edit, and a user-invoked Paste and go row; never read
  the clipboard in the background.
- Recent row menu has Remove and Forget. Add Copy link and Share there, using
  the clipboard and chooser the page UI already uses.
- Address-sheet focus is requested in `LaunchedEffect` and may run before the
  sheet window is laid out. Confirm the keyboard opens and the field clears it
  on a device.
- Recent's relative times are frozen at the composition that opened the sheet
  (`RecentSheet.kt` reads `now` once, and item keys stop rows recomposing on
  scroll): a sheet kept open says "5 minutes ago" for an hour. Recompute per
  row, or on a slow ticker, keeping `DateUtils.getRelativeTimeSpanString`.
- Cold start with a restored tab paints the "Starting the engine…" overlay
  across the whole page area until the engine reports ready
  (`BrowserScreen.PageOverlay`). The restored page paints shortly after; show
  nothing (or the home surface) for that sub-second window to avoid the flash.
- Empty states (home, empty Recent) are plain text. One line of useful copy is
  enough. Do not add decoration that competes with the page.
- The complete palette is offered in #26; reviewed main still partially overrides
  Material defaults. Preserve warm paper/pine and charcoal surfaces, system type
  in chrome and a restrained Reader measure. Beyond roles, the design concept
  (`docs/design/combray-concepts.fragment.html`) fixes the geometry the app has
  not adopted: a distinct panel surface over the background instead of
  `surface = background`, a soft tint for chips/pressed rows/icon tiles, 12 dp
  chips and fields, 23 dp sheet tops, and the restrained type scale (20/500
  sheet titles, 14/500 rows, 11–12 meta). Also: Save is a star while Karakeep is
  a bookmark manager and the concept uses `bookmark`/`bookmark-check`; reader
  view could take a paper tone in light mode; the loading line could take the
  accent tint. Verify sheets, chips, outlines,
  disabled/errors, dialogs, Reader and system bars in both modes, including text
  and non-text contrast. Landscape/large text on Recent and Settings is unverified.
  Lint also flags the missing monochrome layer in
  `app/src/main/res/mipmap-anydpi/ic_launcher.xml`. If themed launcher icons
  matter to the owner, generate a separate matching monochrome asset and test
  platform tinting; preserve the original square, opaque `media-sources/icon.png`
  unchanged. Do not bake Android masking into the source artwork.
- A theme override (system / light / dark) is not built. System appearance is
  the default. Add an override only if the owner wants the phone and the room
  to disagree. (Offered in #52, together with reader text size.)
  Material You dynamic color on Android 12+ is another optional variant; keep
  pine/paper as the deliberate default.
- Local history completion in the address editor: titles and hosts from Recent,
  clearly on-device (the plan's "no autosuggest" covers search typing and server
  contact). Selecting a completion fills the field; it never submits.
  #79 implements the other variant — one tap opens the page like a Recent row,
  shown only while the text differs from what the editor opened with; pick one
  behavior when merging, do not ship both.
- The More sheet's pause row uses `Close` (X) for "Pause history capture"
  (`MoreSheet.kt:76`): X reads as remove, not pause. Hand-build a pause glyph
  the way `StarBorder` already does, or pick accurate semantics.
  The sheet also hides Forward conditionally while other rows stay, so its
  height changes between tabs; consider fixed rows with disabled states for
  muscle memory, keeping the "only offer what works" intent.
- Home and the engine-failure overlays are fixed centered columns with no
  scroll (`BrowserScreen.kt:324-360`): at 200% font on a 320 dp screen the
  headline and two 240 dp buttons clip with no way to reach them. Wrap them in
  a scroll container.
- "Not configured" notices inside the Hister/Karakeep panels are static text
  (`HisterSearchPanel.kt:98`, `KarakeepPanel.kt:98`) where the save path
  correctly offers a Settings action. Make them one tap from the fix.
- A queue row with no URL falls back to showing the raw `dedupeKey`
  (`SettingsSheet.kt:490`); use a neutral "Unknown page". The Hister exclusion
  list has no empty state and removes rows instantly (`SettingsSheet.kt:443-453`)
  against the app's own confirm-or-undo standard.
- The local Recent filter field sets no IME action (`RecentSheet.kt:122-128`):
  plain Enter, keyboard stays up, unlike the panels' `ImeAction.Search`.
- TalkBack gaps: the More and Address sheets have no heading node (focus lands
  in a list/field with no context, unlike Recent and Settings); overlay states
  (engine missing/failed/starting, page crashed) have no live region, so a
  crash is silent until exploration. The loading indicator's "Loading" is not
  announced as a start either.
- Sheet gutters disagree: 16 dp (Address, Recent), 24 dp (Settings), default
  list inset (More). Pick one rule and apply it.
- Record the frozen palette tokens and their measured contrast in
  `docs/design/tokens.md` once a palette PR lands (#55/#26/#62 pick one); the
  design README asked for on-device validation before freezing, and none is
  recorded.
- The address chip's security icon comes from the URL scheme string, not from
  engine data; the plan asks for engine-derived state. Today only `loadFailed` is
  engine truth in that chip, and #43 governs the label's honesty. Do not draw a
  lock the engine did not confirm.
- Touch targets: Audit icon buttons in `BottomBar` and `RecentSheet` to ensure
  48x48dp minimum interactive component size (`Modifier.minimumInteractiveComponentSize()`).
  Bottom-bar checks already exist; prioritize Recent and Settings and measure
  non-overlap before adding blanket modifiers. Five 48dp actions leave roughly
  64dp for the address surface at 320dp, before its icon/padding. Verify #50's
  proposed full-width address row preserves readable origin, Share and target sizes.
  Start ellipsis preserves the string suffix, not necessarily the complete
  registrable domain, despite the source comment (Fusion 12). Check actual
  rendered site identity at 320/360dp, 200% type, RTL, long IDNs/subdomains and
  99/1000 tabs; passing hit-target semantics alone is insufficient.
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
without reproducing the last-row/pagination problem. A two-column Recent at
landscape/tablet widths is an option to evaluate with the same measurements.

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
  (Offered in #109; the address-swipe half stays open. The alternative below,
  long-press opening the Hister scope, then needs its own gesture.)
- A pending mark on Save when the outbox still has work, separate from the
  per-page star.
  Per-page Pending already has a spinner/live-region label; add another mark only
  for distinct useful information. Capture acknowledgment is not proof of indexing.
- On the new-tab surface, one line: the last page you can reopen, and the open
  tabs. That is the Recent-home idea in miniature. Do not retire the sheet.
  The local madeleine variant reads the existing Recent projection: title,
  domain and relative time, no extra history store or recommendation feed. It is
  distinct from the optional user-invoked Hister recall experiment above.
  A quiet wordmark/search/Recent variant is another experiment; the owner has not
  selected a final Recent-home layout. Avoid a busy dashboard. (#42 offers the
  monogram and host quick links on that surface.)
- Reader text size, A− / A+, remembered locally. Body text is fixed at 18 sp.
  Not a theme system. (Offered in #52 as a three-way setting.)
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
  must not be the only way to close a tab. (#53 offers the swipe; the close
  button and Undo stay.)
- Open copied link: when the new-tab page is up and the clipboard holds a URL
  (or text containing one), show one chip to open it. Android shows its own
  clipboard notice; never read the clipboard in the background.
- A Kagi bangs hint under the new-tab buttons: queries already go to Kagi, so
  `!w`, `!gh` and friends work for free. One muted line, no maintenance.
- Long-press the Recent button opens Recent straight on the Hister scope (the
  owner's long-term memory lives there), as the alternative to the previous-tab
  experiment above.
- One haptic tick when a save lands (the Saved transition only — not on every
  routine action), and a calm morph of the Save icon instead of the spinner
  overlay.
- `ACTION_SEND` into Combray: share a link to the app to open it, or offer
  "Save to Karakeep" from the share target.
- Hardware keyboard shortcuts (Ctrl+T/W/L/R, Ctrl+F) once the engine forwards
  modifiers (engine item 4 drops `metaState` today).
- Favicons in Recent or the address chip only if the embedder already has them.
  Do not fetch icons through another stack.
- A compact, non-color-only mark for whether this page will be captured
  (on, paused, excluded). Label eligibility/acceptance accurately; Hister's
  acknowledgment alone does not prove searchable indexing. Derive paused/
  excluded/queued reasons locally; do not make a background Hister search merely
  to fill a badge.
- Named Undo: `Notice.TabClosed` carries a title. Show a bounded title with an
  untitled fallback, preserving exact tab identity and a reachable Undo action.
- Host letter-tiles instead of favicons: a 24–32 dp tile with the host's first
  letter in Recent rows and the address chip, tinted deterministically from the
  pine/ivory family. Solves "two same-host tabs are indistinguishable" with no
  network (favicons are a privacy surface) and no engine work. Pure function of
  `displayHost`; keep it quiet.
- Undo for forgetting: `forgetVisit`/`forgetSite` delete local history and
  cancel queued captures with no recovery path, while tab close has Undo.
  A short-lived restore needs the deleted row and its captures back in one
  transaction — coordinate with #32 so the semantics stay atomic.
- A notification when a queue needs attention (`SaveStatus.NeedsAttention`,
  Hister auth pause): today the state is visible only inside Settings. Minimal
  content (host only), WorkManager is already wired; coalesce, never notify
  per automatic capture.
- Keep a local offline copy of captured pages: the capture pipeline already
  extracts bounded readable text and then throws it away on upload success.
  "Read this later on this device" reuses it and gives `LIBRARY_MISSING` and
  airplane mode something honest to show. Privacy stance must be explicit:
  local-only is not Hister capture and never uploads.
- A low-opacity launcher glyph behind the empty Home headline (nothing on a
  populated screen, no animation before first paint) as the one decorative
  flourish the chrome section's restraint note allows.

- Reader scroll progress: a thin bar that fills as you move through the article,
  beside the quiet reading-time estimate above. Derived from the existing
  `article` blocks; no server contact.
- A quiet dwell line in Page Info: approximate time spent on this page this
  visit, derived from focus transitions the coordinator already records.
  Private and local; label it as an estimate. Very Combray: browsing as
  reading, not clicking.
- Quiet reading: while Reader is Ready and idle, hold back snackbars and outbox
  badges for a stretch; restore on scroll, close, or a sheet. Local to Reader;
  no capture-policy change.
- Bookmark memory: a one-line "last saved from this site" hint in the Save
  dialog, from existing `SavedItem` rows. Recall, not a recommendation feed.
- Reader paper themes: Sepia and Solarized (and an E-Ink serif) palettes for
  Reader only, applied through a local `MaterialTheme` override. Pages are not
  recolored.

Skip: pull-to-refresh (it fights the surface), edge-swipe as the only close,
and any second renderer. Keep reduced motion and avoid decorative animation or
haptics on every routine action.

## Blocked on the pinned engine

- Stop. More shows Reload on purpose.
- Downloads. The reviewed bridge has no download event. Confirm a Servo hook
  before adding a JNI method. Do not invent one.
- Camera, microphone, location. Every permission request is denied. That is the
  safe default. A prompt needs a tagged request, the origin, and the Android
  runtime permission first. Useful feedback need not wait for granting support
  (Fusion 18): show one document-attributed notice and a Page Info explanation
  for an unsupported request, with no stale/hidden-tab notice or automatic
  grant. Repeated requests must not spam the user.
- Text selection, pinch zoom, find in page. Not exercised. Do not add a gesture
  that fights the surface.
  Pinch touches are already forwarded; verify behavior before adding another
  gesture recognizer. Find in page deserves a bounded, document-scoped engine
  path with bottom search/count/next/previous/dismiss controls. Test Unicode,
  many/no matches, large/changing documents and Back/IME. No full-page copy on
  every keystroke and no Hister/Kagi request for local page search. Site-data
  controls also need verified engine storage and privacy semantics.
  The pinned `servo-checkout` does expose candidates for these — none wired,
  none Combray-tested: `WebView::adjust_pinch_zoom` / `set_page_zoom`
  (`components/servo/webview.rs`), `accesskit_tree_id` /
  `set_accessibility_active` for TalkBack, `notify_scroll_event` plus
  `status_text`/favicon hooks for scroll feedback and page marks, and
  `clipboard_delegate` for a future selection copy. Treat them as leads for the
  zoom/accessibility/find work, not as verified behavior.

## Verification still open

- arm64 physical device: the stale-frame check (seen only under SwiftShader),
  pinch zoom, keyboard on a real page, dark mode, landscape, large font.
- One deliberate save and capture against the real services, then clean up.
  Do not export personal history to validate a fixture.
- Accessibility pass on Recent and Settings. Other screens have device checks.
- Personal signing key and a native build in CI before any release tag.
  Page accessibility is separate from chrome: the SurfaceView tests do not
  establish an Android accessibility-tree bridge. Still to do: the owner's
  keyboard, essential live sites, login and cookie persistence, and final
  arm64 ABI/ELF/16 KiB page-size compatibility as applicable before release.
  No real service writes without an explicit test action.

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

## Fusion validation

Independent branch regressions were failing-first, then green after the fix.
The 21 October pre-publication combined unpublished gate `./gradlew --offline testDebugUnitTest lintDebug
assembleDebug assembleRelease :app:assembleDebugAndroidTest` succeeded with no
lint errors. Five selected AccessibilityTest cases passed on our API 30 x86_64
software emulator (3 copy-action cases plus 2 large-font/48dp/non-overlap
cases). Lint retains the existing targetSdk/Espresso/ChromeOS/monochrome-icon
and EngineSurfaceView tooling/performClick warnings. No native engine was
staged; no signed usable release, native device behavior, physical gesture,
actual clipboard contents, live integrations or phone battery/FPS were
validated. Additional 22 October narrow checks on the merged 752d7da trees:
the encoded policy+integration suites green, the background coordinator suite
plus app compile green, and the copy slice's app unit/debug/instrumentation
assembly green. Logs/diffs: `build/fusion-audit/final-kotlin-gate.log`,
`final-accessibility.log`, `final-xml/`, `verification-union.diff` and
`{encoded,background,copy}-vs-752d7da.{log,diff}`; local artifacts are archived
under `~/.local/share/devin/cli/combray-fusion-artifacts-20261021/` rather than
left to ephemeral /tmp.

Unit-test gaps found by the Muse pass, each cheap and each missing today:
retention enforced at claim time (item: RUNNING rows past the age limit),
a drain whose `prune`/`claim`/`finish` throws still scheduling its wakeup,
`Notice.Problem` ever reaching the snackbar, Recent ordering ties and the
500-row cap, `SERVO_ERROR_TITLES` asserted against the real engine on a
device, and one emulator flow of the address sheet: type, receive a #79
suggestion, open it, rotate with the IME up. Dark-mode screenshots of the
finished palette (sheets, checkboxes, switches, chips) are still the missing
visual evidence for the palette work.

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
- Protect the bridge invariants while editing: one serialized command queue in
  `BrowserCoordinator`, generation/document identity on every engine event,
  fenced outbox claims, epoch-guarded surface painting, and secrets bound to
  their server fingerprint. Changes that relax any of these need tests that
  fail without them.
- Add policy tests for pasted URL tabs/newlines before changing normalization;
  blindly replacing every whitespace character with a space can change meaning.
  Count ambiguous attempts conservatively: counting only returned failures can
  permit endless resends after repeated response loss. Non-positive Kotlin
  `repeat` counts do not establish a crash; the input concern is positive-range
  work and correct editing semantics.
