# Combray: outstanding analysis and future work

Consolidated on 7 November 2026 from the existing backlog and the sol-1,
mistral-2 and sol-2 source reviews. Completed work and findings implemented
in open PRs are omitted. Open PRs are not claims about behavior on main.
Remaining inherited candidates are
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

## Still open

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

### Bind queued keyboard edits to the original field

bugs — important

`native/src/engine.rs::ime_commit` still receives an `ImeCommit` containing
text and selects `active_webview()` when processing it. A tab/field change
between admission and execution can retarget text. The older single global
`ime_owner` finding is obsolete: fields are now kept per tab, with visible-tab
keyboard ownership. That does not by itself identify an already queued edit.

Suggested fix: carry view generation and applicable field/document identity
with edits, rejecting a stale owner instead of typing into whichever view is
active. Exercise hidden autofocus, tab switching, navigation, blur, closure,
rotation and delayed delivery. Effort: one to two days including native/device
input regressions.

### Complete composing and surrounding-text semantics

bugs — important

`engine/EngineSurfaceView.kt`, `EngineInputConnection`, and the native input
commands implement a minimal bridge. Composition is buffered without visible
page preview; surrounding-text, caps and selection synchronization are absent.
`ImeShown.multiline` is not applied to the editor configuration, hardware
modifiers are not carried as modifiers, and `deleteSurroundingText` can emit
two JNI commands per requested character without a work cap. These are
inherited concrete implementation limits; rich-text typing added later must
not be mistaken for a complete Android IME contract.

Suggested fix: establish bounded, field-attributed editing semantics with
AOSP/Gboard and CJK/correction/emoji tests. Verify dismissal does not flush an
old composition into another field, same-kind field changes do not reuse a
dead connection, `ACTION_MULTIPLE` does not become bogus key releases, and
large deletion requests do not block the UI. Preserve complete edits or
explicitly decline unsupported operations rather than silently clipping them.
Effort: several days with real-engine and keyboard evidence.

### Clear every obsolete page control on document replacement

bugs — important

`BrowserCoordinator.kt` retains controls until `ControlHidden` or a crash.
`native/src/engine.rs` explicitly clears LeavePage/context-menu controls on a
new document, but other pending embedder controls still depend on Servo's
withdrawal behavior. A missing withdrawal can leave the old question under a
new page's host. This is the remaining inherited document-attribution
candidate, distinct from the Back-answering work in an open PR.

Suggested fix: reproduce a replaced document with a pending select/script
control, then retire or dismiss all obsolete controls at a document-identity
boundary. Keep prompts belonging to a still-current frame valid; an equal URL
is not identity. Verify delayed responses cannot reach the replacement page.
Effort: about one day plus device checks.

### Make failed surface detach an explicit ownership failure

bugs — important

`EngineSurfaceView.surfaceDestroyed`, `native/src/jni_api.rs::send_and_wait`
and native surface binding need a current failure-path check. The inherited
review found a five-second UI-thread acknowledgment wait, success reported
after an unsuccessful unbind, and a subsequent bind dropping a still-bound
`NativeWindow`. Lifecycle-command prioritization is being implemented in an
open PR; it does not by itself establish correct EGL ownership after failure.

Suggested fix: acknowledge actual release, retain or deliberately leak the
old window when EGL cannot release it, and do not bind a replacement as if
detach succeeded. Test timeout, failed unbind, rapid background/rotation and
paint after rebind. Do not weaken `SurfaceHolder.Callback`'s lifetime contract
just to shorten a wait. Effort: one to two days with native/device proof.

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
Canonicalizing URL spellings, already being implemented in an open PR, is a
separate problem. Key rotation cannot be inferred to mean account replacement.

Suggested fix: inspect the deployed read-only identity contract or introduce
an explicit account-replacement operation that isolates old state. Do not
invent an endpoint or use token hashes as account IDs. Plan a preserving
migration and test two fixture accounts on one host, same-account rotation,
status separation and old-job resumption. Effort: one to two days after the
server contract/owner decision is available.

### Bound long-lived document and cancellation maps

performance problems, stuttering, and jank — important

