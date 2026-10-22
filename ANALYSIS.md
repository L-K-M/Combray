# Combray — analysis and future work

Shovel-ready list for the next session. Consolidated 21 October 2026 from the
earlier roadmap (`SWE2.md`), `Grok.md` (discarded after its merge),
`GeminiFlash.md`, the Sol review (`Sol.md`, landed with #28), the MiMo review
(`MiMo.md`, folded in below), and the Muse review (`MuseSpark.md`, folded in
below and discarded). Sol's and Muse's source citations refer to
`e5cf8ed`/`4105c28`; later handoffs record re-verification. This version also
incorporates the independent Fusion review in `fusion.md` (source baseline
`4105c28`; its source findings are not phone benchmarks), the Inkling review
(`inkling.md`) and the GLM review (`glm.md`), each kept as the review record.
Their code slices were published as PRs on 21 and 22 October 2026.

On 22 October 2026 a triage reviewed every open PR (#24 to #110). Waves 1
and 2 landed on main that day. Duplicates, superseded PRs, PRs with a false
premise and designs the owner declined are closed; the native wave 3 PRs stay
open with the changes each needs. See
[Pull requests after the 22 October triage](#pull-requests-after-the-22-october-triage).
The owner's answers are recorded in the plan's
[owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026);
this file links there instead of repeating the rationale. Read
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

Prioritize the remaining privacy/state fixes, stale-frame correctness, input and
bounded extraction; then readable chrome, accessibility and theme validation.
An open PR is not an implemented feature on main. Remove completed work only
after it lands and its acceptance evidence is recorded. New ideas below are
proposals, not additional confirmed requirements, unless marked decided with a
link to the owner decisions.

## Pull requests after the 22 October triage

The triage read each PR's body, diff, CI on its head and GLM review threads; it
ran no Gradle, Rust or device build. The owner's answers, and which are
Confirmed or Proposed, are in the plan's
[owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026).

### Landed on main

Wave 1 landed on 22 October 2026 through one integration PR, with small fixes
on top (for example #51's 200-character title cap and #39's `?id=%33` case in
#95). #65 merged on 21 October; the `Element.animate` polyfill (#94/#104) was
already on main. The wave's evidence is JVM-level: unit tests, lint and
assembly in CI. The integrated tree had no device run, and nothing here proves
deployed service compatibility.

| PR | Branch | What it changed |
| --- | --- | --- |
| #75 | `opus/ci-checks` | CI compiles androidTest and checks committed Room schemas, `cargo fmt` and page-script syntax (`native/src/*.js`, `native/src/vendor/*.js`); `scripts/check-app.sh` runs every `*Test` class, so `ReaderTest` is in the default run (Sol S04) |
| #28 | `sol/review` | `Sol.md` review record; [ENGINE_SPIKE section 14](docs/ENGINE_SPIKE.md#14-sol-review-validation-21-october-2026), the Sol validation run |
| #107 | `glm/retry-after-overflow` | `Retry-After` seconds clamped before the millisecond conversion (correctness item 14) |
| #31 | `sol/expire-abandoned-captures` | Abandoned RUNNING Hister captures expire; age rechecked inside every claim (S13) |
| #30 | `swe2/budget-flag` | Hister budget warning derived from the live queue size |
| #106 | `glm/save-guard-order` | An unconfigured Save says "set Karakeep up" before the credential-URL guard |
| #35 | `sol/karakeep-list-receipt` | A list-bound save needs a usable bookmark ID to finish (S15) |
| #68 | `opus/hister-paging` | An empty `page_key` ends Hister paging; Hister's More is disabled while its page loads |
| #95 | `fusion/encoded-credential-keys` | Query and fragment names percent-decoded once before the credential check (Fusion 1) |
| #73 | `opus/saves-address-change` | Changing Karakeep's address parks unsent saves (`DESTINATION_CHANGED`); Settings offers sending them to the current address or discarding them |
| #32 | `sol/atomic-forgetting` | Forgetting history and cancelling queued captures share one Room transaction (S14) |
| #110 | `glm/retention-graph` | One `Retention` instance shared by maintenance and Settings (correctness item 4) |
| #71 | `opus/recent-order` | Leaving a visit retires it with the tab's last-use time; the current tab stays above pages it left |
| #34 | `sol/reader-navigation` | Accepted URL changes, same-document and hash routing included, leave a ready or pending Reader (S01) |
| #46 | `grok/stale-reader-article` | A Reader article is accepted only from the live, uncrashed view and the same document |
| #70 | `opus/host-display` | `data:`, `about:`, `file:` and similar pages never show another site's host |
| #108 | `glm/tab-badge-cap` | The Recent badge shows 99+; TalkBack keeps the exact count |
| #97 | `fusion/copy-address` | Long-press on the address copies the exact URL, marked sensitive (first part of Fusion 20) |
| #51 | `glm/share-title` | Share sends the title and the address (`EXTRA_SUBJECT`, first line) |
| #48 | `grok/page-info-dismiss` | Page Info closes from an effect, not a state write during composition |
| #100 | `inkling/karakeep-pagination-key` | The Karakeep panel keys its load on fingerprint and generation, not the whole row |
| #56 | `opus/system-keys` | Only keys `servo_key` maps are consumed; system keys reach Android (performance item 16) |
| #65 | `muse/engine-failed-status` | 21 October: a tab-less engine error while `STARTING` moves the status to `FAILED`. Deferred: include `event.request` in the engine-level log line |

Wave 2 landed on 22 October 2026 through a second integration PR, in the order
below, with the reworks the triage asked for. Its evidence is also JVM-level;
the instrumentation tests it adds or changes compile in CI but had no device
run. Known limits: a numbered Reader item with several paragraphs still shows
a bullet before its later paragraphs (the review's patch would have removed
every bullet), and #96's throttle is a hint the engine may drop.

| PR | Branch | What it changed |
| --- | --- | --- |
| #66 | `muse/inert-event-publish` | Paint heartbeats and other inert engine events skip `publish()`'s reads, gated inside the event dispatch as #24 had it |
| #62 | `opus/theme` | The pine and ivory color scheme, complete in light and dark |
| #64 | `opus/recent-polish` | Recent rows: initials, last-used times, weekday headers, TalkBack scope tabs |
| #53 | `mimo/swipe-close-tabs` | Swiping a tab row in Recent closes it; the background shows only while swiping |
| #78 | `opus/restored-splash` | A launch screen holds until the session is restored; a failed maintenance still publishes the stored tab |
| #52 | `mimo/appearance-and-reader-size` | Settings › Display › Appearance (`ThemeMode`); the reader text size was dropped for #87's single text control |
| #41 | `grok/forget-resurrected-history` | A forgotten page stays out of Recent, the page an open tab shows included; the tab keeps its title. The integration added `TabEntity.pageForgotten` (schema 4), so loading that page again in the tab (restore, eviction, crash, reload) records and captures nothing |
| #36 | `mimo/recent-visit-identity` | A navigating tab's old page stays in Recent while the tab has no renderer; a detached page is retired (#71) |
| #72 | `opus/history-fixes` | Reload keeps titles; Recent search reaches all retained history; Undo focus (Proposed) |
| #67 | `muse/recent-single-flow` | Recent subscribes once per depth (newest rows, all retained history), filters in memory and caps history after the filter; `remember(browser)` from #25 |
| #37 | `mimo/outbox-resilience` | The outbox survives a broken job cycle; a failed claim waits before the next wakeup; `LEASE_MS` KDoc from #29 |
| #74 | `opus/outbox-patience` | Work that never reached its server keeps its attempts and needs attention after seven days, counted from the last re-save or retry; Hister refusals are dropped |
| #76 | `opus/icons-haptics` | Bookmark icons, a Waiting state, haptics on saves and long-press, a pine tab badge |
| #43 | `grok/no-decorative-lock` | The chip marks only failed and cleartext loads; HTTPS is not called verified |
| #57 | `opus/back-to-opener` | Back on a page-opened tab returns to its opener; one Back action for system Back and the bar, which leaves Reader first |
| #47 | `grok/bottom-back` | Back on a tab another app opened returns to that app (merged as part of #57) |
| #58 | `opus/more-close-tab` | Close tab in More; "Resume history capture" stays while capture is paused (Proposed) |
| #59 | `opus/link-menu` | Open in background and Save link in the link menu; copies use the sensitive clipboard helper |
| #63 | `opus/address-suggestions` | Address suggestions from open tabs and Recent, with #79's single subscription, address-policy filter and heading |
| #61 | `opus/share-to-combray` | Other apps share links and text into Combray; "Save to Karakeep" asks once before saving; launcher shortcuts |
| #109 | `glm/quick-tab-switch` | Long-press Recent switches to the previous tab; a sideways swipe on the bar steps through tabs (owner request) |
| #45 | `grok/hister-malformed-search` | A Hister answer that is not a search result is reported; `"history": null` stays an empty result |
| #91 | `opus/sensitive-params` | Broader credential-parameter matching on #95's decoding; fragments count only `name=value` pairs |
| #69 | `opus/address-policy` | Typed private and loopback IP literals and `localhost` open over plain HTTP; `user:password@host` is refused |
| #77 | `opus/reader-typography` | Reader view: the page's list numbers, a serif type scale, "Show the page" at the top |
| #96 | `fusion/background-throttling` | Backgrounding the app asks the engine to throttle the shown page too (a hint Servo 0.6.0 does not reliably honor) |
| #92 | `opus/pending-load` | The loading line shows from the app's own request until a commit, 30 seconds at most (Proposed) |
| #50 | `grok/narrow-address` | Below 400dp the address gets its own row above the actions; snackbars clear the bar's measured height |

### Closed without merging

Rows that name a PR in "closes when" stay open until that PR lands.

| PR | Reason | Where its value went |
| --- | --- | --- |
| #24 | Duplicate of #66 | Its gate structure is #66's shape |
| #25 | Duplicate of #67 | `remember(browser)` is in the reworked #67 |
| #26 | Superseded by #62 (no tests, thinner contrast) | None |
| #27 | Owner chose no floating Reader close button | Exit while loading, if needed, as a #77 follow-up |
| #29 | Premise cannot occur: one dispatcher runs per process | Accurate `LEASE_MS` KDoc is in #37 |
| #39 | Same logic as #95 | Its `?id=%33` case is in #95 |
| #40 | Same key as #100, with a false changelog claim | None |
| #44 | False headline: `reader.js` and `Article.kt` pass only http(s) links, so `mailto:` and `tel:` never reach it | Idea: a rejected Reader link shows `notice_address_not_opened` |
| #49 | False premise: only FakeEngine's message carries the URL; native refusals carry none | None |
| #55 | Superseded by #62 | None (its optional surface-ladder test was not taken) |
| #60 | Duplicate of #56, less accurate (it forwarded keys `servo_key` drops) | Its tests and modifier KDoc are in #56 |
| #79 | Duplicate of #63, weaker tap semantics | Single subscription, address-policy filter, "On this device" heading and gate tests are in #63 |
| #85 | Covered by #90's SHOW_REFUSED path | Its scenario is to become a KeyboardTest case for #90 |
| #98 | Owner chose no Material You default and no pitch-black variant | #52 carries the override |
| #99 | Equivalent to #48 | Its comment note is in #48 |
| #101 | Owner chose no floating Reader close button | None |
| #102 | Duplicate of #51 | Its trailing default parameter is in #51 |
| #105 | Regression: freezes the address the dialog opened with | None |

### Wave 3

Wave 3, the native PRs, is integrated on one branch. Every merge was compiled
against the pinned Servo for x86_64 (`scripts/build-native.sh`), and
`cargo test --lib`, the JVM tests, lint and assembly pass. It has had no
device run: `scripts/check-app.sh` on an emulator must pass on the integrated
tree before it counts as landed, and the PRs' device evidence is from their own
branches. Owner decisions are Confirmed for #80, #84, #86, #89 and #103's
direction; Proposed for one text-size control (#87). #103's proposed hiding
after scrolling stops is not done: its device checks rely on the button
staying, so the change needs its own device run.

| PR | Branch | What it changes |
| --- | --- | --- |
| #82 | `opus/screen-geometry` | Pages read a real screen and window size (`screen_geometry`) |
| #83 | `opus/media-cancel` | Audio, video and text-track loads are cancelled before downloading; one notice per page |
| #93 | `opus/leave-page` | "Leave this page?" for a touched page's `beforeunload`; Stay also ends #92's loading wait |
| #87 | `opus/page-zoom` | More › Text size zooms every page; Reader view scales by the same step (one text control, Proposed) |
| #103 | `feat/scroll-edge-controls` | Scroll to top while scrolling up, to bottom while scrolling down, on pages and in Reader view |
| #86 | `opus/find-in-page` | Find in page, bound to the document on screen (view, generation and document in every request) |
| #80 | `opus/external-app-links` | Links for other apps ask first, then hand off; malformed `intent:` links are refused instead of crashing; `file:` navigations refused |
| #84 | `opus/popup-blocking` | Windows only after a tap on the document the user tapped; one window per tap |
| #89 | `opus/sign-in-userinfo` | HTTP sign-in credentials stay out of page URLs, Recent and shares; capture and save stay allowed (owner) |
| #54 | `opus/page-dark-mode` | Pages see `prefers-color-scheme: dark` from the app's resolved theme, the in-app choice included |
| #81 | `opus/capture-budget` | Capture holds the page on screen at most 400 ms and waits while it is touched; the engine wakes for a deferred capture |
| #90 | `opus/keyboard-activation` | Page keyboards open only on user activation; per-tab fields; a dismissal acts only on an open keyboard |

Before a device run, check in particular: #90's reopen-after-Back check (it
failed once on its branch), #86's `FindTest` (its scroll assertions read
`scrollY`, which #103 found stale after programmatic scrolls), #80's and #84's
tests through the new "Open this link in another app?" question, and #85's
scenario for #90 (a page focusing its field while the address sheet is open),
which is not written yet.

### Still open

| PR | Before it lands | Line |
| --- | --- | --- |
| #88 | Stays a draft: throttling does not work yet, and #96 supersedes its Kotlin half. Fix ThrottlingTest's metric (read `window.ticks` through `NativeEngine.evaluate`) before blaming Servo; split `wakeup.rs` out later; #81's deferred captures now wake the engine themselves | Not landing |
| #42 | Waiting on the owner: a launcher monogram and host quick links on the new-tab page, ranked by visits in the retention window | Undecided |

Notes for the open work:

- **Outbox lease (#37, landed; from #29).** One dispatcher runs per process, so
  an in-process concurrent reclaim cannot happen, and a longer lease only slows
  crash recovery; #37's `LEASE_MS` KDoc says so. Per-read and connect timeouts
  are still not an overall request deadline: a slow trickle or DNS can outlast
  a fixed lease. Test a
  slow live request, expiry and takeover, and its stale late completion; use
  deadlines or renewal and fenced transitions, never an exactly-once promise
  (Fusion 15).
- **Forgetting (#32, #41, #36, all landed).** #32 deletes local history and
  cancels queued captures in one transaction. #41 keeps a forgotten page out of
  Recent once you leave it, and the tab's `pageForgotten` mark keeps it out
  when the page loads again in that tab; #36 keeps a renderer-less tab's page
  in Recent at both detach sites. Pending capture timers are not cancelled
  (correctness item 34), but they can no longer send an open tab's
  forgotten page.
- **Device runs of older branches.** A build without #73's
  `DESTINATION_CHANGED` crashes reading an outbox that holds one; rebase a
  branch onto main before running it on a device with main's data.

## Correctness still open

History/capture atomicity (#32), abandoned-claim expiry (#31), page-info
dismissal (#48), Karakeep-panel identity (#100), Retry-After clamping (#107),
the Retention plumbing (#110), percent-encoded credential names (#95) and
system keys (#56) landed on main on 22 October 2026 in wave 1. Wave 2 added
outbox resilience (#37), patience with unreachable servers (#74), forgotten
pages staying out of Recent (#41, #36), Recent search over all retained
history (#72, #67), broader credential names (#91), malformed Hister answers
(#45) and honest connection labels (#43). Do not implement them again.
Validate the listed source candidates before editing; none is a new device
reproduction.

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
is distinct from the local-deletion/cancellation transaction that landed in #32.
Files: `HisterIntegration.kt`, `OutboxDispatcher.kt`, `Outbox.kt`,
`IntegrationDao.kt`; use controlled barriers at claim/dispatch/admission.

1. **A failed list add hides a bookmark Karakeep already stored.** After
   `createLink` succeeds, a list PUT that is not `Done` becomes the job result,
   and the saved-item row is written only on final `Done`. Record the bookmark
   when create succeeds. Retry only the list step. A `404` stays a visible
   failure. File: `Integrations.dispatchSave`. The plan says a list step must not
   mark the link unsaved. This is separate from #35's landed missing-ID fix. Test a
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
4. **Landed in #110:** one `Retention` instance flows from `AppGraph` to
   maintenance and the Settings history-days label. Exposing retention as a
   setting is the chrome section's configurable-retention proposal.
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
8. **Landed in #72 and #67 (Sol S02):** a non-blank Recent filter reads every
    retained visit (`HistoryDepth.ALL_RETAINED`), matches in memory (no SQL
    `LIKE`, so `%`/`_` are literal) and caps history rows after the filter; a
    coordinator test finds the oldest of 600 visits. No search test yet covers
    Unicode case folding, failed visits or the open tab's current visit.
9. **Service loading and queue rejection states (Sol S17).**
    `HisterSearchPanel`/`KarakeepPanel` initially collect configuration as null,
    flashing a false setup prompt. Delay the first configured emission and verify
    no false prompt. #30 (landed) derives the budget warning from the live queue
    with a largest-payload margin, so it clears after drain or discard and
    covers a body larger than the remaining space.
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
14. **Landed in #107:** `Retry-After` seconds are clamped before the
    millisecond conversion. JVM tests cover huge, invalid, negative and ordinary
    values, case-insensitive headers and the HTTP-date limitation.
15. **Links to other apps.** Decided: `mailto:`, `tel:`, `intent:`, `market:`
    and similar links ask before Combray hands them to Android
    ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
    Wave 3 integrates #80 and #84: such a link reaches Android only after a tap
    and the "Open this link in another app?" question, a malformed `intent:`
    link is refused instead of crashing, and a window needs a tap on the
    document the user tapped (device run pending). Before wave 3,
    `AddressPolicy.EXTERNAL_SCHEMES` allowed only `mailto:` and `tel:`, and the end-to-end gap (Fusion 5) is not just
    missing scheme names: `native/src/delegate.rs::request_navigation` allows
    page requests and the coordinator ignores `PageNavigationRequested`, so
    clicked mail/tel links never reach the typed-input external decision. Carry
    view/document, frame level and user activation into the decision: normal
    HTTP(S) stays fast and unsupported schemes are refused. Never launch an
    intent from arbitrary script or an iframe; `javascript:`, file and content
    schemes stay rejected. Test mail/tel, `intent:`, redirects, popups,
    unsupported schemes and absent handlers. No local-file read or sandbox
    escape has been demonstrated.
16. **Page controls can be attributed to the wrong page.** `view.controls` is
    cleared on crash but not when a document commits, so a dialog from the
    previous document is presented under the new page's host when the engine
    does not send `ControlHidden`. Clear pending controls when
    `view.currentDocument` changes, answering them Dismiss. File:
    `BrowserCoordinator.kt`.
17. **Coordinator lifecycle edges.** `start()` is not idempotent (a second call
    starts a second work-queue consumer and breaks the single-consumer ordering
    invariant); `onEngineError` ignores
    refusals without a request, leaving the coordinator believing a dead view is
    on screen; and `pendingCaptures`/`pendingArticles` are removed only when a
    result arrives, so a crashed view leaks them for the process lifetime (fold
    the last one into item 13's pruning when measured). `recent()`'s retention
    window now slides while a sheet stays open (wave 2). Each is small and
    unit-testable like the PR #36 cases. File: `BrowserCoordinator.kt`. The narrow
    unscoped-STARTING failure path is already fixed on main by #65 (see
    [Landed on main](#landed-on-main)); it does not claim all runtime errors and
    is not a task.
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
22. **Landed in #91:** on #95's decoding, `SensitiveUrls` treats `key`,
    `ticket`, `sig`, `authcode`, names containing `token`, `secret` or
    `signature`, and `code` beside `state` (an OAuth callback) as credentials;
    fragments count only `name=value` pairs. Its breadth is Proposed in the
    [owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026).
23. **Any page can summon the keyboard without a user gesture.**
    `native/src/delegate.rs` drops `InputMethodControl.allow_virtual_keyboard`
    (and text/insertion point): a page-side `focus()` shows the keyboard at any
    time. Forward the flag and let the app suppress programmatic keyboards.
    #90, integrated in wave 3, forwards the flag, so the keyboard opens only
    after user activation, and keeps one field per tab; its SHOW_REFUSED path
    covers the app-side half proposed by #85, now closed. Its reopen-after-Back
    device check failed once on its branch and must be re-run. Before wave 3,
    `ime_owner` was a single slot shared across views (performance item 4).
24. **Settings queue rows decode whole payloads during composition.**
    `Integrations.jobUrl` (`Integrations.kt:135-138`) JSON-decodes the full
    capture payload (up to 256 KiB of page text) to read one URL, and
    `SettingsSheet.kt:552` calls it per row inside composition;
    `observeOpenJobs` re-emits on every outbox write. The same decode runs
    inside `HisterIntegration.jobHost`'s transaction when forgetting a site.
    Persist a `url`/`host` column on the job at enqueue (schema, migration,
    backfill) and never parse payloads in composition. Tests: migration with a
    populated queue, a Settings screen over a full Hister queue stays responsive.
25. **Switching Recent's scope auto-sends the local filter to a server.**
    `RecentSheet.kt:213-215` seeds the Hister/Karakeep panels with
    `initialQuery = query`, and their debounced effect fires the seeded text
    with no further user action. The panels promise "only text typed here is
    sent"; the mental model does not include "my local filter is now a server
    query". Prefill the field but require an explicit Search for the first
    request; debounced typing only after the user types in the panel. Test that
    scope entry with a non-blank local filter sends nothing until submitted.
26. **The work-queue consumer survives `Exception` but not `Error`.**
    `BrowserCoordinator.start` catches `Exception`; an `OOM` or
    `StackOverflowError` (article JSON is parsed on that same queue) cancels
    the consumer and the browser silently stops processing commands forever,
    while `CancellationException` on shutdown is logged as a failure and posts
    a spurious notice. Catch `Throwable`, rethrow cancellation, restart or
    degrade visibly. Test with a throwing job that the next command still runs.
27. **`lastFocusedAt` uses `now - 1`, collapsing ties and inverting recency.**
    Tabs losing focus in the same millisecond get identical values
    (`BrowserCoordinator.activate`), and a tab focused at exactly `now` is
    rewritten to a smaller value; `openTabs()` orders by that column, so Recent
    order and `closeLogically`'s successor choice become rowid-dependent when
    links open fast. Use a monotonic focus sequence or `max(old, now - 1)` with
    a tiebreaker. Test two same-millisecond focus changes.
28. **`onDone` runs inside the `finish` transaction.** `Integrations.recordDone`
    decodes the payload inside `Outbox.finish`'s `withTransaction`; a decode
    throw rolls the finish back. Since #37 the drain then records the cycle as
    `Retry("cycle_failed")`, so the job is re-sent after its backoff until the
    attempt limit marks it FAILED, and the save's success is never recorded.
    Decode defensively or run `onDone` after the commit.
29. **Retention is wall-clock with no forward-jump guard.** `maintain()` deletes
    everything older than seven days at every start; a device clock set far
    forward (manual date, bad NTP) wipes all local history and closes unused
    tabs in one go. Clamp `now` against the newest stored timestamp, or record
    the assumption. Test with a clock an year ahead of the newest row.
30. **Page-opened windows become unbounded tabs.** `native/src/engine.rs:607-631`
    adopts `window.open` from the active view with no user-activation check
    (Servo 0.6 does not report activation) and each becomes a full logical tab;
    `releaseViewsBeyond` evicts renderers, not tabs. Cap adoptations per opener
    per time window, or require the coordinator's last real input time. #57
    (landed) records the opener for Back but caps nothing. #84 targets the
    same surface from the native side; check it first.
31. **`BrowserSettings.apply()` can lose a just-toggled setting.** Process death
    right after the tap reverts it silently (`BrowserSettings.write`, which
    #52's theme choice uses too). Use
    `commit()` for this tiny write, or document the loss.
32. **`onCheckIsTextEditor()` is always true while the engine runs.**
    `EngineSurfaceView.kt:140`: TalkBack announces the whole page area as an
    editable field, and the IME can re-show after a window refocus with no page
    field. Return whether the engine reports an active field (last ImeShown
    without ImeHidden).
33. **`WorkManagerScheduler` schedules with `ExistingWorkPolicy.REPLACE`.** A
    wakeup booked while the unique worker is between its final `drain()` and
    return can request cancellation of a worker that is already finishing.
    Benign today (the dispatcher mutex serializes drains in-process); worth a
    one-line rationale or `APPEND` semantics before any multi-process dispatch.
    File: `OutboxWorker.kt`.
34. **Forget does not cancel a pending capture timer.** `forgetSite` and
    `forgetVisit` cancel queued captures (#32) but not `LiveView.captureTimer`.
    A timer that fires after a forget can still enqueue the page's text:
    `HisterIntegration.wasForgotten` only matches jobs created before the
    forget. The plan requires that a delayed capture cannot enqueue erased
    content (durable queue and failure policy). Pre-existing. Since wave 2 a
    tab whose page was forgotten (`pageForgotten`) neither requests nor
    accepts a capture, which covers a timer or capture in flight for that
    page; a page of the site that a tab shows without a visit, or a timer racing
    the forget transaction itself, is not fenced. Cancel or fence matching timers
    in the forget path and test a forget racing a due timer. File:
    `BrowserCoordinator.kt`.

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
5. **`publish()` Room work.** #66 (landed, in #24's gate structure) skips
   `publish()` for inert events such as paint heartbeats, which arrive once per
   second per painting tab. Measure stateful title/load bursts before adding a
   snapshot/dirty flag. Count queries and verify real navigation and title
   events still publish.
6. **Recent re-sorts on every emission.** #64 (landed) computes day boundaries
   with `java.time` once per list change, not a `Calendar` per row. The list
   still re-sorts on every emission of any of its three flows; SQL ordering
   would remove the per-emission sort. Search truncation landed with #72 and
   #67 (correctness item 8).
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
11. **Landed in #66:** `publish()` reads `dao.openTabCount()` (`SELECT COUNT(*)`)
    instead of loading every open `TabEntity`. Any later cache of active IDs or
    counts must stay transactional with tab state.
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
    changing suspension, frame scheduling or the live-view budget. The
    visibility hint landed with #96 `fusion/background-throttling`, a hint Servo
    0.6.0 does not reliably honor; remaining work is real-GPU validation, not
    another implementation of that hint. No measured CPU/FPS/battery numbers.
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
16. **Landed in #56** (with #60's tests): `forwardKey` consumes only keys
    `servo_key` maps, so system keys such as the volume rocker reach Android.
17. **Every state emission recomposes the whole screen, open sheet included,
    through freshly allocated lambdas.** `BrowserScreen.kt:95` collects one
    `browserState` at the top; each engine-event pass rebuilds `dismiss`,
    `notify`, the `onDecision` tree and every sheet callback, so an open
    Address/Recent/Settings sheet fully recomposes for events it does not
    display. The device suite already records Recent's first open exceeding
    Espresso's idle timeout on the software emulator. Hoist sheet content below
    the state read or give callbacks stable identities. Measure with the
    recomposition compiler report before and after.
18. **Small query waste beyond the history index.** `releaseViewsBeyond` runs
    one `dao.tab()` per live view after `openTabs()`
    (`BrowserCoordinator.releaseViewsBeyond`); `openTabs()` filters
    `state='OPEN' ORDER BY lastFocusedAt` with no composite index; `observeOpenJobs` has no index on `service`;
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
  improve hierarchy rather than rebuilding them. Open tabs has its own empty
  message since #64, which also added weekday headers and site tiles. Cover
  duplicate tabs, last rows/pagination, long labels, keyboard and 200% text
  with TalkBack.
  Local typing stays local; remote requests require an explicit Hister/Karakeep
  scope. Keep local, long-term and web search distinguishable.
- The Hister and Karakeep panels clear their results on every keystroke while
  the debounced search waits: the list flickers and empty space flashes. Keep
  the last answer visible (with the loading line) until the new one arrives.
  Files: `HisterSearchPanel.kt`, `KarakeepPanel.kt`.
  For Load more (Fusion 11), #68 (landed) disables Hister's button while its
  page loads and keeps the shown rows with the reason when a page fails.
  Karakeep's Load more is still unguarded. Bind in-flight state to
  destination/query/cursor, disable duplicate taps, preserve rows with inline
  Retry on failure, and reject late old-generation pages. Test double taps,
  delayed old queries and unauthorized/offline later pages; #100's landed
  service-identity fix is related but distinct.
- The tab badge caps at "99+" and TalkBack keeps the real count since #108,
  which added no test: check 0/1/99/100 without changing logical state.
- Share sends the title and the address since #51. Still open: long-press Share
  could copy the address instead of opening the chooser.
- Address-copy long press landed in #97 `fusion/copy-address` (Fusion 20),
  with a labeled accessibility action, no copy action on home, and no duplicate
  Android 13+ confirmation. Still verify a
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
- Landed in #64: Recent's relative times and day headers follow a 30-second
  clock while the sheet stays open.
- Cold start with a restored tab paints the "Starting the engine…" overlay
  across the whole page area until the engine reports ready
  (`BrowserScreen.PageOverlay`). #78's launch screen covers only the session
  restore (two seconds at most), not engine startup. The restored page paints
  shortly after; show nothing (or the home surface) for that sub-second window
  to avoid the flash.
- Empty states (home, empty Recent) are plain text. One line of useful copy is
  enough. Do not add decoration that competes with the page.
- The complete palette landed in #62 (it superseded #26 and #55): every color
  role is set, and `ThemeContrastTest` checks text and outline pairs against
  WCAG. Preserve warm paper/pine and charcoal surfaces, system type
  in chrome and a restrained Reader measure. Beyond roles, the design concept
  (`docs/design/combray-concepts.fragment.html`) fixes the geometry the app has
  not adopted: a distinct panel surface over the background instead of
  `surface = background`, a soft tint for chips/pressed rows/icon tiles, 12 dp
  chips and fields, 23 dp sheet tops, and the restrained type scale (20/500
  sheet titles, 14/500 rows, 11–12 meta). Save is a bookmark since #76, as the
  concept's `bookmark`/`bookmark-check`. Also: reader view could take a paper
  tone in light mode; the loading line could take the accent tint. Verify
  sheets, chips, outlines, disabled/errors, dialogs, Reader and system bars in both modes, including text
  and non-text contrast. Landscape/large text on Recent and Settings is unverified.
  Lint also flags the missing monochrome layer in
  `app/src/main/res/mipmap-anydpi/ic_launcher.xml`. If themed launcher icons
  matter to the owner, generate a separate matching monochrome asset and test
  platform tinting; preserve the original square, opaque `media-sources/icon.png`
  unchanged. Do not bake Android masking into the source artwork.
- Theme override: decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  An in-app Follow the system / Light / Dark choice; pine and ivory stay the
  default, with no Material You default and no pitch-black variant. #52 landed
  it (Settings › Display › Appearance, `ThemeMode`); #54, integrated in
  wave 3, gives pages the app's resolved theme.
- Local history completion in the address editor: decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  A history row opens in the tab being edited (a new tab when the editor is for
  a new tab); an open-tab row switches to that tab. Rows stay clearly on-device
  (the plan's "no autosuggest" covers search typing and server contact). #63
  landed it with #79's grafts.
- The More sheet's pause row has a hand-built pause glyph since #58.
  The sheet still hides Forward conditionally while other rows stay, so its
  height changes between tabs; consider fixed rows with disabled states for
  muscle memory, keeping the "only offer what works" intent.
- Home and the engine-failure overlays are fixed centered columns with no
  scroll (`BrowserScreen.kt:472-507`): at 200% font on a 320 dp screen the
  headline and two 240 dp buttons clip with no way to reach them. Wrap them in
  a scroll container.
- "Not configured" notices inside the Hister/Karakeep panels are static text
  (`HisterSearchPanel.kt:100`, `KarakeepPanel.kt:103`) where the save path
  correctly offers a Settings action. Make them one tap from the fix.
- A queue row with no URL falls back to showing the raw `dedupeKey`
  (`SettingsSheet.kt:552`); use a neutral "Unknown page". The Hister exclusion
  list has no empty state and removes rows instantly (`SettingsSheet.kt:505-516`)
  against the app's own confirm-or-undo standard.
- The local Recent filter field sets no IME action (`RecentSheet.kt:202-208`):
  plain Enter, keyboard stays up, unlike the panels' `ImeAction.Search`.
- TalkBack gaps: the More and Address sheets have no heading node (focus lands
  in a list/field with no context, unlike Recent and Settings; #63's "On this
  device" heading shows only with suggestions); overlay states
  (engine missing/failed/starting, page crashed) have no live region, so a
  crash is silent until exploration. The loading indicator's "Loading" is not
  announced as a start either.
- Sheet gutters disagree: 16 dp (Address, Recent), 24 dp (Settings), default
  list inset (More). Pick one rule and apply it.
- #62 has landed, and `ThemeContrastTest` computes its contrast in a unit
  test. Record the frozen palette tokens and their measured contrast in
  `docs/design/tokens.md`; the design README asked for on-device validation
  before freezing, and none is recorded.
- The address chip's security icon comes from the URL scheme string, not from
  engine data; the plan asks for engine-derived state. Since #43 the chip draws
  an icon only for a failed load (`loadFailed`, engine truth) or a cleartext
  `http:` address (the scheme string), and Page Info no longer calls HTTPS
  verified. Do not draw a lock the engine did not confirm.
- Touch targets: Audit icon buttons in `BottomBar` and `RecentSheet` to ensure
  48x48dp minimum interactive component size (`Modifier.minimumInteractiveComponentSize()`).
  Bottom-bar checks already exist; prioritize Recent and Settings and measure
  non-overlap before adding blanket modifiers. Five 48dp actions leave roughly
  64dp for the address surface at 320dp, before its icon/padding. #50 landed
  the owner's choice of an own address row below about 400dp
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026));
  AccessibilityTest checks both layouts at 320dp and 412dp but has not run on
  a device. Verify it preserves readable origin, Share and target sizes.
  Start ellipsis preserves the string suffix, not necessarily the complete
  registrable domain, despite the source comment (Fusion 12). Check actual
  rendered site identity at 320/360dp, 200% type, RTL, long IDNs/subdomains and
  99/1000 tabs; passing hit-target semantics alone is insufficient.
- Soft keyboard layout stability: verify existing `imePadding`, focus timing and
  viewport resizing before adding animated insets. Respect reduced motion.
  Since #50 snackbars clear the bar's measured height instead of a fixed 72dp;
  still measure snackbar/bar bounds with large fonts and the keyboard up.
- OLED pitch-black option: declined; the owner chose no pitch-black variant
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
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
  Sideways swipes on the bar switch tabs since #109 (next item), so an upward
  swipe must stay distinct from them.

- Previous tab and tab switching: decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  Long-pressing Recent switches to the previously used tab (`lastFocusedAt`
  already orders tabs), and swiping sideways on the bottom bar switches tabs.
  #109 landed both: the long-press, and sideways swipes that step through open
  tabs in Recent order. Keep a visible action and avoid fighting Back or the
  page's gestures.
- A pending mark on Save when the outbox still has work, separate from the
  per-page bookmark.
  Per-page Pending already has a spinner/live-region label, and Waiting a clock
  (#74, #76); add another mark only
  for distinct useful information. Capture acknowledgment is not proof of indexing.
- On the new-tab surface, one line: the last page you can reopen, and the open
  tabs. That is the Recent-home idea in miniature. Do not retire the sheet.
  The local madeleine variant reads the existing Recent projection: title,
  domain and relative time, no extra history store or recommendation feed. It is
  distinct from the optional user-invoked Hister recall experiment above.
  A quiet wordmark/search/Recent variant is another experiment; the owner has not
  selected a final Recent-home layout. Avoid a busy dashboard. (#42 offers the
  monogram and host quick links on that surface; it waits on the owner.)
- Reader text size: Proposed in the
  [owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)
  as one control, where page zoom (#87, More › Text size) also scales Reader;
  #52 landed without its separate reader size. Body text is a fixed 18 sp
  serif since #77.
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
- Close all tabs, or close other tabs, from Recent. Swiping a tab row to close
  it is decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026))
  and #53 landed it; the close button and Undo stay, so the
  swipe is never the only way to close a tab.
- Open copied link: when the new-tab page is up and the clipboard holds a URL
  (or text containing one), show one chip to open it. Android shows its own
  clipboard notice; never read the clipboard in the background.
- A Kagi bangs hint under the new-tab buttons: queries already go to Kagi, so
  `!w`, `!gh` and friends work for free. One muted line, no maintenance.
- Open Recent straight on the Hister scope (the owner's long-term memory lives
  there). #61's "Search history" launcher shortcut does this from the launcher.
  In the app, long-press Recent is taken by the previous tab, so this needs
  another gesture.
- Haptics: decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  A tick when a save lands, a distinct one when it is refused, and one on a page
  long-press; #76 landed them. A calm morph of the Save icon
  instead of the spinner overlay stays an idea.
- Share into Combray: decided
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  Other apps can share links and text into Combray, and "Save to Karakeep" from
  the share sheet asks for one tap before it saves. #61 landed it with that
  confirmation, plus launcher shortcuts.
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
  `displayHost`; keep it quiet. #64 landed the Recent-row tile in one sage
  tint; the per-host tint and the address chip remain.
- Undo for forgetting: `forgetVisit`/`forgetSite` delete local history and
  cancel queued captures with no recovery path, while tab close has Undo.
  A short-lived restore needs the deleted row and its captures back in one
  transaction, as atomic as the forget that #32 landed.
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
  path with bottom search/count/next/previous/dismiss controls. #86,
  integrated in wave 3, runs find in the page, bound to the document shown; the owner accepted that the page can observe
  the query and the selection
  ([owner decisions](docs/IMPLEMENTATION_PLAN.md#owner-decisions-from-the-pr-triage-22-october-2026)).
  Test Unicode,
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

Since #75, `scripts/check-app.sh` runs every `*Test` class by default,
`ReaderTest` included (Sol S04), and explicit-class runs still work. The fixture
port is still fixed (8766): parameterize fixture ports for isolated simultaneous
sessions rather than sharing a fake service.

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
completed visual/accessibility pass. Exact commands/limits are in
[docs/ENGINE_SPIKE.md section 14](docs/ENGINE_SPIKE.md#14-sol-review-validation-21-october-2026),
landed with #28.

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

Unit-test gaps found by the Muse pass, each cheap and each missing today
(#31's tests now cover retention at claim time for RUNNING rows past the age
limit; #37's `OutboxTest` covers a drain whose storage, claim or finish step
throws still booking its wakeup; #72 and #67 cover the 500-row cap):
`Notice.Problem` ever reaching the snackbar, Recent ordering ties,
`SERVO_ERROR_TITLES` asserted against the real engine on a
device, and one emulator flow of the address sheet: type, receive a #63
suggestion, open it, rotate with the IME up. Dark-mode screenshots of the
finished palette (sheets, checkboxes, switches, chips) are still the missing
visual evidence for the palette work.

## How to check

- `python3 scripts/check_docs.py` and `git diff --check` always.
- Kotlin: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- `native/`: `cargo fmt --check` and `cargo test --lib`.
- A worktree may hold another model's uncommitted files, including
  `native/src/engine.rs` or `ReaderView.kt`. Do not stage them.
- Open pull requests and the changes each needs are listed under
  [Still open](#still-open). Edit them only as listed there or as the owner
  asks, and do not start a second patch for any of them.
- App/engine behavior needs `scripts/build-native.sh` and device fixtures;
  record commands, toolchains, failures and limits in `docs/ENGINE_SPIKE.md`.
  Use the pinned rustup proxy; prepare Servo before host path-dependency tests.
- Set `ANDROID_HOME` to the actual SDK. The Sol run used
  `/home/paseo/android-sdk`; `/home/paseo/Android/Sdk` is only a default location.
- Keep this backlog consolidated; do not start competing implementations. Sol
  S03 landed with #66, S09's palette with #62 (its geometry is still open under
  Chrome) and S17's budget half with #30 (its loading half is correctness
  item 9). S01/S02/S13/S14/S15 landed with #34, #72 and #67, #31, #32 and #35.
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
