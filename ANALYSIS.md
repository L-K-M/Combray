# Combray: outstanding analysis and future work

Consolidated on 7 November 2026 from the existing backlog and the sol-1,
mistral-2, sol-2 and devin-2 source reviews. Completed work and findings
implemented in open PRs are omitted. Open PRs are not claims about behavior on
main. Remaining inherited candidates are
identified as such where their current impact still needs reproduction. The
7 November 2026 mistral-2 review's blocking/important findings are open PRs
(#204, #207, #213, #220, #225, #226, #231) and are omitted per the rule above;
its remaining nice-to-have findings are folded into the sections below. The
7 November 2026 devin-4 review's important findings are also open PRs
(#205, #206, #209, #215, #217, #230, #233, #234, #245, #247) and are omitted;
its remaining nice-to-have findings are folded into the sections below.
Sol-2's important findings are implemented in open PRs #206, #217, #221, #230
and #246. Concurrent PRs #215 and #226/#222 cover its reserved pagination and
query-handoff slices; sol-2 did not duplicate or verify those implementations.
Those findings are omitted too, without implying they have landed on main.

Read [AGENTS.md](AGENTS.md), the [implementation plan](docs/IMPLEMENTATION_PLAN.md)
and [integration evidence](docs/INTEGRATIONS.md) before implementation. Servo is
mandatory; `servo/servo` is source pulls only. Use synthetic fixtures, never
personal history or reported-site data in tests, branches or public reports.
Current engine facts and measured results belong in
[ENGINE_SPIKE.md](docs/ENGINE_SPIKE.md), not the older reviews' Servo 0.6
assumptions. The earlier source reviews remain useful provenance: `SWE2.md`,
`Sol.md`, `GeminiFlash.md`, `fusion.md`, `inkling.md` and `glm.md`.

The sol-1 November audit passed documentation/whitespace checks, 117 host script
tests, and 207 report-service tests on Python 3.13. Each of its four feature
PRs passed failing-first regressions and the full local Kotlin
unit/lint/debug-assembly gate. It performed no native build, device check or
personal-service test: its checkout lacked the disposable Servo build input.
Those limits do not replace the project's existing device evidence.
Sol-2 also passed the Kotlin gates for its changes, 118 script tests, eight
capture-script checks and 169 Rust host tests. It built the pinned native
engine for arm64 and passed targeted capture/search, save and real-Keystore
checks on a fresh API-36 arm64 emulator. Exact commands and limitations are in
its feature PRs. These are not a full browser-suite run, physical-phone results
or deployed-service acceptance; pending CI/reviews remain on the open PRs.
The devin-2 review re-verified the backlog at `3224345`; its blocking and
important findings are open PRs and are omitted, and its remaining nice-to-have
findings are folded into the sections below.
The devin-fable review re-verified the same `3224345` tree. Its important
finding (the remote search panels clearing their results while a search waits)
is implemented in open PR #254 and is omitted; its other open PRs (#257, #259,
#261, #262, #264, #268) implement the matching nice-to-have entries. Its
remaining nice-to-have findings are folded into the sections below.
The longcat-2 review's blocking and important findings are implemented in open
PRs #219, #237, #243, #244, #250, #256 and #267 and are omitted; its remaining
nice-to-have findings are folded into the sections below.
The longcat-1 review's important findings are implemented in open PRs #233,
#241, #260, #263 and #269 (concurrent PRs cover the same findings) and are
omitted; its nice-to-have findings are implemented in open PRs #271, #272,
#274 and #275 and are omitted, except for the remaining outbox transaction
note below.
The 7 November 2026 mimo-2 review's blocking and important findings are
implemented in open PRs #238, #248, #258, #265, #270, #273 and #277 and are
omitted per the rule above; its nice-to-have findings implemented in open PRs
#279, #280 and #281 are omitted too. Concurrent open PRs cover its Karakeep
double-tap guard (#268) and a first slice of its IME and Keystore findings
(#264, #269), noted in the remaining entries. The blocking stale-frame defect
and the
important IME editor-action and media-load attribution gaps have no PR and are
recorded as entries below; the surface-detach wait and the signed release path
were already recorded here and are not repeated. It left the first three
unimplemented rather than ship android-only native or input changes its
session could not build or verify. Its remaining findings are folded into the
sections below. It ran the Kotlin unit/lint gates, `check_docs.py`,
`git diff --check` and the 169 Rust host tests; no native build, device check
or personal-service test.
The 7 November 2026 mimo-1 review's blocking and important findings are
implemented in open PRs #240, #255, #266, #276, #278, #283, #284 and #287,
and in concurrent sessions' open PRs #260, #264, #273 and #275, and are
omitted per the rule above. Three important findings have no PR and are
recorded as entries below: the external-app link activation gap, the
accumulating sign-in controls and the leave-page question's input gaps. It
withdrew its outbox-receipt finding after the queue's own tests showed the
retry-the-cycle behavior is deliberate (see the outbox completion entry).
Its remaining findings are folded into the sections below. It ran the Kotlin
unit/lint gates, `check_docs.py` and `git diff --check`; no native build,
device check or personal-service test.
The 7 November 2026 Argon review passed over the newest code (the PDF viewer,
the native media pipeline, the report service, reload-pull, the user-agent
token and the Web Animations stub) at `3224345`. Its blocking and important
findings are implemented in open PRs #282, #293, #295 and #297 and are
omitted per the rule above; its nice-to-have findings implemented in open PRs
#285, #286, #288, #289, #291, #292, #301 and #302 are omitted too. Its
remaining nice-to-have findings are folded into the sections below. It ran
`node --test native/src/pdf/test/viewer.test.mjs`, `cargo test --lib`,
`cargo fmt --check`, the Kotlin unit/lint gates, the report-service tests and
`git diff --check`; no native build, device check or personal-service test.

The longcat-3 review (7 November 2026, base `3224345`) passed the Kotlin
unit/lint gates, `check_docs.py`, `git diff --check` and the documentation
checks; no native build, device check or personal-service test. Its blocking
and important findings are implemented in open PRs #208, #211, #216, #223,
#224, #229, #235, #239, #249, #294 and #296 and are omitted per the rule
above; its nice-to-have findings implemented in open PRs #299, #300, #303,
#304, #306, #307, #308 and #309 are omitted too. Concurrent open PRs cover
several of the same findings (#219, #225, #230, #242, #244, #250, #269), so
the entries below keep only the unimplemented parts. Its remaining nice-to-have
findings are folded into the sections below.

The longcat-4 review (7 November 2026, base `3387c8a`, with main
advancing during the session) passed the Kotlin unit/lint gates,
`check_docs.py` and `git diff --check`; no native build, device check or
personal-service test. Its blocking and important findings are implemented
in open PRs #222, #227, #232, #251, #253, #298, #305, #310, #311, #312,
#313, #314, #315 and #316 and are omitted per the rule above; concurrent open
PRs cover several of the same findings, so the entries below keep only the
unimplemented parts. Its remaining nice-to-have findings are folded into the
sections below.

The fable-1 review (10 November 2026, base `282d3f8`) read main by subsystem
and by cross-cutting concern on helper models, had each finding checked by a
different model and then against the code. Its two important findings (a
page-chosen title or address too long for Android to read back from the
database, and Forget this site missing tabs that had not loaded their page)
landed in PR #338, and its test and compiler-warning cleanups in PR #339.
Smaller confirmed defects landed in PR #340 (a PDF that arrives after a
back-forward return, find in page counting invisible text, a long-press that
outlives its page), PR #341 (an unbounded Hister search query, text cut
through a character) and PR #342 (a refused link from another app dropped
without a word, the Save to Karakeep dialog outliving its screen). All of
these are omitted. Its remaining findings are folded into the
sections below. It also compared every entry of this document with main: ten
entries whose work had landed were removed, 23 were cut down to what remains,
and statements about open PRs inside entries were brought up to date. It ran
the Kotlin unit, lint and debug-assembly gate, the script, site-check and
report-service tests, the page-script and PDF viewer tests, `check_docs.py`
and `git diff --check`; no native build, `cargo test`, device check or
personal-service test of its own (the Engine workflow ran them for #340).

State of the PRs named above on 10 November 2026: none is open. 71 merged.
39 were closed with the reason recorded on each: 33 were superseded by a
merged PR for the same finding, and six were declined (#207 work-queue
backpressure, #220 a rate cap on page-opened windows, #231 remembered screen
callbacks, #288 a process-wide PDFium inflation cache, #303 a logical focus
clock, #313 an upload pick bound). The focus-order entry below still carries
what #303 set out to do.

## Still open

### Fix the stale-frame defect after hidden-view churn

bugs — blocking

The known M0 defect (docs/ENGINE_SPIKE.md section 6): after several hidden-tab
create/close cycles, navigating the visible tab can leave the surface painting
the old document forever while history, titles and captures report the new
one. A same-size surface rebind (Home and back) does not recover the frame; a
resize such as rotation does. `native/src/engine.rs`'s paint path, `resize`'s
same-size early return (`:901`) and `surface_created` (`:747`) are the current
suspects. Every user on a long session can meet a frozen page.

Suggested fix: run the documented cheap experiment first (force a resize, or
an unconditional `webview.resize` on every `surface_created`) under the
painted-pixel reproduction the acceptance entry names; if that does not hold,
per-tab render contexts or a painter rebuild after view churn. The mimo-2
review could not verify any of it: the change is in android-only native code
and its session had no Servo build or device. Effort: one to two days for the
experiment, one to two weeks for a structural fix.

### Model the keyboard's editor actions in the input pipeline

missing features — important

`EditorActions.kt` maps the keyboard's Next to Tab and Go, Done, Search and
Send to Enter, refusing unknown actions, and `PageEditorInfo.kt` derives
`imeOptions` from the field kind. Two gaps remain: `IME_ACTION_PREVIOUS` is
refused rather than moving to the previous field, and there is no blur or
finish engine command, so Done can only submit; a form cannot be closed from
the keyboard without sending it.

Suggested fix: add engine commands for the previous field and for blur, map
`IME_ACTION_PREVIOUS` to the former, and run device checks over real forms.
The commands are android-only native changes, so they need the native build
and a device run. Effort: one to two days.

### Credit media loads overtaken by a navigation to their new page

bugs — important

A link navigation's first media load can overtake the new document's
`LoadStatus::Started`. With no app request pending, `native/src/engine.rs`
(`media_blocked`, `:2167`) credits the load to the committed document being
replaced, and `BrowserCoordinator.onMediaBlocked`/`recordMedia` then drop it
as a replaced document's report or record it under the old page: a tapped link
to a video page can lose its Play offer, and an offer recorded for the old
page is dead (`findPlayback` rechecks the shown page). The native comment trail
above `media_blocked` documents the race.

Suggested fix: either arm a short post-commit hold in the engine (credit
unattributed loads to the just-committed document) or extend the coordinator's
`heldMedia` mechanism beyond `awaitingCommit` to re-attribute reports for a
recently current document on the next commit; a robust fix is a downstream
signal exposing the load's pipeline. Test link, redirect and same-origin
navigations to media pages with the document and generation guards. Effort:
two to four days app-side, longer with a downstream patch. Found in the
mimo-2 review's engine slice; not implemented in its session. The mimo-1
review adds the sibling gap: `GoBack`/`GoForward` (`engine.rs:616-625`) never
start a media hold while `navigate`, `reload` and `create_tab` do, so a
restored page's first media can go the same way.

### Use engine load failures rather than page titles

bugs — important

`BrowserCoordinator.kt`, `SERVO_ERROR_TITLES`, `onTitle` and capture handling
still recognize failures through the titles "Error loading page" and
"Certificate error". An engine wording change can turn a failure into normal
history, while an ordinary document choosing either title can be suppressed.
This is an inherited source finding, not a new device reproduction.

Suggested fix: carry an identified top-level load-failure signal from the
verified downstream embedding. Until then, pin the actual engine titles with
a device regression and test an ordinary page using the same title. Preserve
failed-load versus committed-document semantics. Effort: one to two days plus
native/device validation.

### Complete composing and surrounding-text semantics

bugs — important

`EngineInputConnection` and the native input commands buffer a live
composition preview, apply `ImeShown.multiline` to the editor configuration,
carry hardware modifiers, bound deletions and decline operations the bridge
cannot represent. The bridge still has no page selection, surrounding-text
or caps channel, so a keyboard cannot synchronize its editor state with the
field.

Suggested fix: establish bounded, field-attributed surrounding-text, caps
and selection synchronization with AOSP/Gboard and CJK/correction/emoji
tests. Keep declining unsupported operations rather than silently clipping
them. Effort: several days with real-engine and keyboard evidence.

### Clear every obsolete page control on document replacement

bugs — important

`native/src/engine.rs` hides only LeavePage and context-menu controls when a
document replaces another; pending alert/confirm/prompt, file, select and
sign-in controls are left to Servo's withdrawal behavior, and
`BrowserCoordinator.kt` retains controls until `ControlHidden`, an answer or
a crash. A missing withdrawal can leave the old question under a new page's
host.

Suggested fix: reproduce a replaced document with a pending select or script
control, then retire or dismiss every obsolete control kind at a
document-identity boundary. Keep prompts belonging to a still-current frame
valid; an equal URL is not identity. Verify delayed responses cannot reach
the replacement page. Effort: about one day plus device checks.

### Make failed surface detach an explicit ownership failure

bugs — important

`native/src/engine.rs` stops painting at once when a surface is destroyed
and keeps the old window bound when the unbind fails, retrying it before the
next bind. But `Command::SurfaceDestroyed` still acknowledges completion
unconditionally, so `native/src/jni_api.rs::send_and_wait` reports a
successful detach after a failed unbind and the next surface binds while the
old window may still be referenced by EGL. The bounded UI-thread
acknowledgment wait still stalls rotation and backgrounding while the engine
thread is busy, an ANR risk, and no failure-injection test covers the path.

Suggested fix: acknowledge actual release, do not bind a replacement while
the unbind retry fails, and land lifecycle-command prioritization so surface commands are not queued
behind input (PR #218 tried an urgent channel and was closed: a normal
batch could consume its wakeup). Test timeout, failed
unbind, rapid background/rotation and paint after rebind. Do not weaken
`SurfaceHolder.Callback`'s lifetime contract just to shorten a wait.
Effort: one to two days with native/device proof.

### Fail startup when the profile directory cannot be created

bugs — important

`native/src/engine.rs::start_servo` emits an error after `create_dir_all` fails
but continues starting Servo. The engine can then browse without reliably
persisting cookies/HSTS. The adjacent saved-sign-in cleanup failure also has
a documented proxy-auth retention consequence; keep that distinction from a
fatal profile-directory failure.

Suggested fix: stop startup for an unusable profile and publish an actionable,
redacted failure, without deleting the logical session. Verify unwritable
storage, retry and ordinary profile persistence. Effort: about half a day plus
native/device validation.

### Bound Reader acquisition before cloning the DOM

performance problems, stuttering, and jank — important

`native/src/reader.js:38` clones the whole document before Readability checks
`maxElemsToParse`. Its inline/block walks also recurse by nesting depth. A
large or deeply nested page can allocate heavily or throw before the output
budget helps. A result timeout does not interrupt JavaScript; foreground
capture's later time budget is not a hard Reader execution bound.

Suggested fix: establish an acquisition/node/depth bound before cloning and
use iterative walks or an explicit depth cap. Measure ordinary and hostile
Readability separately. Prefer a trusted or interruptible engine path when
available; do not trust page getters or a page-controlled clock as hard
execution limits. Effort: one to two days with native and device fixtures.

### Bound page-control acquisition before serialization

performance problems, stuttering, and jank — important

`native/src/engine.rs` builds select-option/optgroup and dialog descriptions
before `controls.rs` truncates the JSON. A large picker can therefore allocate
another complete options tree on the engine thread before its output cap is
applied. This is the remaining Sol S20 acquisition concern, not a request to
remove the already implemented wire bounds.

Suggested fix: cap acquisition, selected IDs and messages before cloning or
serializing them. Exercise a synthetic 100,000-option control and verify
responsive rejection or bounded presentation, correct answers and accessible
feedback. Effort: about one day plus native/device checks.

### Distinguish service accounts sharing one root

general issues — important

Service fingerprints identify roots, not accounts. Replacing credentials for
another account on the same Karakeep/Hister server can reuse local Saved state
or explicitly resume jobs whose account identity was never established.
Canonicalizing URL spellings (PR #311) is a separate problem. Key rotation cannot be inferred to mean account replacement.

Suggested fix: inspect the deployed read-only identity contract or introduce
an explicit account-replacement operation that isolates old state. Do not
invent an endpoint or use token hashes as account IDs. Plan a preserving
migration and test two fixture accounts on one host, same-account rotation,
status separation and old-job resumption. Effort: one to two days after the
server contract/owner decision is available.

### Bound long-lived document and cancellation maps

performance problems, stuttering, and jank — important

`HisterIntegration.forgotten`, `LiveView.documentVisits` and native
document/retired maps (`native/src/engine.rs:225-227`) can grow through
repeated navigation/forget loops. A
six-live-renderer cap does not bound documents in one long-lived tab. The
inherited growth claim needs current measurements. The concurrent Devin-1
review corrected the pending capture/article claim: native deadlines emit a
result regardless of view state, and the coordinator removes the entry on its
first result, so those maps are bounded by in-flight requests, not a known
crash-lifetime leak.

Suggested fix: measure controlled navigation and forget loops, then retire
identities only after proving back/forward attribution, stale-result rejection
and forgetting remain correct. Do not prune still-needed document identities
merely by URL or arbitrary age, or add another pending-request mechanism for
the already bounded maps. Effort: one to two days plus lifecycle regressions.

### Define and test the native listener exception boundary

bugs — important

`native/src/jni_api.rs::JavaListener.emit` invokes Kotlin synchronously on the
engine thread. A thrown listener/parser exception must not leave JNI in an
exceptional state that poisons later delivery or strands the engine. The
inherited review also notes parsing/copying of 256/512 KiB capture events on
that thread; no current failure injection or timing evidence establishes the
boundary's behavior.

Suggested fix: test a throwing listener followed by a valid event against the
pinned JNI implementation, explicitly handling pending exceptions and owner
lifetimes. Any off-thread parsing requires a bounded, ordered event queue and
preserved generation/document tags. Keep payloads and exception-quoted page
text out of logs. Effort: about one day plus native/device checks.

### Make page reading accessible beyond native chrome

missing features — important

Native target/label tests cover portions of Combray's chrome, not an Android
accessibility tree for rendered page content. Blind users need page navigation
and an accurate editor state, not just labeled toolbar buttons. The inherited
AccessKit APIs are leads, not proof of a working Servo-to-TalkBack bridge.
`EngineSurfaceView.onCheckIsTextEditor` follows the page field's keyboard
ownership (PRs #244 and #312); that is chrome state, not page accessibility.

Suggested fix: investigate the pinned downstream accessibility interfaces and
verify actual TalkBack reading, focus, links, fields, document replacement and
keyboard ownership. Never claim chrome semantics establish page
accessibility. Effort: several days of engine investigation and device tests.

### Restore page copy and manual text selection

missing features — important

The implementation plan records the owner's unresolved Copy/manual-selection
report in [downstream issue #168](https://github.com/L-K-M/Combray/issues/168).
Address copying and page-field Paste are already implemented; neither proves
copying selected page text works. The existing clipboard delegate is only a
candidate integration point.

Suggested fix: reproduce with a synthetic selectable document and a
user-invoked Copy button, then add an identified, permission-appropriate
clipboard/selection path without fighting pinch or long-press link menus.
Verify actual clipboard contents and physical gestures; keep credentials and
page text out of diagnostics. Effort: several days with native/device proof.

### Establish a usable signed release path

general issues — important

The release workflow still lacks the complete native/signing publication path;
a Kotlin-only APK is not a usable Servo browser. Local signing with a
disposable key and packaging checks already work. The owner's persistent key
and CI signing arrangement remain pending, as recorded in M4.

Suggested fix: wire native arm64 compilation and verified signing into the
release path, obtain signing material outside Git, and verify a clean install
of the exact signed, engine-bearing artifact. Check ABI/ELF and applicable
16 KiB page-size compatibility, packaged notices and exact engine source.
Never cut a release tag merely because Kotlin CI is green. Effort: one to two
days plus the owner's signing setup.

### Finish real-device and deployed-service acceptance

general issues — important

Fixture acceptance does not establish the owner's keyboard, physical GPU,
essential sites, Kagi login/search/cookie persistence or actual
Karakeep/Hister authentication/namespace. The original stale-frame reproduction
compares painted pixels after view churn/navigation; a matching URL alone
cannot close it. The plan keeps physical-phone and deployed-service evidence
explicitly open.

Suggested fix: with authorization, repeat paint-after-churn/eviction,
background/rotation, input, pinch, dark/landscape/large-text and startup tests
on arm64. Perform one deliberate service save/capture and verify the unique
rendered phrase through search, then clean up the fixtures. Never read/export
personal history to validate this. Establish actual service versions, path
prefixes, TLS/network reachability and intended Hister user scope. Effort:
several days once device/configuration access is available.

### Tie external-app link activation to its document

bugs — important

`native/src/engine.rs:1510-1519` (`take_activation`, call site `:2258`)
discards the document its activation was recorded for, while the sibling
pop-up path (`take_document_activation`, `:1525-1531`) requires it "as
browsers tie activation to the document". A tapped link whose destination
then navigates on to `mailto:`, `tel:` or `intent:` (script or meta refresh)
can hand the link to another app with no tap on the new document;
`page_navigation`'s Allow branch never consumes the activation, so a stale
tap survives into the next document.

Suggested fix: require the recorded activation's document to be the view's
current document in `take_activation`, mirroring `take_document_activation`,
and cover link-then-redirect and link-then-refresh to external schemes in the
delegate's own tests where possible. Effort: minutes of code plus the native
build and device verification this session could not run.

### Bound unanswered sign-in controls

bugs — important

`native/src/engine.rs:2283-2315` gives every HTTP 401/407 `SignIn` control a
slot with no deadline (`ControlHandle::deadline` returns `None`, `:401`) and
no cap, and the new-document cleanup (`:2627-2634`) drops only
`LeavePage`/`ContextMenu`. Servo emits `RequestAuthentication` for every
navigation-mode load, iframes included, so a page with many auth-protected
frames queues a sign-in dialog per frame, growing the engine's `page_controls`
and the app's `view.controls` until the tab closes. The coordinator's dialog
cap covers only alert/confirm/prompt.

Suggested fix: give `SignIn` the `LeavePage` treatment — a deadline and/or a
per-view cap with auto-dismiss — and hide stale ones on `NewDocument`. Effort:
about an hour of native code plus the device verification its session lacked.

### Guard unsaved form text the leave-page question misses

missing features — important

The pinned engine's `beforeunload` question covers only navigations the page
starts (the plan's leave-page row): a typed address, Back/Forward and closing
a tab still lose unsaved form text, so composing in a web editor is unsafe
against the app's own navigation. Nothing honest app-side can close the gap.

Suggested fix: pursue the engine change in the downstream checkout (ask on
embedder-started navigations too, or expose an "unsaved form" hint the app
acts on) and keep the gap documented until device evidence says otherwise.
Effort: an engine change, days plus a device run.

## Nice-to-have

### Reconcile stale milestone and toolchain summaries

general issues — nice-to-have

SOL2-11: `docs/IMPLEMENTATION_PLAN.md:3,470` still describes the engine as
"patched Servo v0.6.0" while the pinned downstream is the `combray/0.7`
line, `:466` still counts two open engine blockers and `:504` still says
Stop is missing although Stop landed in PR #179. README's status line and
`docs/BUILDING.md`'s toolchain table are current. Historical runs remain
useful evidence, but present-tense summaries can misdirect implementation
and release assessment.

Suggested fix: reconcile the plan's summaries with the pinned source and
dated run evidence. Preserve what was and was not tested, including fixture
versus deployed services and emulator versus physical phone; do not
manufacture a new acceptance claim from documentation cleanup. Effort:
about half a day.

### Validate safe upload names and resource policy

bugs — nice-to-have

`app/src/main/kotlin/ch/lkmc/combray/UploadStaging.kt` limits names to 120
UTF-16 characters, not the filesystem's UTF-8 component limit, so long CJK
or emoji names can still fail. Staging refuses a pick that would leave less
than a 512 MiB free-space reserve and rolls back a failed pick whole, but
copies from unknown-length providers are still streamed to completion, kept
until the next process start, and no total byte budget bounds one pick. No
page-to-JNI arbitrary filesystem-path capability has been demonstrated.

Suggested fix: bound filename bytes on code-point boundaries while
preserving a useful extension. Agree total-pick budgets and cancellation
behavior, and enforce them while streaming unknown-length providers. Release
a copy only once Servo no longer needs its path.
Verify traversal/symlink/staging-root boundaries where actually exposed,
duplicate names, repeated large picks, cancellation and disk-full without
deleting active attachments. Effort: about half a day.

### Order focus changes monotonically

general issues — nice-to-have

`BrowserCoordinator.activate` writes `now - 1` to the tab losing focus, and
`retireVisit` does the same for the tab being left, so two focus changes in
the same millisecond get identical values and a tab focused at exactly `now`
is rewritten to a smaller one. `BrowserDao.openTabs()` and `observeOpenTabs()`
order by `lastFocusedAt` with no tiebreaker, so Recent order and
`closeLogically`'s successor choice become rowid-dependent when links open
fast.

Suggested fix: write `maxOf(old, now - 1)` and add a deterministic tiebreaker
(id) to the ordering queries. Test two same-millisecond focus changes.
Effort: about half a day.

### Report oversized HTTP responses explicitly

general issues — nice-to-have

`core/integration/HttpTransport.kt:87–99` reads one byte beyond the response
cap but returns only the prefix, expecting a parser to reject truncation.
A valid JSON prefix plus a large suffix can instead appear valid, and normal
oversized responses lose their useful size-failure diagnosis.

Suggested fix: return an explicit bounded-response failure/category and map
it consistently in the clients. Preserve memory limits and redirect/credential
isolation. Test malformed and valid-prefix oversized responses through a local
server. Effort: about half a day.

### Page long-lived failed-save queues without discarding actions

performance problems, stuttering, and jank — nice-to-have

`IntegrationDao.observeOpenJobs` returns every unfinished row (PENDING,
RUNNING, RETRY_WAIT, AUTH_REQUIRED and FAILED), and Settings renders them
non-lazily inside a scrolling Column. The per-row payload decode is off the
UI thread (PR #242) and rows carry a persisted `url` column (PR #298); the
remaining cost is the non-lazy rendering of a large
queue. No phone frame-time measurement is claimed. Deliberate failed saves
are unbounded and must not be silently expired to hide the cost.

Suggested fix: measure page/lazy rendering of large queues, retaining
Retry/Discard and destination binding. Keep explicit saves until the user
resolves them. Effort: about one day including responsiveness checks.

### Explain failed automatic captures' budget retention

bugs — nice-to-have

FAILED automatic capture bodies remain in the seven-day transient budget, so
accumulated permanent failures can pause capture of other pages. The live
budget warning and replacement-aware transactional admission are already
implemented, the latter in PR #206; neither needs another implementation.
The remaining question is the intended treatment and explanation of failed
automatic text, not the admission arithmetic.

Suggested fix: confirm and document that retention policy, explaining how
Retry, Discard and expiry release the budget. Cover failed-body expiry and
warning recovery without another capture, without silently discarding explicit
saves or reopening the fixed replacement bug. Effort: about half a day after
the policy is chosen.

### Normalize a report address's trailing slash

bugs — nice-to-have

`ReportAddress.siteAndPath` trims to the site and path but keeps a trailing
slash on the bare host: `https://example.org/` and `https://example.org` trim
to different strings, and a scheme-less `example.org/` keeps its slash. The
service treats both as the same site, so the preview can show a different
address than the user gets by restoring the full address.

Suggested fix: drop a trailing slash on the bare host (or document the choice)
and add a `ReportAddressTest` case. Effort: under an hour.

### Define a total deadline for integration HTTP attempts

general issues — nice-to-have

One dispatcher per process serializes drains, and fenced claims already
protect local transitions. Connect/read timeouts are not an overall request
deadline: DNS or a slow trickle can exceed the lease. A longer lease alone
only delays crash recovery; client serialization cannot promise exactly-once
or remote latest-wins after ambiguous completion.

Suggested fix: test a slow live request, expiry/takeover and the stale late
answer. Use an appropriate total deadline or renewal/reconciliation while
preserving fences, idempotency and the documented Hister remote-order limit.
Retain newest payloads where required and count ambiguous attempts
conservatively: counting only returned failures can permit endless resends
after repeated response loss. Effort: one to two days.

### Run outbox completion side effects after the commit

general issues — nice-to-have

`Outbox.finish` runs its `onDone` callback inside the Room transaction, so a
side effect that throws rolls back an already-applied job state and the
dispatcher retries it. `Integrations.recordDone` decodes its payload
defensively (PR #230), which closes the known throw path, but the
transaction boundary remains: any future callback that writes to the database
or throws will still couple its fate to the finish transaction. The mimo-1
review withdrew the stronger version of this finding: `OutboxTest`'s
`oneBrokenJobCycleDoesNotStrandTheRestOrTheWakeup` deliberately locks the
rollback-retry behavior, and `dispatchSave`/`recordDone` decode the same
payload, so the known decode throw cannot follow a successful send. What
remains is exactly the boundary above.

Suggested fix: run `onDone` after the transaction commits, or document that
callbacks must be idempotent and non-throwing. Test a throwing callback and a
callback that writes, verifying the job state survives. Effort: about half a
day.

### Handle runtime refusals without request identity

general issues — nice-to-have

`BrowserCoordinator.kt:1503–1512` ignores a tab-scoped refusal without a
request ID once the view has a generation. Such a runtime refusal can leave
the coordinator believing an unusable view is still on screen. The remaining
finding is this identified error-state edge, not startup idempotence,
Error/cancellation handling or wedged-step recovery, which are implemented.
Bounding the queue with backpressure was declined (PR #207): a full queue
would block the UI and engine threads that submit work.

Suggested fix: reproduce a refusal for a live generated view without a
request ID and define the correct recoverable state. Preserve the rule that
an old request refusal cannot clear a newer navigation's wait, and do not
make benign runtime engine notices fatal. Effort: about half a day.

### Fence a rolled-back forget against stale tombstones

bugs — nice-to-have

Forgetting shares a transaction with cancellation, `pageForgotten` keeps an
open tab's forgotten page out of history and capture, and the capture timers
are fenced per page. The remaining case: `forgetHost`/`forgetUrl` write the
in-memory tombstone and bump the policy generation before the surrounding
Room transaction commits, so a rolled-back forget leaves a tombstone that
still cancels that site's pending captures.

Suggested fix: reproduce the rollback case before adding another tombstone
mechanism, then fence or roll back the tombstone and the generation with the
transaction. Verify no erased content is re-enqueued after restart. A direct
concurrent sink test is not proof of a normal coordinator Forget race.
Effort: about half a day.

### Document WorkManager replacement behavior

general issues — nice-to-have

`WorkManagerScheduler` books its unique work with
`ExistingWorkPolicy.REPLACE`, and the rationale is undocumented: booking a
new wakeup while the unique worker is returning can cancel that finishing
worker; the process-wide dispatcher mutex currently makes this benign. The
database, not WorkManager, already remains the source of truth.

Suggested fix: document the rationale and test final-drain/wakeup races
before introducing multiple dispatchers or changing to append semantics.
Effort: a few hours.

### Measure touch backlog and preserve final pointer coordinates

performance problems, stuttering, and jank — nice-to-have

Trace `EngineSurfaceView.kt`, `native/src/commands.rs` and the engine loop:
rapid multi-pointer input can queue work, and the loop processes a bounded
batch per turn. The inherited coalescing proposal has no current measured
input-latency reproduction. A separate urgent channel for lifecycle commands
was tried and closed (PR #218): a normal batch could consume its wakeup and
leave the command queued.

Suggested fix: measure before adding throttles. Do not coalesce input touch
positions: `engine.touch` feeds tap and touch-quiet trackers, and dropping a
drag-out-and-back path can manufacture a false tap. The concurrent Devin-1
review confirms lifecycle-priority work instead deduplicates Wake per batch.
Any future render-only coalescing must retain every point for gesture/activation
trackers, deliver final coordinates and preserve down/up/cancel order. Keep
the existing long-press clock rule: measure from app handling, not the queued
event timestamp. Effort: about one day with real touch fixtures.

### Publish the player snapshot only when it changes

performance problems, stuttering, and jank — nice-to-have

`PlayerActivity.startTicker` publishes a new `PlaybackSnapshot` every 250 ms
while playing. The on-screen position is Media3's own controller; the snapshot's
position is read only by the media device checks, so the StateFlow is updated
four times a second for the whole playback while nothing collects it.

Suggested fix: publish on phase/track changes plus a slower position update
(for example one second), or let the device checks poll a cached position.
Verify the media device checks still observe the position within their timeout.
Effort: about one hour.

### Profile Recent ranking and database query plans

performance problems, stuttering, and jank — nice-to-have

Recent can re-sort after flow emissions; search now correctly covers retained
history and caps after filtering. `BrowserCoordinator.recent()` combines and
sorts up to 5,000 rows without `flowOn(Dispatchers.Default)`, unlike `topSites`
and `continueTab`, so its transform runs in the UI collector's context; the
other projections already move this work off the main thread. Home's top-sites
flow likewise parses,
sensitivity-checks and groups every retained visit on each emission and each
cutoff tick, then caps the tiles at six. Address suggestions rank parsed hosts,
and several DAO paths have possible index/N+1 costs: history filtering/order,
open tabs by state/time, open jobs by service, host/subdomain deletion,
Hister eligibility/exclusion reads. `releaseViewsBeyond`'s per-view primary-key
reads are bounded by the live-view cap, about seven reads per reconcile, not
by open-tab count; the concurrent Devin-1 review correctly identifies those
as negligible rather than an unbounded N+1 problem.

Suggested fix: run EXPLAIN QUERY PLAN and timing on thousands of good/failed
visits and jobs. Add composite indexes or cache genuinely hot projections
only with measured benefit, preserving migrations and deletion semantics.
The candidate `visits(failed,lastSeenAt)` index is not automatically better
than existing indexes. Effort: one to two days.

### Take only the Home tiles the row still needs

performance problems, stuttering, and jank — nice-to-have

`HomeLinks.kt:88–100` maps every eligible site to a `HomeLink` before taking
`HOME_LINK_LIMIT`, even when pins already fill the row. Large histories cause
unnecessary allocations off the main thread on every ranking emission; no
visible jank is established.

Suggested fix: take only the remaining slots with a lazy sequence, preserving
ranking and suppression semantics; profile ranking separately before broader
changes. Effort: about two hours.

### Measure startup, live views, memory and power on the owner's device

performance problems, stuttering, and jank — nice-to-have

Historical SwiftShader/v0.6 evidence included about 141 MiB for two small
tabs, 156 MiB for ten, and 840 MiB after roughly fifteen cached documents plus
two large pages; capture and shutdown were also slow in those runs. These
are not current phone budgets. The deadline-driven engine sleep is already
implemented, so the old 250 ms polling fix is no longer a task. Keystore key
loading is lazy, not an unconditional Application-start operation.

Suggested fix: measure cold/warm chrome, resident/evicted switches, 1/10/30-tab
memory, scroll frame times, idle/background CPU, thermal/battery and queue
completion. Tune the six-live-view default only from that evidence. Verify
what throttling actually does on the pinned engine instead of adding another
copy of the existing visibility hint. Effort: one to two days on the device.

### Handle native-view eviction's transient-state loss clearly

usability: fast, convenient, clear — nice-to-have

Evicted tabs restore their last URL/title, not native back stacks, scroll,
forms or POST state. The loss is documented for developers
(docs/IMPLEMENTATION_PLAN.md, `BrowserCoordinator.kt`), but nothing tells the
user and no bounded scroll seam exists, so the six-view cap and
memory-pressure eviction have a visible cost even though persistent logical
tabs are correct. Full Servo session export/restore is not established.

Suggested fix: measure the loss on device, consider a bounded scroll seam
only where verified, and warn only for known transient state at risk. Never
reconstruct sessions by replaying POST/network history or inventing a native
session format. Effort: about one day for clear recovery UX, longer for new
engine capability.

### Measure profile flush and density-change stalls

performance problems, stuttering, and jank — nice-to-have

Profile fsyncs run on the engine thread every five minutes and when leaving
the foreground. Startup density is also retained by the process-scoped engine.
The inherited review requests evidence, not a demonstrated current fling
stall or display-size failure.

Suggested fix: trace profile writes during scrolling and exercise a display
size/density change within the same process. Move work only if Servo's
ownership and persistence guarantees permit it. Effort: about half a day.

### Fail or back off persistent native rendering errors

general issues — nice-to-have

`native/src/engine.rs::paint` keeps `needs_paint` after `make_current` fails,
so subsequent wakeups can keep retrying/logging. Pre-start/shutdown command
spam can also flood errors. The old fixed 4 Hz polling assumption no longer
applies after deadline-driven sleep.

Suggested fix: reproduce the current failure cadence, back off persistent
errors and publish one useful state transition rather than flooding logcat.
Preserve a recoverable paint request and verify a later valid surface paints.
Effort: about half a day plus native/device checks.

### Close the losing native startup thread on publication races

bugs — nice-to-have

`native/src/jni_api.rs` spawns an engine before publishing its sender in
`OnceLock`. A losing concurrent caller can leave a thread whose own waker
keeps its channel alive. Production startup is serialized today, so this is
an inherited API-edge candidate rather than a demonstrated user failure.

Suggested fix: test concurrent singleton publication before adding startup
callers, and stop a losing thread explicitly without disturbing the winner
or JNI listener lifetime. Effort: about half a day.

### Define overflow semantics for held native reports

general issues — nice-to-have

`native/src/engine.rs::Tab::hold` silently drops the oldest item at
`MAX_HELD_REPORTS`. In the current source these are title/load reports, not
capture-result payloads as an older review described. A document that does
not commit promptly can lose intermediate reports.

Suggested fix: establish which reports must survive, then coalesce safely or
emit a bounded explicit overflow outcome. Verify title/loading state after
a burst and preserve document attribution. Effort: about half a day.

### Cache extraction templates only after measuring

performance problems, stuttering, and jank — nice-to-have

The native capture path expands a large script template for requests. The
earlier claim that every HttpURLConnection request necessarily pays a new
TCP/TLS handshake also needs measurement against actual pooling behavior, not
assumption. `HisterClient.boundedBody` cuts once per request (PR #256).

Suggested fix: benchmark template expansion, serialization and repeated
requests with synthetic payloads; cache stable script parts where useful. Any
connection reuse must preserve no-redirect, credential isolation, response
caps and destination identity. Effort: about half a day to measure, then a
bounded optimization.

### Measure capture's worst-case responsiveness

performance problems, stuttering, and jank — nice-to-have

`native/src/capture.js` checks its time budget between groups of 128 nodes,
and work within one node (a huge text node's whitespace normalization, a
page-defined getter) is not clocked. The documented time budget therefore does
not bound a single huge node. This is an acknowledged engine limitation, not a
new measured freeze.

The walk's worklist is bounded per element only: each element may push up to
the remaining node budget, and the budget is spent when a node is popped. A
real tree pushes each node once. A page that replaces `childNodes` with a
getter returning the same fake element many times can grow the list far past
the node budget, which the comment above the push says cannot happen. It
gains nothing by that: its own script can use up the engine's memory
directly (docs/ENGINE_SPIKE.md, one process).

Suggested fix: benchmark synthetic huge-node and style-heavy pages with
frame/input timing; bound preprocessing where it helps and pursue interruption
only in the downstream engine. Do not claim a callback timeout stops
JavaScript. For the worklist, spend a total push budget of about twice the
node budget and end the walk as the node budget does, with a case in
`native/src/test/capture.test.mjs`. Effort: about one day for evidence,
further work depends on the result; the push budget is an hour.

### Offer retry and settings actions for a failed search

usability: fast, convenient, clear — nice-to-have

`HisterSearchPanel.kt` and `KarakeepPanel.kt` show an error notice for a failed
first search but offer no direct action: after reconnecting, the user must edit
the query or reopen the scope, and an authentication failure has no shortcut to
repair it.

Suggested fix: add Retry for transient failures and Settings for
authentication/configuration failures, keeping existing results during a failed
later page. Effort: about half a day.

### Distinguish configuration loading from not configured

usability: fast, convenient, clear — nice-to-have

Hister/Karakeep panels initially collect a nullable service as null, so a slow
first emission can briefly show setup copy even for a configured server.
The panels already offer Settings actions; adding that action again is not a
task.

Suggested fix: represent initial loading separately, then show setup only
after a confirmed absent configuration. Test a deliberately delayed configured
emission. Effort: a few hours.

### Verify the wrapped Recent scope chips

visual issues and layout problems — nice-to-have

`RecentSheet.kt`'s scope chips wrap onto a second line in a `FlowRow` with
single-choice tab semantics, so narrow widths no longer hide the Hister and
Karakeep scopes outright. No 320dp/200%-type measurement exists, and
duplicate tabs, the last row and pagination with the keyboard up are
uncovered. Date headers, active/open markers, host tiles and times already
exist; this is not a request to rebuild Recent.

Suggested fix: verify at 320dp/200% type, keeping single-choice TalkBack
semantics and local/remote scope clarity. Cover duplicate tabs, the last row
and pagination with the keyboard up. Effort: about half a day.

### Retain each remote scope's query and paging

usability: fast, convenient, clear — nice-to-have

`RecentSheet.kt` composes `HisterSearchPanel` or `KarakeepPanel` conditionally,
so each panel owns its query and paging state. Switching from Hister to Karakeep
and back recreates the panel from the local filter rather than the remote query
the user was refining. Open PRs (#222, #226, #235, #252) already keep a seeded
local filter from being sent to a scope the user has not selected; the
retention itself is not addressed.

Suggested fix: retain per-scope query and scroll position for the sheet's
lifetime and invalidate results on service identity changes. Effort: about one
day.

### Verify Settings and Recent layouts at narrow widths

visual issues and layout problems — nice-to-have

The Settings Save/Check connection row wraps in a `FlowRow`, so the row
overflow at large fonts is no longer open. What remains is evidence:
Settings and Recent have no independent bounds and reachability
measurements. Scrolling Home is already implemented. An unweighted
LazyColumn alone does not prove clipping.

Suggested fix: measure 320dp, 200% type, landscape and keyboard-visible
states before changing layout, keeping the wrapped row's 48dp
non-overlapping touch targets and verifying its wrapped labels with long
translations. Check last rows, page actions and snackbar bounds; do not add
blanket modifiers. Effort: about half a day of Settings/Recent
accessibility coverage.

### Preserve readable origin under constrained layouts

visual issues and layout problems — nice-to-have

Start ellipsis preserves a suffix, not necessarily a complete registrable
domain. Address/chrome target tests do not prove that long IDNs, subdomains,
RTL and large text leave the site's identity understandable. Search result
rows (`KarakeepPanel.kt`, `HisterSearchPanel.kt`) clip long hosts at the row
edge with no ellipsis, unlike their titles.

Suggested fix: inspect actual rendered origins at 320/360dp, 200% text, long
hosts and large counts. Keep engine-derived failures/cleartext indications,
not a decorative verified lock; Page Info must not infer certificate
verification from HTTPS. Give search result hosts a deliberate overflow
(ellipsis or a second line) and retain the complete accessible label. Test
0/1/99/100 badge boundaries and exact TalkBack counts without changing
logical tab state. Effort: about half a day.

### Cap deep Reader list indentation

visual issues and layout problems — nice-to-have

`ReaderView.kt` adds 16dp of start padding per list level before the marker and
text. Nested instructions become narrow on phones and at larger type; no
screenshot defect is claimed.

Suggested fix: prototype a width-aware indentation cap while keeping semantic
nesting and ordered markers; compare narrow, RTL and large-type examples.
Effort: about half a day.

### Verify the remote search fields' Search action

usability: fast, convenient, clear — nice-to-have

The local Recent filter uses `ImeAction.Search` and puts the keyboard away,
and the Hister and Karakeep fields submit through their Search action. That
remote behavior is not deliberate or verified: Android Back and focus
behavior with nested sheets is untested.

Suggested fix: wire remote Search deliberately without bypassing
privacy/submission rules. Verify Android Back and focus behavior with
nested sheets. Effort: a few hours plus UI/IME tests.

### Improve failure copy and save receipts

usability: fast, convenient, clear — nice-to-have

Save receipts persist across restarts, and the Save button distinguishes
Pending, Waiting, Saved, saved-with-list-failure and Needs attention in
words as well as color. Settings queue rows still print safe but technical
categories such as `http_401`, and coordinator problems are hardcoded
English strings in core. A new unexpected-response category could improve
proxy/captive-portal copy, but `WrongAddress` intentionally includes
reachable non-service responses; that naming suggestion is not a
connection-check correctness bug.

Suggested fix: map categories to localized recovery actions, preserve the
original payload and destination through switching/response loss, and
localize coordinator problems through typed notices mapped to app resources,
not resource IDs in core. Effort: about one day.

### Let audio play with the screen off, or say it stops

usability: fast, convenient, clear — nice-to-have

`PlayerActivity` keeps the screen on only while video plays
(`onIsPlayingChanged` sets `keepScreenOn = isPlaying && hasVideo`), and
`onStop` releases the player. A long audio track therefore stops when the
screen times out, a pocket-listening interruption. docs/VIDEO.md records the
C1 rule of no background audio, but the player never tells the user that
this is why audio ends, and no owner decision on background audio is
recorded.

Suggested fix: decide the policy. If background audio is wanted, keep the
screen on for audio too or add a foreground media session (an owner
decision; do not add one silently). If it is not, say so in the player.
Effort: 30 minutes for the flag or a line of copy.

### Give address and state changes clear accessibility context

usability: fast, convenient, clear — nice-to-have

The address editor is a pane titled "Address or search" while editing and
its rows say what each does, the toolbar menu carries a pane title, and the
engine/reader overlay messages are polite live regions. No TalkBack
announcement evidence exists for any of them, and load starts have no live
region: the overlays cover engine/session/crashed states only.

Suggested fix: exercise TalkBack focus and announcements on a device for
the address pane, the engine starting/missing/failed/crashed overlays and
load starts. A crash or changed loading state must not require manual
exploration, and routine progress must not become announcement spam.
Effort: about half a day with TalkBack.

### Verify keyboard, focus and inset stability on real windows

visual issues and layout problems — nice-to-have

Address focus is requested from `LaunchedEffect`. Since 9 November 2026 the
address editor is in the main window, not a sheet: its keyboard animation and
the held engine surface were checked frame by frame on an API 36 emulator, but
IME resizing and snackbar clearance still need physical-device evidence.
Existing main-thread state collection, inline Home entry, page Paste/Back
handling and measured bottom-bar padding should not be replaced based on the
older review's assumptions.

Suggested fix: exercise address open/type/suggestion selection/rotation with
IME up, page autofocus while the address editor owns focus, keyboard Back and
reopen, and nested-sheet dismissal. Measure bar/snackbar bounds at large font
sizes before adding animated insets, respecting reduced motion. Effort: about
half a day on-device.

### Adopt a deliberate sheet spacing and surface rule

aesthetics and theming — nice-to-have

Address/Recent commonly use 16dp gutters and Settings 24dp, with menu/list
insets differing again. The design reference proposes distinct panel
surfaces, softly tinted chips/pressed rows/tiles, 12dp field/chip geometry,
23dp sheet tops and a restrained title/row/meta scale. These are proposals,
not a confirmed mandate to override native Material geometry.

Suggested fix: document an intentional compact/reading/form spacing rule and
compare synthetic light/dark states before selectively aligning related
surfaces. A quiet Reader paper tone or accent loading line can be tried
without a wholesale UI reformat. Effort: about half a day.

### Validate the palette as displayed, not only calculated

aesthetics and theming — nice-to-have

`ThemeContrastTest` checks text/outline pairs, but that does not establish
hierarchy, disabled/errors, chips, checkboxes, dialogs, Reader or system bars
on a physical screen. The pine/ivory light and charcoal dark schemes and
Follow system/Light/Dark setting already exist. Dynamic-color default and a
pitch-black variant were declined by the owner.

Suggested fix: record a synthetic screenshot/physical-device matrix with
Recent, Settings, Reader, notices and errors in both appearances, landscape
and 200% type. Document measured tokens/contrast in the design reference if
freezing them. Preserve the icon's palette and square source. Effort: about
half a day with a device.

### Add an intentional monochrome launcher mark if wanted

aesthetics and theming — nice-to-have

`app/src/main/res/mipmap-anydpi/ic_launcher.xml` lacks a monochrome layer.
Android themed-icon users therefore lack a matching monochrome Combray mark.
The original source artwork is already square, opaque and edge-to-edge.

Suggested fix: generate and visually test a separate monochrome asset if the
owner values it, including platform tinting. Never bake launcher masking or
rounded outer corners into `media-sources/icon.png`. Effort: about half a day.

### Make destructive history actions understandable and reversible

usability: fast, convenient, clear — nice-to-have

Forgetting a site asks for confirmation and states its local-versus-remote
scope, visit removal is a deliberate tap, and the close Undo restores
whether the closed tab was focused. Two gaps remain: the Undo snackbar says
only "Tab closed" instead of the closed tab's bounded title with an untitled
fallback, and removing a Hister exclusion is immediate, with no confirmation
or explanation that it can enable future capture. A short-lived Forget undo
would also have to restore affected capture state atomically, not just
paint the row back.

Suggested fix: use the available bounded title in Undo close, and give
exclusion removal a truthful scope confirmation and useful empty-list
guidance. Keep explicit close-and-forget semantics. Effort: about one day;
never claim an already delivered remote capture can be unsent.

### Offer bulk tab actions without splitting Recent's mental model

missing features — nice-to-have

Recent has individual close buttons, swipe-close and Undo, but no Close all
or Close other tabs. A long session can require repetitive cleanup. History
and open tabs must remain one product with distinct record semantics.

Suggested fix: offer deliberate bulk actions with recoverable or confirmed
scope, retaining history and exact tab identities. Optional grouping by tab
or finer time buckets such as this morning remains a projection, not a second
history database. Effort: about one day with close/undo/process-death tests.

### Make history retention configurable if useful

missing features — nice-to-have

Settings currently describes the shared retention policy rather than exposing
choices. The seven-day default and optional unused-tab closing are existing
behavior. A wider range is a proposal, not an unmet confirmed requirement.

Suggested fix: consider 1/7/30/90 days and Forever with a preserving migration,
fake-clock tests and clear local-versus-remote expiry copy. Keep the focused
tab safe and explain how unused-tab closing follows retention. Effort: about
one day after the product choice.

### Expose bounded origin-specific permission and site-data controls

missing features — nice-to-have

`native/src/delegate.rs::request_permission` denies unsupported device
capabilities, and Settings has no selective cookie/storage clearing. Downloads
also need verified downstream hooks; the long-press image download is
implemented and not repeated here. A general download manager or broad
browser parity is not promised, and no general save-to-device workflow exists
yet: the PDF viewer is not one. Until a hook exists a download link is worse
than absent (mimo-1 review): the request never commits and the pending-load
line runs out its 30-second timeout (`BrowserCoordinator.awaitCommit`) and
vanishes with no message, so the tap appears to do nothing.

Suggested fix: first verify the pinned permission/storage/download contracts.
Give one document-attributed unsupported-permission explanation and Page Info
detail without stale/hidden-tab spam or automatic grants. Any future prompt
needs origin identity plus Android runtime authorization. Selective clearing
must state what it actually removes; private browsing requires separately
proven isolation/storage semantics. A general download flow should first
verify the pinned embedding's response/download boundary, then propose a
bounded Android storage flow with an explicit destination choice, separate
from PDF viewing and from replacing Servo. Effort: several days of
engine/device work.

### Bring PDF text into Find, Reader and Hister

missing features — nice-to-have

The PDFium-wasm viewer renders pages, but `native/src/find.js` and `reader.js`
still exclude its labels/content until the planned text slice. Non-embedded
CJK text also needs the planned font slice. These limit long-document reading
and recall; they are not evidence that PDF display itself is missing.

Suggested fix: follow [docs/PDF.md](docs/PDF.md)'s bounded PDFium text and CJK
font work, retaining actual document identity, opt-in capture and exclusions.
Use the owner-selected renderer, not a second HTML browser engine. Effort:
several days with native/device PDF fixtures.

### Offer a Reader article outline

missing features — nice-to-have

`ReaderView.kt` renders heading blocks with heading semantics but offers no
outline, so a reader of a long article must scroll to rediscover sections even
though heading blocks and lazy-list positions already exist.

Suggested fix: offer a compact bottom outline sheet with accessible heading
labels and jump-to-section actions; do not add permanent chrome. Effort: about
one day.

### Preserve a document-keyed Reader reading seam

novel, delightful, or quirky ideas worth trying — nice-to-have

`ReaderView.kt:168–228` owns a new lazy-list state when Reader is recreated.
A brief trip to the page or another tab can therefore lose a long article's
place.
A quiet progress/reading-time cue could also support reading without becoming
a dashboard. The shared Text size control is already implemented; a second
Reader size setting is not needed.

Suggested fix: retain a bounded scroll seam keyed by tab and actual document,
invalidating it on replacement rather than matching URLs. An optional
block-based progress line must not claim exact page percentage. A local,
optional "time left" estimate could derive from the extracted text and reading
position so a reader can judge whether to finish now; label it approximate and
account for language differences, with no tracking or service request. Any
reading-time estimate should not assume an English word rate. Effort: about
one day with navigation/rotation tests.

### Try quiet host-colored monograms

novel, delightful, or quirky ideas worth trying — nice-to-have

Recent and Home already have local host-letter tiles, currently in a shared
sage tint. A small deterministic palette could make similar titles faster to
scan without another favicon network request. Same-host duplicate tabs still
need their time/identity context.

Suggested fix: compare a few accessible pine/sage/ochre tints derived locally
from normalized host. Keep letters and other non-color meaning, test contrast,
and add address-chip tiles or real favicons only if they genuinely help.
Never fetch icons through another network stack just for chrome. Effort:
about half a day.

### Explain why a Home tile appeared

novel, delightful, or quirky ideas worth trying — nice-to-have

Automatic frequent-site tiles and explicit pins share Home's surface, and
`HomeLink.pinId` already records which is which. A brief "Pinned by you" /
"From recent visits" explanation in the existing long-press menu would make the
recommendations understandable without cluttering the grid.

Suggested fix: use the existing `pinId` provenance in the options menu, keeping
the main grid uncluttered and the ranking local. Effort: about half a day.

### Coalesce explicit offline-save completion and attention receipts

novel, delightful, or quirky ideas worth trying — nice-to-have

A deliberate save can finish after connectivity returns without an obvious
receipt, while queue problems are mainly visible in Settings. Per-page
Pending/Waiting and Hister acknowledgment markers already exist; a second
badge should communicate genuinely different information.

Suggested fix: try one destination-aware completion summary or a minimal,
coalesced attention notification, with restart/duplicate-receipt tests. Never
notify per automatic capture, expose private page content in notification
previews, or imply crawling/indexing completed. An on/paused/excluded capture
mark should be derived locally and labeled accurately, not filled by a
background Hister search. Effort: about one day.

### Try a user-invoked Madeleine recall

novel, delightful, or quirky ideas worth trying — nice-to-have

The existing Hister scope can support deliberate recall of a selected phrase,
possibly an on this day/last week query if the deployed API actually supports
it. Reader view already lets a passage be selected and copied, so a
source-attributed "copy quote with source" action is another deliberate
keepsake shaped like the literary reference. It is not a reason for passive
remote queries or a new AI service. The launcher history shortcut and Home's labeled Hister/Karakeep dock actions
already exist: `BottomBar.HomeActions` is wired to the scopes in
`BrowserScreen`, outside the `HomeScreen` body. The old proposal requiring
another in-app Hister gesture is therefore obsolete.

Suggested fix: prototype one explicit action against the established search
contract, clearly distinguishing local, Hister and Kagi results. Date-aware
queries must not invent server syntax. A quote action must exclude credentials
from shared links and must not automatically retain selected text. Effort:
about one day for a bounded experiment.

### Add an optional richer save payload at the deliberate action

novel, delightful, or quirky ideas worth trying — nice-to-have

Long-press Save could choose a list/note for that operation instead of changing
a global setting. A local last saved from this site hint could provide useful
bookmark memory. Lists already exist in Settings; ordinary one-tap Save must
stay direct.

Suggested fix: snapshot the optional metadata with the original payload and
destination, keep creation acknowledgment distinct from metadata failure, and
use existing SavedItem records without a recommendation feed. Effort: about
one day after the receipt work is integrated and the interaction is selected.

### Add a deliberate open-copied-link affordance

novel, delightful, or quirky ideas worth trying — nice-to-have

Home can offer a useful Paste and go/open copied link action, and a muted Kagi
bangs hint could make existing search capabilities more discoverable. Neither
requires network autosuggest or a new search integration.

Suggested fix: read the clipboard only after a user-invoked foreground action,
pass links through the address policy, and retain an explicit search decision
for non-links/sensitive text. Keep first-run/empty copy to one helpful line,
not decoration competing with the page. Effort: a few hours plus intent/policy
regressions.

### Keep optional reading embellishments local and restrained

novel, delightful, or quirky ideas worth trying — nice-to-have

A quiet approximate dwell line in Page Info, Reader-only Sepia/Solarized or
E-Ink-like paper/serif palettes, coalesced quiet-reading feedback, a calmer
Save-icon transition or a faint launcher glyph on empty Home are possible
experiments, not confirmed
requirements. Reader appearance is currently coupled to the chrome theme
(`ReaderView.kt` reads the same `ThemeMode`), so a reader who prefers dark
controls and warm light paper cannot choose that combination; the pine/ivory
palette itself is coherent. The bottom bar already auto-hides and
pull-to-reload already works; the old Zen Mode/pull-to-refresh backlog must not
be reimplemented.

Suggested fix: test one low-distraction idea at a time, derive dwell from
existing focus transitions, respect reduced motion and keep pages untouched.
An optional Reader-only paper/sepia/dark choice should reuse the existing
semantic colors and pass contrast checks. Do not suppress actionable failures
or change capture policy merely to make Reader quiet. Preserve the owner's
no-pitch-black/no-Material-You-default choice. Effort: about half a day per
experiment.

### Consider an explicit local offline reading copy

novel, delightful, or quirky ideas worth trying — nice-to-have

Captured readable text is normally transient and removed after upload. A
separate user-requested read later on this device action could offer honest
airplane-mode or missing-engine content, but retaining that text changes the
privacy/storage contract.

Suggested fix: make it an explicit local-only feature with independent
retention/deletion and no implicit Hister upload. Do not silently repurpose
an automatic capture queue into an indefinite history archive. Effort: several
days after a product decision.

### Add hardware shortcuts and alternate Recent gestures only deliberately

novel, delightful, or quirky ideas worth trying — nice-to-have

Ctrl+T/W/L/R/F needs a verified modifier path through the engine. An upward
Recent gesture must not conflict with sideways tab switching, upward Menu,
page pulls or Android's system region. Long-press Recent already quick-switches
tabs, and Home already has Continue/quick links and labeled Hister/Karakeep bottom-dock
actions (`BottomBar.HomeActions` wired by `BrowserScreen`).

Suggested fix: retain visible accessible routes and test gesture cancellation,
multiple pointers and keyboard focus before adding optional accelerators.
A two-column landscape/tablet Recent or an alternate Recent-home layout is an
experiment, not a final owner selection. Effort: about one day per bounded
interaction.

### Preserve the native feature and security maintenance boundary

general issues — nice-to-have

The M0 inventory records the one-process model without a content sandbox and
the enabled servoshell feature set (test bindings, Bluetooth, WebXR/WebGPU,
experimental capabilities; `native/Cargo.toml`), and it dates from the
v0.6.0 pin: it was not refreshed for the current 0.7 line, and no decision
on trimming is recorded. Content runs in the app process; Android's
application sandbox is not proof of a separate content-process sandbox or
private-browsing guarantee. Engine updates must retain a small downstream
patch/update path.

Suggested fix: refresh the feature/sandbox inventory against the pinned
build, record which features stay enabled and why, and change defaults only
with compatible builds and explicit evidence, keeping all changes and
reports downstream. Extract host-testable ownership rules when touching
Android-only engine code; do not treat host tests as device coverage.
Effort: about one day for a refresh, then bounded maintenance slices.

### Close focused regression and validation gaps

general issues — nice-to-have

Inherited cheap gaps without regressions: Notice.Problem reaching its
snackbar, failed visits and current-open visits in search, literal `%`/`_`
plus Unicode case/accent matching (`RecentItemMatchTest` covers case and the
history cap, not accents or wildcards), pasted URL tabs/newlines beyond the
tested literal space, and one full address suggestion/rotation/IME flow.
Fixture ports remain fixed at 8766 (`scripts/check-app.sh`), which
complicates concurrent isolated sessions; sol-2's final capture rerun hit a
real port collision before a successful retry. Old review-specific runs are
not proof of today's integrated tree. Sol-2 also saw one report-service
SIGTERM test timeout on Python 3.15; its focused rerun passed; all 207
service tests also passed on local Python 3.13. The separate nesting
regression is covered (PR #202). Recheck the full service suite on a proposed
replacement runtime without presenting that one timeout as a confirmed
production shutdown defect.

Suggested fix: add independently expected regressions where missing,
parameterize fixture ports for isolated sessions, and rerun the relevant
native/device boundary after integration. Do not normalize pasted URL
whitespace blindly: replacing every whitespace character can change meaning.
Historical Sol validation had a System UI ANR and an incomplete Reader gate;
its exact evidence is in
[ENGINE_SPIKE section 14](docs/ENGINE_SPIKE.md#14-sol-review-validation-21-october-2026).
Fusion's archived results under
`~/.local/share/devin/cli/combray-fusion-artifacts-20261021/` included JVM
gates and five selected accessibility cases without a staged engine, not a
usable signed release or phone performance claim. Effort: about one day for
the focused test additions.

### Refresh the introductory status and architecture handoff

general issues — nice-to-have

`README.md:16` still says "All 18 device checks pass" and
`docs/IMPLEMENTATION_PLAN.md:3` still dates the handoff 18/19 October 2026 and
describes the engine as "patched Servo v0.6.0", while the plan body records
28–171 emulator checks and the pinned downstream is now the `combray/0.7` line
based on v0.7.0. New contributors meet old dates, a stale check count and
Servo/Stop statements contradicted by `docs/ENGINE_SPIKE.md` and
`docs/BUILDING.md`, which can lead to duplicate investigations or mistaken
limitations.

Suggested fix: give the introduction a current, dated summary and link to the
detailed evidence rather than repeating volatile counts. Preserve limits on
what was actually verified. Effort: about two hours.

### Expose automatic sites separately in the Home editor

usability: fast, convenient, clear — nice-to-have

Concurrent Devin-1 review: `HomeLinks.state` caps combined links at
`HOME_LINK_LIMIT`, while `HomeEditorSheet` derives its frequent-site editor
from `state.links.filter { it.pinId == null }`. Six pins leave that section
empty, so the user cannot pin/hide another automatic site without removing a
pin first. The capped visible launchpad is correct; the editor needs a broader
projection.

Suggested fix: expose uncapped ranked sites or a separate automatic-links
collection in `HomeLinksState` for editing. Test six pins, pin replacement and
hide/restore without changing the six-tile Home limit. Effort: about half a day.

### Show preparation and cancellation for slow file providers

usability: fast, convenient, clear — nice-to-have

Concurrent Devin-1 review: `PageControlDialog.kt` stages picked content URIs on
the graph scope through `UploadStaging.kt`, with no progress/cancel affordance.
A large file or slow provider leaves the user unable to distinguish preparing
from hung. This is separate from the startup-lifetime fix (PR #209)
and complements the documented resource-policy proposal.

Suggested fix: show a bounded Preparing upload state and a truthful cancel or
timeout path. Cancellation must not claim bytes were attached or delete an
earlier attachment still referenced by a page. Test a blocked provider and
navigation/process boundaries. Effort: about one day.

### Align localhost subdomain classification for page media

bugs — nice-to-have

Concurrent Devin-1 review: `core/media/MediaPolicy.kt::isDevicePageHost`
recognizes exact `localhost`, while `mayReachHost` treats `*.localhost` as
device-scoped. Where such a name actually resolves locally, its page is not
recognized as a device page and its media to localhost targets is refused.
Both classifiers must respect the owner-approved network policy.

Suggested fix: align the classifiers and cover exact localhost, subdomains,
non-local suffix lookalikes, resolved addresses and redirect hops. Do not
broaden unrelated pages' access to device/link-local targets. Effort: a few
hours plus policy/network tests.

### Use a neutral monogram for hostless documents

visual issues and layout problems — nice-to-have

Concurrent Devin-1 review: `ui/Formatting.kt::monogram` falls through to
`displayHost`'s scheme-derived labels. Every `data:` document therefore shows
D, with similar uniform scheme letters for other hostless pages. These tiles
can look more like site identity than a neutral document marker.

Suggested fix: use a neutral glyph for hostless pages while retaining the
safe scheme-only address label. Never derive a fake host from arbitrary data
or about-page text. Effort: a few hours with formatting/UI tests.

### Avoid a brief engine-start overlay flash on restored pages

visual issues and layout problems — nice-to-have

The earlier review observed a full-page Starting the engine overlay between
logical-session restoration and the first real frame. The launch splash now
covers session restoration for up to two seconds, not necessarily the engine
start. This is a remaining visual candidate, not a new measured startup delay.

Suggested fix: verify the current cold-start sequence, then consider keeping
the existing launch/background surface for a genuinely brief transition.
Never hide a missing/failed engine indefinitely or claim an unpainted restored
page is ready. Effort: about half a day with device frame evidence.

### Evaluate predictable menu geometry without offering broken actions

usability: fast, convenient, clear — nice-to-have

The inherited review proposed retaining unavailable Forward as a disabled row
rather than changing menu height between tabs. The implementation has since
moved from an older More sheet to a toolbar menu, so the current thumb path
and owner requirement to offer useful actions need rechecking.

Suggested fix: compare current menus on tabs with and without forward
history before changing row visibility. Keep routine controls at the bottom,
accessible labels and the only offer what works intent. Effort: a few hours
for a synthetic UX comparison.

### Identify top-level page-initiated navigation events

usability: fast, convenient, clear — nice-to-have

The native navigation delegate can report frame navigations without saying
which frame initiated them. The plan therefore does not treat every
PageNavigationRequested as a new top-level loading request; tapped links can
show loading only from commit. Blindly consuming every such event would make
iframe activity misrepresent page progress.

Suggested fix: inspect the pinned downstream callback for frame-level identity
or add a verified downstream signal before using these events for top-level
chrome. Test iframe loads, same-document changes, redirects and real tapped
links with generation/document guards. Effort: about one day plus native and
device checks.

### Keep a typed service credential across rotation

usability: fast, convenient, clear — nice-to-have

`SettingsSheet.kt:339` holds the credential field in plain `remember`, so a
rotation loses a just-typed API key or token while address fields survive.
Unlike plain memory, `rememberSaveable` can serialize the credential into
Android saved state; it must not be described as never persisting the field.

Suggested fix: retain the unsaved draft in a scoped, memory-only holder across
rotation, clearing it on dismissal and process death. Do not re-display stored
credentials or put the draft into a saved-state Bundle. Verify rotation,
dismissal and process-loss behavior. Effort: about half a day.

### Reject out-of-range ports in the address policy

bugs — nice-to-have

`AddressPolicy.kt:71` accepts any 1–5 digit port (`example.org:99999` classifies
as Navigate), but the WHATWG parser then rejects the port, so the user only gets
the generic "address not opened" snackbar instead of a local "malformed
address" message.

Suggested fix: treat ports above 65535 as `MALFORMED_ADDRESS` in
`AddressPolicy`. Effort: small plus a unit test.

### Fix the address policy's space handling

bugs — nice-to-have

`AddressPolicy.kt:97-99` documents that a known-scheme address navigates "as
typed", but `AddressPolicy.kt:108` encodes only literal spaces as `%20`. Tabs
and other whitespace inside a pasted `https:` link are left in place; the
authority check accepts them and the engine may refuse the address, showing
"address not opened" for a link that looked valid.

Suggested fix: replace every whitespace character with `%20` (the existing
`WHITESPACE` regex), or reject the address as malformed, and correct the doc
comment to match. Add a policy test for a tab inside a pasted link. Effort:
trivial.

### Offer copying the article's address from Reader view

novel, delightful, or quirky ideas worth trying — nice-to-have

Reader view's top bar (`ReaderView.kt` `ShowPageBar`) offers only "Show the
page"; a passage's source cannot be copied or shared without leaving the
article.

Suggested fix: add a "Copy link" action next to "Show the page", reusing
`copyAddressToClipboard` (sensitive-marked like the bar's long-press copy).
Effort: small.

### Keep the engine-start overlay behind the engine state

visual issues and layout problems — nice-to-have

`BrowserScreen.kt:888-891` (`PageOverlay`) checks `tab.crashed` before
`state.engine == EngineStatus.STARTING`, so a crashed tab during engine start
shows "page crashed" with a Reload button that cannot work yet.

Suggested fix: check `STARTING` before `tab.crashed`. Effort: about 15 minutes.

### Paint Home's wordmark before the links arrive

visual issues and layout problems — nice-to-have

`HomeScreen.kt:104-108` paints an empty background while the links flow is
loading; the wordmark, heading and tiles appear together a moment later on
every cold start on Home.

Suggested fix: paint the wordmark and heading at once and load only the tiles
below them. Effort: about an hour.

### Re-request Find focus after the keyboard hides

usability: fast, convenient, clear — nice-to-have

`FindBar.kt:70` requests focus once per composition. After the user dismisses
the keyboard (Back), the find field keeps no focus and hardware-keyboard typing
goes nowhere until they tap it.

Suggested fix: re-request focus when the IME hides while the find bar is open,
or make the whole bar focusable. Effort: about 30 minutes.

### Do not wake the process to refresh relative times

performance problems, stuttering, and jank — nice-to-have

`HomeScreen.kt:199` and `RecentSheet.kt:159` run a `produceState` loop that
wakes the process every 60 s / 30 s for as long as they are composed, even when
nothing visible changes. `RecentSheet`'s tick recomposes the whole sheet,
including the server panels, which do not read the clock.

Suggested fix: derive the relative time from one tick at the screen level, or
tick only while the sheet is actually visible; scope the tick to the local
rows' headers and times. Effort: about an hour.

### Run Recent's filter off the main thread

performance problems, stuttering, and jank — nice-to-have

`RecentSheet.kt:153` filters up to 5,000 retained rows with
`contains(ignoreCase = true)` during composition on the main thread, on every
keystroke. A large history makes the filter a few milliseconds of jank.

Suggested fix: run the filter on `Dispatchers.Default`, as
`AddressSuggestions.rank` already does. Effort: about an hour.

### Answer a second intent without dropping the first sheet

usability: fast, convenient, clear — nice-to-have

`MainActivity.kt:92` sets `launchRequest.value = incoming`, overwriting a
pending launch request. Two shares or a share plus a launcher shortcut in quick
succession lose the first sheet.

Suggested fix: ignore a new request while one is pending, or queue them.
Effort: about 15 minutes.

### Keep the address decision's host visible at large type

visual issues and layout problems — nice-to-have

`AddressEditor.kt`'s decision button splits its label into lead, host and
tail, ellipsizes the host from the start and the tail from the end. The
three texts are still unweighted in the `RowScope`: at 200% font the host
can take the room and push the path out of the button, and the lead can
clip without an ellipsis.

Suggested fix: give the host `weight(1f)` and ellipsize the lead and tail.
Effort: about 30 minutes.

### Reset a swiped tab row before the list updates

visual issues and layout problems — nice-to-have

`RecentSheet.kt:338` creates `SwipeToDismissBoxState` with `remember {}` and
leaves it at the dismissed value; a row stays fully swiped, showing its close
background, until the list updates and removes it.

Suggested fix: reset the state to `Settled` in `onDismiss` before closing.
Effort: about 15 minutes.

### Offer a recently closed tabs list

missing features — nice-to-have

The Undo snackbar covers one close; an older closed tab cannot be reopened from
Recent, and closing the wrong tab a minute later means retyping the address.

Suggested fix: add a "Recently closed" section backed by the existing CLOSED
tab rows, with the same open and forget actions. Effort: about a day.

### Offer copy and share for the article text

missing features — nice-to-have

Reader text is selectable, but copying a whole article takes a long
press-and-drag, and Share sends only the address and title. A passage or the
whole article cannot be copied or shared as text without leaving Reader.

Suggested fix: add a More action that copies or shares the article's plain
text, reusing the existing clipboard and chooser paths. Effort: about two
hours.

### Long-press the tab counter to open Recent

novel, delightful, or quirky ideas worth trying — nice-to-have

The tab counter shows the number of tabs but is inert; a long-press would be a
shortcut to the tab list, distinct from the Recent button's long-press
(previous tab).

Suggested fix: add a `combinedClickable` long-press on the counter that opens
Recent on `RecentScope.OPEN`. Effort: about an hour.

### Book an outbox wakeup when a job is claimed

bugs — nice-to-have

`OutboxDispatcher.drain` books the next wakeup only in its `finally`. A
process kill between `claim()` and that `finally` leaves the job `RUNNING`
under a lease with no alarm booked, so delivery recovers only when WorkManager
re-runs the interrupted `OutboxWorker` (it never returned a `Result`) or the
user opens the app. That is likely but not guaranteed on every device.

Suggested fix: book a wakeup right after a successful claim, at the claim's
`leaseUntil`, so a kill always leaves an alarm behind. Test a kill between
claim and finish. Effort: under a day.

### Map a handler's own cancellation to a failed attempt

bugs — nice-to-have

`OutboxDispatcher.step` maps a `CancellationException` thrown by a handler's
own internals (not the drain's cancellation, which is rethrown) to `null`, and
`drain` then records `DispatchResult.Retry("dispatch_failed")`: a handler bug
that cancels itself is retried to the attempt limit instead of failing
honestly. `dispatch` rethrows such exceptions into exactly this path.

Suggested fix: distinguish a handler-originated cancellation and map it to
`Rejected`/`Failed`, reserving `Retry` for explicit transport outcomes.
Effort: a few hours.

### Complete the engine's per-view cleanup paths

bugs — nice-to-have

Four small state-cleanup gaps from the mimo-2 review's engine slice, all in
android-only native code its session could not build or test.
`close_tab` (`native/src/engine.rs:1172-1210`) does not `cancel_reload_pull()`,
so a pull armed on the closing tab fires for a closed view, and `:1178` emits
`ImeHidden` for a merely focused (withheld) field, which can hide the app's
keyboard spuriously. `set_throttling(Throttled)` (`:1060-1078`) skips the
cleanup `select_none` performs (reload pull, keyboard, off-screen leave-page
questions). `open_page_tab` (`:1022`) never arms `pending_media.navigation_requested`
as `create_tab` does, so a page-opened window's early media loads can be
dropped.

Suggested fix: mirror `select_none`'s cleanup in `close_tab` and the throttled
branch, gate `ImeHidden` on `Keyboard::Open`, and arm the media hold in
`open_page_tab`; each has an obvious regression to write on the host side.
Effort: under an hour each once the android-only build and a device run are
available.

### Harden KeystoreSecretStore writes and reads

bugs — nice-to-have

`SecretStore.kt:107-114` writes through a fixed `<name>.tmp` path: a kill
between write and rename leaves an orphan that is never cleaned, and any
future concurrent write to one `SecretName` can interleave bytes into a
corrupt ciphertext (key creation itself is serialized under a process-wide
lock). `:94-96` treats every `ProviderException` on read as a lost Keystore
key, so a transient failure (boot, load) makes the UI demand a credential
that is intact.

Suggested fix: unique temp name per write plus rename, drop stale `*.tmp`
files at startup, and distinguish key-loss from transient failure,
reporting the latter as temporarily unavailable. Effort: a few hours.

### Keep one media player at a time

bugs — nice-to-have

`PlayerActivity` has standard launch mode and no `onNewIntent`
(`app/src/main/AndroidManifest.xml:56-58`), so two quick Play taps stack two
players that fight over audio focus and reveal each other on close.
`PlaybackRequests.kt:13-20` evicts at four entries: a fifth rapid offer evicts
an id whose activity is still launching, and that player flashes and closes.

Suggested fix: `launchMode="singleTop"` with `onNewIntent` replacing the
request (or a single-player guard), and refuse or hold offers while a player
is starting. Effort: about half a day with a device run.

### Claim H.264 profiles precisely until the codec probe exists

bugs — nice-to-have

`native/src/media.rs:158-166` (`mp4_codec`) accepts bare `avc1`/`avc3` and any
profile prefix as `Probably`, so a page can pick an H.264 profile the device
cannot decode and fail in the player instead of falling back. The planned
phase-B codec probe (docs/VIDEO.md) is the real fix.

Suggested fix: until the probe, require a profile level (`avc1.PPCCLL`) for
`Probably` and answer a bare codec `Maybe`. Effort: half a day for the parser
tightening.

### Prune saved_items when a service is removed

general issues — nice-to-have

The `saved_items` dedupe table grows with every distinct saved URL and is
never pruned; it is only consulted for save status and duplicate suppression.
Deliberate failed saves are separately tracked and must not be silently
expired (see the failed-save queue entry above).

Suggested fix: delete a service's `saved_items` rows when the service is
removed, and consider a documented age bound that never touches outbox rows.
Effort: a few hours.

### Quiet the remaining lint warnings

general issues — nice-to-have

The build has no Kotlin compiler warnings. Lint still reports 22 warnings in
`app` and 3 in `engine`. Two need a decision: `BrowserScreen.kt` reads
`ClipDescription.EXTRA_IS_SENSITIVE` (`InlinedApi`: the constant needs API 33;
it is inlined and benign but deserves a guarded suppression with a reason),
and `EngineSurfaceView.kt` overrides `onTouchEvent` without `performClick`
(`ClickableViewAccessibility`). The rest is housekeeping: unused resources,
`UseKtx`, `UsableSpace`, `TextConcatSpace`, `ViewConstructor`,
`UseRequiresApi` and dependency-version notices.

Suggested fix: a `Build.VERSION` guard or reasoned `@SuppressLint`,
`performClick` delegation in the surface view, and the housekeeping items one
category at a time. Effort: an hour or two.

### Isolate scroll-direction state from the page's composition

performance problems, stuttering, and jank — nice-to-have

The engine reports a `ScrollEdge` on every `ACTION_MOVE` past slop, and
`BrowserScreen.kt:638-642` writes `scrollActivity` (always) and
`scrollEdge`/`barDirection` (on change) into states read in the page `Box`'s
content lambda (`:692-695`), so the page subtree recomposes on every scroll
frame; `ReaderView.kt` does the same for the article. The reader's block
rebuild is stable (a remembered link callback and remembered block strings),
and Compose scopes skip most children, so the real cost is unmeasured, but
this is the classic per-frame recomposition shape.

Suggested fix: isolate the reads (a `State` read only inside
`ScrollEdgeButton`), throttle reporting to direction changes, or derive with
`derivedStateOf`; validate with a layout-inspector trace on a device.
Effort: one to two days.

### Parse capture results off the engine thread

performance problems, stuttering, and jank — nice-to-have

A 256 KiB page capture is serialized to JSON on the engine thread
(`native/src/events.rs`) and parsed again there before reaching the
coordinator (`NativeEngineHost.deliver`): tens of milliseconds on the thread
that paints, visible as dropped frames when a large page captures.

Suggested fix: hand the raw JSON to the coordinator's dispatcher and parse it
there, or parse only the small fields natively and stream the text separately.
Effort: about half a day.

### Avoid recomposing the bottom bar on every swipe move

performance problems, stuttering, and jank — nice-to-have

`BottomBar.kt:167,281` reads `swipeProgress` in composition for the threshold
line, so the whole bar recomposes on every pointer move of a tab swipe (the
drag offset itself is correctly kept in a `graphicsLayer`).

Suggested fix: derive a boolean or draw the line from a `State` read in the
draw phase. Effort: an hour or two.

### Show notices while a modal sheet is open

usability: fast, convenient, clear — nice-to-have

`NoticeHost` lives in the activity window (`BrowserScreen.kt:647-655`), but
every `ModalBottomSheet` is its own dialog window on top: while Address,
Settings, Media, Home-editor or Report is open, notices (media playable,
popup blocked, opened-in-background Show, Undo) are invisible behind the
sheet. Only Recent hosts its own snackbars.

Suggested fix: render a notice host inside each sheet as Recent does, or move
the host above sheets. Effort: half a day.

### Restore the splash icon's tile

visual issues and layout problems — nice-to-have

`values/themes.xml:13-16` uses `ic_launcher_foreground` (the ivory glyph and
its pine tile) on the `window_background` color: against the dark night
background the pine tile at roughly 1.6:1 all but disappears, and against
light paper the ivory glyph at roughly 1.1:1 half-does, so the launch screen
never reads as the launcher icon.

Suggested fix: set `windowSplashScreenBackground` to the icon's pine tile
color, or use the full adaptive `@mipmap/ic_launcher`. Effort: an hour.

### Keep reader chrome out of the reader zoom

visual issues and layout problems — nice-to-have

`ReaderView.kt:149,202,227-235` applies the reader's density override
(`fontScale * textSize.zoom`) to the whole article subtree including the
app's own "Show the page" button and scroll control, so reader chrome inflates
with the reading size (at 150% the label dwarfs its 48dp target).

Suggested fix: move `ShowPageBar` and the scroll control outside the
`CompositionLocalProvider`. Effort: an hour or two.

### Smooth the bottom-bar hide/show viewport change

usability: fast, convenient, clear — nice-to-have

The page viewport grows and shrinks when the bottom bar hides or shows
(`BrowserScreen.kt:377-380`), reflowing content on every transition. This
follows the owner's explicit design (hidden controls free page space, 31
October 2026); only the abruptness is open, not the behavior.

Suggested fix: animate the inset change if the owner wants it smoother; do
not change the freed-space requirement. Effort: about a day.

### Add a haptic tick at the tab-swipe threshold

novel, delightful, or quirky ideas worth trying — nice-to-have

The bottom-bar tab swipe shows a directional line filling toward its 56dp
threshold; one light haptic tick as it fills would confirm the pending switch
without looking.

Suggested fix: vibrate once when the gesture crosses the threshold, respecting
the system haptic setting. Effort: an hour.

### Offer Save to Karakeep on the opened-in-background notice

novel, delightful, or quirky ideas worth trying — nice-to-have

The "opened in background" snackbar offers Show. A second action, Save to
Karakeep, would serve the open-later/keep-forever split through the existing
durable save queue.

Suggested fix: add the action to the existing notice, routing through the
same save checks and receipt as the bar's Save. Effort: an hour or two.

### Offer a per-site "always open in Reader" preference

novel, delightful, or quirky ideas worth trying — nice-to-have

Reader view is opened per page from More. A one-tap "always for this site"
offer after opening it could remember a reading-first site next to Home pins,
without any remote data.

Suggested fix: store the preference locally (host-merged like pins), apply it
on committed documents through the existing reader invalidation rules, and let
the editor undo it. Effort: about a day.

### Remember the text size chosen per site

novel, delightful, or quirky ideas worth trying — nice-to-have

More › Text size zooms pages globally (the pinned embedding exposes
`set_page_zoom`); a reading-first browser could remember the size chosen per
site and restore it when one of that site's pages commits, so a small-print
forum reopens at the size last used. Local and private.

Suggested fix: store per-site zoom next to Home pins, apply through the
existing page-zoom plumbing on commit, and let the editor undo it. Effort:
about a day.

### Make Forward a gesture, not only a menu row

usability: fast, convenient, clear — nice-to-have

Forward is a routine navigation action but appears only in More
(`ToolbarMenu.kt:252`), while Close tab holds a bottom-bar slot;
back-and-forth browsing pays two taps per forward.

Suggested fix: long-press Back for Forward (the bar already teaches
long-press on Recent) with a haptic tick, keeping Forward in More as the
visible route. Effort: hours.

### Add a haptic tick at the reload-pull threshold

novel, delightful, or quirky ideas worth trying — nice-to-have

Sibling of the tab-swipe tick above: one light tick when the pull-to-reload
gesture crosses its threshold would confirm the pending reload without
looking.

Suggested fix: vibrate once on threshold crossing in the pull tracking,
respecting the system haptic setting. Effort: an hour.

### Show the report id in the preview

missing features — nice-to-have

`ReportSheet.kt`'s details block names the app, engine, Android and display
values, but not the report id. The id is what the service uses to recognise a
repeated delivery, and it is the only handle the user has when talking about
a report.

Suggested fix: show the id in the details block, selectable for copying.
Effort: under an hour.

### Show a media entry's duration or size

missing features — nice-to-have

`MediaSheet.kt` lists a page's audio and video by name and site only. A page
with several files gives no way to tell a 30-second clip from a three-hour
recording before playing it.

Suggested fix: show the duration once the player has probed it, or the file
size from the response headers. Effort: about half a day.

### Give the fullscreen player a share action

missing features — nice-to-have

`PlayerActivity.kt` has Close and the media controls, but no Share. A user who
finds a video in a page cannot share its address from the player; they must
leave it and use the browser's Share.

Suggested fix: add a share button that sends the request's address through the
existing chooser. Effort: about half a day.

### Notify when a report is resolved

novel, delightful, or quirky ideas worth trying — nice-to-have

A user who sends a report has no idea when an agent marks it done or rejected;
they must open Settings and look at the queue. The service already exposes the
status history to the agent routes.

Suggested fix: a local notification when a report's status changes, with the
same coalescing and privacy rules as the save receipts. Effort: about half a
day.

### Give the find bar's status line room at 320 dp

visual issues and layout problems — nice-to-have

`FindBar.kt` puts the field on its own row and gives the status text the
remaining width with a two-line cap, but four 48 dp icon buttons (Previous,
Next, New tab, Close) still sit beside it. At 320 dp with 200% text the
"3 of 12" line can still wrap to two lines, pushing the bar taller.

Suggested fix: hide Previous/Next below a narrow width, or move them into
the field's trailing icons. Effort: under an hour.

### Scale the report preview's screenshot cap with the window

visual issues and layout problems — nice-to-have

`ReportSheet.kt`'s `PREVIEW_MAX_HEIGHT` is a fixed 240 dp. On a large screen
the preview is still 240 dp tall, so the user cannot judge the screenshot they
are about to send.

Suggested fix: scale the cap with the window size class. Effort: under an
hour.

### Lift the dark scheme's darkest container

aesthetics and theming — nice-to-have

`Theme.kt`'s dark `surfaceContainerLowest` is `0xFF0A110D`, darker than the
charcoal surface and close to black. On OLED it can read as a hole rather than
a raised surface.

Suggested fix: lift it to a tone that stays above the surface. Effort: under
an hour.

### Add a reading-progress bar to Reader view

novel, delightful, or quirky ideas worth trying — nice-to-have

`ReaderView.kt` tracks the article's scroll position for the scroll edge
button but shows no progress. A thin bar that fills as the user moves through
the article gives a sense of progress and a quick way back to the top.

Suggested fix: derive it from the existing scroll state. Effort: under an
hour.

### Add a "jump back in" card to Home

novel, delightful, or quirky ideas worth trying — nice-to-have

The Continue card names one tab; a second line with the last page the user can
reopen would make the new-tab page more useful without a busy dashboard.

Suggested fix: read the existing Recent projection. Effort: about half a
day.

### Request the find bar's focus after the field is placed

usability: fast, convenient, clear — nice-to-have

`FindBar.kt:70` requests focus in `LaunchedEffect(Unit)`, which can run before
the `OutlinedTextField` is attached. The find field can then open without
focus and the user must tap it.

Suggested fix: request focus from a `LaunchedEffect` keyed on the field's
placement, or use `Modifier.onPlaced`. Effort: under an hour.

### Answer HTTP/0.9 with a valid status line on Python 3.12

bugs — nice-to-have

`services/site-reports/site_reports/api.py`'s `send_error` sets
`request_version = ""` so that `send_response_only` writes a status line. On
Python 3.13+ that method uses `protocol_version` and the response is valid;
on Python 3.12, which the README supports, it writes `" 400 Bad Request"`
with no version token. The pinned image is 3.13, so production is unaffected.

Suggested fix: set `request_version = "HTTP/1.1"` (or write the status line
directly). Effort: under an hour.

### Read Recent and the new-tab tiles on the clock retention trusts

bugs — nice-to-have

Maintenance expires history on `RetentionClock`, which ignores a device clock
running more than an hour ahead of the time that passed since its last
trusted reading. `BrowserCoordinator.recent` and `topSites` compute their
retention cutoff from the raw clock. After a forward jump larger than the
retention period within one boot, maintenance keeps the history, but Recent
lists only open tabs and the new-tab page shows no site tiles until the clock
is corrected. Nothing is deleted.

Suggested fix: derive both cutoffs from the trusted time. `RetentionClock` is
not thread-safe and writes its anchor, and the two flows run outside the
coordinator's queue, so do not call it from them: keep the lead of the raw
clock over the trusted one from the last maintenance pass, subtract it, and
re-subscribe the SQL lower bound when it changes. Test with a wall clock that
jumps while elapsed time barely moves, as
`aForwardJumpedClockKeepsHistoryAndUnusedTabs` does for maintenance. Effort:
about half a day.

### Tie a long-press's context click to its document in the engine

bugs — nice-to-have (needs a device run)

PR #340 made the long-press timer answer only while the page under the press
is still the page the app has bound to the surface. `NativeEngine.contextClick`
still carries only coordinates, and the engine's `context_click` delivers to
whichever view is active, so a document the engine committed but the app has
not processed yet can still receive the click. `scrollToEdge` shows the
complete form: view, generation and document id travel with the command and
the engine drops it when they are stale.

Suggested fix: pass the pressed `PageRef` through `contextClick` and check it
in `engine.rs` as `scroll_to_edge` does. Cover it with a fixture page that
replaces itself under a held press. Effort: about two hours plus the native
build and the device check.

### Give a backgrounded selected tab the hidden-page capture budget

bugs — nice-to-have (needs a device run)

`run_capture` in `native/src/engine.rs` picks the script budget from
selection alone: the selected tab gets the on-screen 400 ms, any other tab up
to 2.5 s. When the app goes to the background the coordinator throttles the
selected tab and the engine hides its view, but the tab stays selected. An
automatic Hister capture that fires then (a page finishing its load after
you switched apps) walks a long page for 400 ms on a throttled thread and
stores truncated text, although nobody is watching the page it would stall.

Suggested fix: treat a selected but throttled view as hidden when choosing
the budget (`self.active == Some(tab) && tab.throttling == Normal`). Extract
the decision next to `capture::time_budget` so a host test covers it.
Effort: about an hour plus the native build.

### Keep notices clear of the find bar

UI/UX — nice-to-have (needs a device run)

The notice host is padded by `barHeight`, which only the bottom bar measures.
While find in page is open the bottom bar is not composed, so the last
single-row height (64dp) stays in use although the find bar is about 112dp
tall. With the keyboard down, a notice or the media offer that is showing
covers the lower part of the find field. The find bar's button row stays
free, and with the keyboard up the overlap mostly disappears.

Suggested fix: measure the find bar with its own `onSizeChanged` and use that
height for `noticeInset` while find is open. Effort: under an hour plus a
device look.

### Open an app link's fallback in the tab that raised it

bugs — nice-to-have

The "Open in another app?" dialog keeps only the link's address. Its fallback
page (`browser_fallback_url`) is loaded into the tab that is active when you
tap Open. If another tab took focus while the dialog was up (a link or shared
text arriving from another app, or the launcher's New tab shortcut), the
fallback replaces that tab's page instead. A page cannot cause this itself:
its windows are blocked once the link has used the tap.

Suggested fix: carry the raising tab's id in `ExternalLink` (the coordinator
knows the view) and open the fallback there; if that tab is gone, say that no
app can open the link. Extend
`anAppLinkWithoutItsAppOpensItsFallbackPageInTheSameTab`. Effort: about an
hour.

### Make two device checks able to fail

general issues — nice-to-have (needs a device run)

`MediaPlayerTest.audioPlays` logs whether the position advanced but never in
the failed-report shape `summarize-instrumentation.py` lists, so a player
that reaches READY and never advances is summarized as passed.
`HisterTest.pausedCaptureSendsNothing` asserts only that nothing arrived
within fifteen seconds; unlike its neighbours it does not first prove capture
was running, so it also passes when the pipeline is dead.

Suggested fix: log `REPORT audio: failed: position did not advance` when the
probe times out (the summarizer then lists it without failing the run), and
in the paused-capture check load an allowed page first and await its
document. Both change `app/src/androidTest`, so the Engine workflow runs
them. Effort: an hour plus the run.

### Add the sign-in and CSP-sandbox PDF device checks

general issues — nice-to-have (needs a device run)

docs/PDF.md planned `PdfTest` cases for a PDF behind HTTP sign-in and for one
served under a CSP sandbox. Neither exists, and the fixture server has no
such PDF, so the viewer's behaviour for both is unverified.

Suggested fix: add the two fixtures to `testing/fixtures/server.py` and the
checks to `PdfTest`. Effort: about half a day plus the run.

### Skip the NDK download in the Engine workflow's device job

general issues — nice-to-have

`scripts/m0/emulator.sh` sources `scripts/m0/env.sh`, which installs the
pinned NDK (about 2.4 GB) through `sdkmanager` whenever it is missing. The
Engine workflow's device job only boots an emulator and runs the installed
APKs, yet on a fresh hosted runner it downloads the NDK first.

Suggested fix: install the NDK from `scripts/build-native.sh`, its only
consumer, and leave `env.sh` to report a missing one. Add a `scripts/tests`
case with a recording `sdkmanager` stub that the emulator setup must not
call. Effort: about an hour plus one Engine run.

### Run the repository checks once per PR push

general issues — nice-to-have

`.github/workflows/checks.yml` triggers on every `push` and on
`pull_request`, so each push to a PR branch runs the documentation job twice
and lists it twice among the PR's checks. `ci.yml` limits `push` to `main`
for this reason.

Suggested fix: `push: branches: [main]` in `checks.yml`. Effort: minutes.

### Back off the outbox's fallback wakeup while its storage fails

performance problems, stuttering, and jank — nice-to-have

When `Outbox.nextWakeup()` throws, `OutboxDispatcher.drain` books the next
run a constant 60 seconds out, and it keeps no count of failed drains. While
the queue's database stays unreadable (a full disk), the WorkManager job
runs about once a minute, each time pruning, housekeeping the report
directory and logging the failure. No job is lost; every other retry in the
queue backs off up to six hours.

Suggested fix: count consecutive failing drains in the dispatcher and double
the fallback up to `RetryPolicy`'s ceiling, resetting on the first healthy
read. Extend `aBrokenQueueStillBooksItsNextWakeup`. Effort: about an hour.

### Report a failed settings write instead of crashing

bugs — nice-to-have

The settings sheet launches its writes bare: Save, Check connection and
Remove of each service, the Karakeep list choice, Hister's capture switch and
its exclusions (`SettingsSheet.kt`, eight `scope.launch` blocks). They read
and write Room, which throws when storage fails (a full disk, an I/O error),
and nothing catches it, so the app ends. The Home editor, the report drafts
and the appearance settings catch the same failure and say so.

Suggested fix: catch at each launch as `HomeEditorSheet.change` does and show
the section's message. `ServiceFormActions.perform` must keep rethrowing
(`aFailureReleasesTheAdmission` pins it), so the catch belongs at the call
sites, applied through `complete` so that it respects the form's edit fence.
Extend `SettingsSheetTest` with throwing `configure` and
`setCaptureEnabled` stubs. Effort: about two hours.

### Say which address Check connection checked

UI/UX — nice-to-have

Check connection reads the stored service, not the drafts in the form, and
its answer ("Connected.") appears under the edited fields without naming a
server. After typing a new address and tapping Check before Save, the answer
describes the old server and reads as if the new one worked.

Suggested fix: name the address that was checked next to the answer, or
check the drafts without storing them. The second changes what Check means
and needs the owner's decision. Effort: under an hour for the first.

### Stage an upload whose provider cannot name it

bugs — nice-to-have

`UploadStaging.copy` asks the provider for the file's display name before
anything else, without a guard. A provider that throws from `query` but
serves the stream fails the whole pick as unreadable. The size query right
after it already treats a throwing provider as "unknown", and a missing name
already falls back to `upload`.

Suggested fix: catch in `displayName` as `reportedSize` does.
`aProviderFailureIsRedactedAndDoesNotDeleteAnEarlierPick` uses such a
provider for its failure and needs one that fails on `openFile` instead.
Effort: under an hour.

### Keep the fragment of an address named to site-check

general issues — nice-to-have

`sitecheck run` and `sitecheck check` load the normalized form of a web
address named on the command line, and normalizing drops the fragment (right
for addresses from history, where a fragment can hold a token). A reduced
test case that depends on its fragment, such as a hash-routed state on a
local server, is therefore checked without it and can report `ok` for a
state that was never loaded. A named `file:` address keeps its fragment.

Suggested fix: keep the normalized address as the store key and load the
address as written, or refuse a named address with a fragment and say why.
Test next to `test_named_urls_and_local_files`. Effort: about an hour.