`HisterIntegration.forgotten`, `LiveView.documentVisits` and native
document/retired maps can grow through repeated navigation/forget loops. A
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
accessibility tree for rendered page content. `EngineSurfaceView` also reports
itself as a text editor whenever the engine runs, rather than only for an
active page field. Blind users need page navigation and an accurate editor
state, not just labeled toolbar buttons. The inherited AccessKit APIs are
leads, not proof of a working Servo-to-TalkBack bridge.

Suggested fix: investigate the pinned downstream accessibility interfaces and
verify actual TalkBack reading, focus, links, fields, document replacement and
keyboard ownership. Test changing `onCheckIsTextEditor` with real IME reopen
behavior before narrowing it. Never claim chrome semantics establish page
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

## Nice-to-have

### Reconcile stale milestone and toolchain summaries

general issues — nice-to-have

SOL2-11: `README.md:16` still presents the original 18-check snapshot;
`docs/IMPLEMENTATION_PLAN.md:3,469` mixes Servo 0.6/no-Stop statements with
later implemented capabilities. `docs/BUILDING.md:36` lists Gradle 9.8.1
while the wrapper pins 9.8.2. Historical runs remain useful evidence, but
present-tense summaries can misdirect implementation and release assessment.

Suggested fix: reconcile summaries and verified toolchain tables with the
pinned source and dated run evidence. Preserve what was and was not tested,
including fixture versus deployed services and emulator versus physical phone;
do not manufacture a new acceptance claim from documentation cleanup.
Effort: about half a day.

### Validate safe upload names and resource policy

bugs — nice-to-have

`app/src/main/kotlin/ch/lkmc/combray/UploadStaging.kt` limits names to 120
UTF-16 characters, not the filesystem's UTF-8 component limit. Long CJK/emoji
names can still fail. Copies are streamed without per-pick/total byte budgets
and retained until restart, so repeated deliberately selected large
attachments accumulate for the whole process and unknown-length providers are
copied to completion. Startup lifetime/privacy fixes are implemented in open
PRs and are intentionally not repeated here. No page-to-JNI arbitrary
filesystem-path capability has been demonstrated.

Suggested fix: bound filename bytes on code-point boundaries while preserving
a useful extension. Agree documented limits and cancellation behavior before
rejecting legitimate large files; enforce them while streaming unknown-length
providers, roll back only the failed pick, and explain refusals with a useful
size/storage error. Release a copy only once Servo no longer needs its path.
Verify traversal/symlink/staging-root boundaries where actually exposed,
duplicate names, repeated large picks, cancellation and disk-full without
deleting active attachments. Effort: about one day after the policy is chosen.

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
non-lazily inside a scrolling Column. SOL2-13 also identifies
`SettingsSheet.kt:431,620` calling `Integrations.kt:141–145` to JSON-decode
full capture payloads during composition just to read their URLs. Queue
emissions can therefore parse a 20 MiB queue's worth of text on the UI path;
no phone frame-time measurement is claimed. Deliberate failed saves are
unbounded and must not be silently expired to hide the cost.

Suggested fix: project lightweight host/status data outside composition and
measure page/lazy rendering of large queues, retaining Retry/Discard and
destination binding. If metadata is persisted, use a preserving Room migration.
Keep explicit saves until the user resolves them. Effort: one to two days
including populated-queue migration and responsiveness checks.

### Explain failed automatic captures' budget retention

bugs — nice-to-have

FAILED automatic capture bodies remain in the seven-day transient budget, so
accumulated permanent failures can pause capture of other pages. The live
budget warning and replacement-aware transactional admission are already
implemented, the latter in open PR #206; neither needs another implementation.
The remaining question is the intended treatment and explanation of failed
automatic text, not the admission arithmetic.

Suggested fix: confirm and document that retention policy, explaining how
Retry, Discard and expiry release the budget. Cover failed-body expiry and
warning recovery without another capture, without silently discarding explicit
saves or reopening the fixed replacement bug. Effort: about half a day after
the policy is chosen.

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

### Enforce ServiceRoutes' one-request-per-attempt contract

general issues — nice-to-have

`ServiceRoutes.kt` documents a one-request callback, but its attempt transport
has no single-use guard. Current callers must be checked before claiming a
production double-send; the concern is future misuse permitting extra sends
before alternate-address failover.

Suggested fix: add a single-use guard if the contract remains public to
multiple internal callers, with tests that a second execution cannot send or
trigger unsafe failover. Preserve the distinction between NotConnected and
ambiguous delivery. Effort: a few hours.

### Handle runtime refusals without request identity

general issues — nice-to-have

`BrowserCoordinator.kt:1503–1512` ignores a tab-scoped refusal without a
request ID once the view has a generation. Such a runtime refusal can leave
the coordinator believing an unusable view is still on screen. The remaining
finding is this identified error-state edge, not startup idempotence,
Error/cancellation handling, queue bounds or wedged-step recovery, which are
implemented in open PRs.

Suggested fix: reproduce a refusal for a live generated view without a
request ID and define the correct recoverable state. Preserve the rule that
an old request refusal cannot clear a newer navigation's wait, and do not
make benign runtime engine notices fatal. Effort: about half a day.

### Fence the remaining forget-timer edge cases

bugs — nice-to-have

Forgetting already shares a transaction with cancellation, and
`pageForgotten` prevents requests/results for an open tab's forgotten page
from returning to history or capture. The inherited unresolved candidate is a
site shown without a Visit or a timer racing the forget transaction, not the
already fixed ordinary forgotten-page case. Claim/admission policy fencing is
being implemented in an open PR.

Suggested fix: reproduce those specific timer/document cases before adding
another tombstone mechanism. Cancel or fence only genuinely remaining delayed
captures, and verify no erased content is re-enqueued after restart. A direct
concurrent sink test is not proof of a normal coordinator Forget race.
Effort: about half a day after the open policy work is integrated.

### Keep recency deterministic when focus times tie

bugs — nice-to-have

`BrowserCoordinator.activate` rewrites the previous tab's `lastFocusedAt` to
`now - 1`, while MRU queries sort only that timestamp. Same-millisecond focus
changes can tie or move a timestamp backward, affecting All/MRU successor
selection. Fixed creation-time Open-tab/swipe ordering is already implemented
and is a different invariant.

Suggested fix: reproduce focus/close/undo ties with a fake clock, then use a
monotonic focus sequence or an explicitly justified timestamp/tie-break rule.
Do not reintroduce focus-based reordering into Open tabs or swipes. Effort:
about half a day including restart ordering.

### Document WorkManager replacement behavior

general issues — nice-to-have

`WorkManagerScheduler` uses `ExistingWorkPolicy.REPLACE`. Booking a new wakeup
while the unique worker is returning can cancel that finishing worker; the
process-wide dispatcher mutex currently makes this benign. The database, not
WorkManager, already remains the source of truth.

Suggested fix: document the rationale and test final-drain/wakeup races before
introducing multiple dispatchers or changing to append semantics. Empty queues
must not schedule endless fallback work. Effort: a few hours.

### Measure touch backlog and preserve final pointer coordinates

performance problems, stuttering, and jank — nice-to-have

Trace `EngineSurfaceView.kt`, `native/src/commands.rs` and the engine loop:
rapid multi-pointer input can queue work, and the loop processes a bounded
batch per turn. The inherited coalescing proposal has no current measured
input-latency reproduction. Lifecycle priority work has an open PR.

Suggested fix: measure before adding throttles. Do not coalesce input touch
positions: `engine.touch` feeds tap and touch-quiet trackers, and dropping a
drag-out-and-back path can manufacture a false tap. The concurrent Devin-1
review confirms lifecycle-priority work instead deduplicates Wake per batch.
Any future render-only coalescing must retain every point for gesture/activation
trackers, deliver final coordinates and preserve down/up/cancel order. Keep
the existing long-press clock rule: measure from app handling, not the queued
event timestamp. Effort: about one day with real touch fixtures.

### Profile stateful publication

performance problems, stuttering, and jank — nice-to-have

Inert events already skip coordinator `publish()`, and tab counts use
`SELECT COUNT(*)`. Stateful title/load bursts still query/publish. Stable
browser-screen callbacks have an open PR, so that implementation is not
repeated here. The old emulator idle-timeout observation is not a current
phone benchmark.

Suggested fix: count Room queries and use frame/recomposition traces before
adding cached snapshots or dirty flags. Preserve navigation/title delivery
and transactional counts; isolate remaining broad state reads only where
measurements justify it. Effort: about one day.

### Profile Recent ranking and database query plans

performance problems, stuttering, and jank — nice-to-have

Recent can re-sort after flow emissions; search now correctly covers retained
history and caps after filtering. Address suggestions rank parsed hosts, and
several DAO paths have possible index/N+1 costs: history filtering/order,
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
forms or POST state. The six-view cap and memory-pressure eviction therefore
have a visible cost even though persistent logical tabs are correct. Full
Servo session export/restore is not established.

Suggested fix: document and measure the loss, consider a bounded scroll seam
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

The native capture path expands a large script template for requests, and
`HisterClient.boundedBody` can rebuild a near-MiB JSON body through truncation
iterations. The earlier claim that every HttpURLConnection request necessarily
pays a new TCP/TLS handshake also needs measurement against actual pooling
behavior, not assumption.

Suggested fix: benchmark template expansion, serialization and repeated
requests with synthetic payloads; cache stable script parts or improve bounded
construction where useful. Any connection reuse must preserve no-redirect,
credential isolation, response caps and destination identity. Effort: about
half a day to measure, then a bounded optimization.

### Measure capture's worst-case responsiveness

performance problems, stuttering, and jank — nice-to-have

`native/src/capture.js` checks its time budget between groups of 128 nodes,
and work within one node (a huge text node's whitespace normalization, a
page-defined getter) is not clocked. The documented time budget therefore does
not bound a single huge node. This is an acknowledged engine limitation, not a
new measured freeze.

Suggested fix: benchmark synthetic huge-node and style-heavy pages with
frame/input timing; bound preprocessing where it helps and pursue interruption
only in the downstream engine. Do not claim a callback timeout stops
JavaScript. Effort: about one day for evidence, further work depends on the
result.

### Preserve visible context while remote search waits

usability: fast, convenient, clear — nice-to-have

`HisterSearchPanel.kt:69–75` and `KarakeepPanel.kt:74–78` clear old answers
on each keystroke, leaving blank space during debounce/network delay.
Pagination single-flight/error handling is already implemented in an open PR
and is not a second task here.

Suggested fix: try retaining the prior answer visibly tagged as belonging to
its old query, with stale rows disabled or unmistakably distinct, until the
new answer arrives. Keep errors separate from empty results and never relabel
old rows as current. Effort: about one day with delayed-query/configuration
regressions.

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

### Make hidden Recent scopes discoverable

visual issues and layout problems — nice-to-have

`RecentSheet.kt:194–207` puts Open tabs, All, Hister and Karakeep in one
horizontal scroller without an overflow cue. Narrow widths or 200% text can
hide core service scopes. Date headers, active/open markers, host tiles and
times already exist; this is not a request to rebuild Recent.

Suggested fix: verify at 320dp/200% type, then wrap chips or add an unobtrusive
trailing cue while keeping single-choice TalkBack semantics and local/remote
scope clarity. Cover duplicate tabs, the last row and pagination with the
keyboard up. Effort: about half a day.

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

`SettingsSheet.kt:379–402` uses a non-wrapping Save/Check connection action row
inside 48dp of horizontal margins. Large fonts, split-screen and longer
translations can squeeze its labels or wrap the buttons awkwardly. Its full
narrow/large-font accessibility matrix is not recorded. Scrolling Home is
already implemented and recovery-overlay scrolling has an open PR; those code
fixes are not repeated here. Settings/Recent still need independent bounds and
reachability evidence. An unweighted LazyColumn alone does not prove clipping.

Suggested fix: measure 320dp, 200% type, landscape and keyboard-visible states
before changing layout. Use a wrapping/adaptive action layout, verified with
long labels at 320dp/200% while keeping 48dp non-overlapping touch targets.
Check last rows, page actions and snackbar bounds; do not add blanket
modifiers. Effort: about one day including Settings/Recent accessibility
coverage.

### Preserve readable origin under constrained layouts

visual issues and layout problems — nice-to-have

Start ellipsis preserves a suffix, not necessarily a complete registrable
domain. Address/chrome target tests do not prove that long IDNs, subdomains,
RTL and large text leave the site's identity understandable. The separate
address row at narrow widths and capped tab badge are already implemented.
Search result rows (`KarakeepPanel.kt`, `HisterSearchPanel.kt`) clip long hosts
at the row edge with no ellipsis, unlike their titles.

Suggested fix: inspect actual rendered origins at 320/360dp, 200% text, long
hosts and large counts. Keep engine-derived failures/cleartext indications,
not a decorative verified lock; Page Info must not infer certificate
verification from HTTPS. Give search result hosts a deliberate overflow
(ellipsis or a second line) and retain the complete accessible label. Test
0/1/99/100 badge boundaries and exact TalkBack counts without changing logical
tab state. Effort: about half a day.

### Cap deep Reader list indentation

visual issues and layout problems — nice-to-have

`ReaderView.kt` adds 16dp of start padding per list level before the marker and
text. Nested instructions become narrow on phones and at larger type; no
screenshot defect is claimed.

Suggested fix: prototype a width-aware indentation cap while keeping semantic
nesting and ordered markers; compare narrow, RTL and large-type examples.
Effort: about half a day.

### Give local Recent a useful keyboard completion action

usability: fast, convenient, clear — nice-to-have

`RecentSheet.kt:209–216` has a single-line filter with no explicit IME
completion action. The keyboard unnecessarily occupies list space, while
remote fields request Search but need their actual action behavior verified.

Suggested fix: use Done to hide the local keyboard without clearing its
query; wire remote Search deliberately without bypassing privacy/submission
rules. Verify Android Back and focus behavior with nested sheets. Effort:
a few hours plus UI/IME tests.

### Improve failure copy and save receipts

usability: fast, convenient, clear — nice-to-have

`SettingsSheet.kt:611–628` exposes safe but technical categories such as
`http_401` and can fall back to an opaque dedupe key. Save status also lacks a
compact acknowledged destination/list receipt and direct details/retry path.
A new unexpected-response category could improve proxy/captive-portal copy,
but `WrongAddress` currently intentionally includes reachable non-service
responses; that naming suggestion is not a connection-check correctness bug.

Suggested fix: map categories to localized recovery actions, use Unknown page
instead of opaque IDs, and distinguish Pending, Saved, partial membership and
Needs attention in text as well as color. Preserve the original payload and
destination through switching/response loss. Localize coordinator problems
through typed notices mapped to app resources, not resource IDs in core.
Effort: about one day. Creation-receipt persistence is implemented in open
PR #230; this is the remaining presentation and recovery task.

### Give address and state changes clear accessibility context

usability: fast, convenient, clear — nice-to-have

Recent and Settings have headings; address entry has an On this device heading
only when suggestions exist. Engine starting/missing/failed/crashed overlays
and load starts need a current TalkBack announcement check. The old More-sheet
implementation has since become a toolbar menu, so its earlier missing-heading
claim must be reverified rather than copied blindly.

Suggested fix: add appropriate heading/live-region context only after testing
focus and announcement behavior. A crash or changed loading state must not
require manual exploration, and routine progress must not become announcement
spam. Effort: about half a day with TalkBack.

### Verify keyboard, focus and inset stability on real windows

visual issues and layout problems — nice-to-have

Address focus is requested from `LaunchedEffect`; actual sheet/window timing,
IME resizing and snackbar clearance still need physical-device evidence.
Existing main-thread state collection, inline Home entry, page Paste/Back
handling and measured bottom-bar padding should not be replaced based on the
older review's assumptions.

Suggested fix: exercise address open/type/suggestion selection/rotation with
IME up, page autofocus while an address sheet owns focus, keyboard Back and
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

### Give Recent rows copy and share actions

usability: fast, convenient, clear — nice-to-have

The visit menu has Remove and Forget but no direct Copy link/Share. Address
long-press copy and page Share already exist, so this is a convenience gap,
not a request for another clipboard backend. Optional long-press Share or a
visible Paste and go route should not obscure existing accessible actions.

Suggested fix: reuse safe native clipboard/chooser paths, keep exact recorded
URLs and tab identities, and test API 30–32 versus Android 13+ confirmation.
Verify a physical long press, actual clipboard contents and TalkBack, not only
an exposed semantics action. Never read the clipboard in the background.
Effort: about half a day.

### Make destructive history actions understandable and reversible

usability: fast, convenient, clear — nice-to-have

Tab close has Undo, but removing a visit/forgetting a site and removing Hister
exclusions have different immediate effects. Exclusion removal can enable
future capture; users need clear scope and useful empty-list guidance. A
short-lived Forget undo would also have to restore affected capture state
atomically, not just paint the row back.

Suggested fix: choose confirmation or a truthful Undo per operation, explain
local versus remote deletion, and keep explicit close-and-forget semantics.
Use the available bounded title in Undo close with an untitled fallback,
preserving whether the closed tab was focused. Effort: about one day; never
claim an already delivered remote capture can be unsent.

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
also need verified downstream hooks; a long-press image-download slice has an
open PR, so it is not repeated here. A general download manager or broad
browser parity is not promised, and no general save-to-device workflow exists
yet: the PDF viewer is not one.

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

Inherited native features such as test bindings, Bluetooth, WebXR/WebGPU and
experimental capabilities need an inventory of what is actually enabled.
Content runs in the app process; Android's application sandbox is not proof
of a separate content-process sandbox or private-browsing guarantee. Engine
updates must retain a small downstream patch/update path.

Suggested fix: inspect the pinned feature set and supported sandbox mode,
change defaults only with compatible builds and explicit evidence, and keep
all changes/reports downstream. Extract host-testable ownership rules when
touching Android-only engine code; do not treat host tests as device coverage.
Effort: about one day for an inventory, then bounded maintenance slices.

### Close focused regression and validation gaps

general issues — nice-to-have

Inherited cheap gaps include Notice.Problem reaching its snackbar, failed
visits/current-open visits in search, literal `%`/`_` plus Unicode case/accent
matching, pasted URL tabs/newlines, and one full address
suggestion/rotation/IME flow. Fixture ports
remain fixed at 8766, which complicates concurrent isolated sessions; sol-2's
final capture rerun hit a real port collision before a successful retry. Old
review-specific runs are not proof of today's integrated tree. Sol-2 also saw
one report-service SIGTERM test timeout on Python 3.15; its focused rerun
passed; all 207 service tests also passed on local Python 3.13. The separate
nesting regression has open PR #202. Recheck the full service suite on a
proposed replacement runtime without presenting that one timeout as a
confirmed production shutdown defect.

Suggested fix: add independently expected regressions where missing,
parameterize fixture ports for isolated sessions, and rerun the relevant
native/device boundary after integration. Do not normalize pasted URL
whitespace blindly: replacing every whitespace character can change meaning.
Historical Sol validation had a
System UI ANR and an incomplete Reader gate; its exact evidence is in
[ENGINE_SPIKE section 14](docs/ENGINE_SPIKE.md#14-sol-review-validation-21-october-2026).
Fusion's archived results under
`~/.local/share/devin/cli/combray-fusion-artifacts-20261021/` included JVM gates
and five selected accessibility cases without a staged engine, not a usable
signed release or phone performance claim. Effort: about one day for the
focused test additions.

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
from hung. This is separate from the startup-lifetime fix already in open PRs
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

### Key the Hister search effect on server identity, not the whole row

bugs — nice-to-have

`HisterSearchPanel.kt:67` keys its search effect on `LaunchedEffect(query,
service)`, the whole `ServiceEntity` including `updatedAt`, so any settings
save that rewrites the row (even one that only drops the alternate address)
clears the results and re-searches. `KarakeepPanel.kt:70` was keyed on
fingerprint and generation by #100; the Hister panel was not.

Suggested fix: key the effect on `service?.fingerprint, service?.generation`
like the Karakeep panel. Effort: trivial plus a test.

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

### Fix the address policy's space-handling comment

bugs — nice-to-have

`AddressPolicy.kt:97-99` documents that a known-scheme address navigates "as
typed", but `AddressPolicy.kt:108` encodes its spaces as `%20` first.

Suggested fix: correct the doc comment to match the encoding (or stop encoding
if the engine is meant to parse the raw text). Effort: trivial.

### Offer copying the article's address from Reader view

novel, delightful, or quirky ideas worth trying — nice-to-have

Reader view's top bar (`ReaderView.kt` `ShowPageBar`) offers only "Show the
page"; a passage's source cannot be copied or shared without leaving the
article.

Suggested fix: add a "Copy link" action next to "Show the page", reusing
`copyAddressToClipboard` (sensitive-marked like the bar's long-press copy).
Effort: small.
