# Instructions for implementation agents

## Read first

Read [README.md](README.md), then [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), then [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md). These files carry the project context; do not require the original chat to understand the work. The plan separates confirmed requirements, proposed defaults, and unresolved questions. Keep those distinctions when making changes.

## Product boundaries

- The app is **Combray**, a personal **Android** browser. **Servo is mandatory.** Do not replace it with Android WebView, Chromium, GeckoView, a remote renderer, or an external browser to pass a milestone. Report a concrete blocker and preserve the Servo direction.
- Keep routine UI actions at the bottom. Tabs and short-term history share one Recent UI. Karakeep saves, Hister content/history search, and Kagi web search are core requirements.
- Preserve the existing `LICENSE` and `media-sources/icon.png`. Icon artwork is square, opaque, edge-to-edge, with sharp outer corners. Launcher masking is Android's concern; do not bake a squircle into the source artwork.
- Existing HTML is a design demo with sample data, not the browser or evidence of working integrations. Two layout alternatives remain; a proposed default is not a user-confirmed decision.
- Personal use does not mean arbitrary sites, page content, tokens, or service requests are trusted. Never put native integration credentials in page JavaScript, navigation URLs, committed configuration, logs, or screenshots.

## Implementation discipline

1. Start with milestone M0. Prove independent Servo views, lifecycle handling, and correctly attributed text extraction on Android. Record exact revisions, commands, environment, results, and remaining limitations in `docs/ENGINE_SPIKE.md` when it exists.
2. Keep Servo handles on their owner thread. Tag callbacks with tab identity, engine generation, and navigation identity. An equal URL is not proof of an equal document.
3. Separate persistent logical tabs, visits, and native views. Closing, expiring local history, forgetting, and deleting remote content have different semantics.
4. Make integration operations durable and tied to their original payload and destination. Test delayed callbacks, ambiguous network completion, process death, and deletion races, not just happy paths.
5. Pin dependencies and toolchains once verified. Do not invent a Maven Servo artifact, JNI API, build command, or compatible version combination. Keep future Gradle wrapper files and dependency lockfiles in version control.
6. Implement bounded vertical slices. Update the plan's status and record decisions after evidence changes. Do not mark a feature complete from fake engine events or mocked HTTP alone.
7. Run checks appropriate to the change. For this documentation bootstrap: `python3 scripts/check_docs.py` and `git diff --check`. Add native tests/build checks when there is native code. Do not claim device tests that were not run.
8. Prefer small patches and inspect the actual diff. Do not commit, push, publish, or change the user's services unless the active task authorizes it. Do not read or export personal history to validate a fixture-based feature.

## Subagents

Proactively delegate suitable work to available free or cheaper subagents running in other harnesses.

## Identity

- Proposed application id and Kotlin namespace: **`ch.lkmc.combray`** (fleet rule: GitHub repos use `ch.lkmc.*`, lowercase on Android). The plan leaves the id open until M1; confirm it there before persistent data or signing rely on it. The debug build adds `applicationIdSuffix = ".debug"`, so both variants install side by side; `scripts/install.sh` launches by these ids.
- Launcher icons derive from `media-sources/icon.png` and are committed; document the generator next to them.

## Build and release tooling

The family's build, install, release, and CI tooling is in place ahead of the app. It stays inert until the Gradle project exists, then switches on without workflow changes.

| What | Command |
|---|---|
| Documentation check | `python3 scripts/check_docs.py` |
| Release APK → `dist/` | `scripts/build.sh` |
| Debug APK → `dist/` | `scripts/build.sh --debug` |
| Build, install, launch on a device | `scripts/build.sh --install` (or `scripts/install.sh [--release]`) |
| Resolved config, no build | `scripts/build.sh --check` |
| CI gate | `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` |
| Cut a release | `scripts/release.sh X.Y.Z [--push]` |

`scripts/release.sh` is a stub over the shared `lkm-release` engine (`gradle-android` kind, https://github.com/L-K-M/release-tool): it bumps `versionName`, increments `versionCode`, updates the README version marker, commits, and tags `vX.Y.Z`; `--push` pushes branch and tag, and the tag runs `.github/workflows/release.yml` (tag/versionName gate, tests and lint, release APK, GitHub Release).

Published assets follow the fleet naming rule `<base>-<vX.Y.Z>-<os>-<arch>`: `combray-v0.1.0-android-arm64.apk` plus its `.sha256`. The arch is `arm64` because Servo ships per-ABI native libraries and M0 starts with arm64; update `ARCH` in `scripts/build.sh` and `release.yml` when M0 settles the ABIs.

## When the Gradle project lands

1. Create it at the repo root with the APK module named `app`, and commit the Gradle wrapper (`gradlew`, `gradle/wrapper/*`). CI's Android job switches on once `gradlew` and `app/build.gradle.kts` exist; until then it passes with a notice.
2. In `app/build.gradle.kts`, set `namespace` and `applicationId`, and give the debug build type `applicationIdSuffix = ".debug"`.
3. Declare `versionName = "0.1.0"` and `versionCode = 1` as plain literals: the release engine and the CI tag gate read and rewrite them with `sed`.
4. Move the Android job in `.github/workflows/ci.yml` and the build job in `release.yml` to the toolchain M0 pins (JDK, NDK, Rust, Servo build steps). Both assume JDK 17 and a plain Gradle build today.
5. Configure release signing before the first release tag (M4). Keep signing keys out of Git; `release.yml` expects a signed `app/build/outputs/apk/release/app-release.apk`.
6. Add `gradle` (and, once a crate exists, `cargo`) entries to `.github/dependabot.yml`.
7. Generate and commit the launcher icons from `media-sources/icon.png`.

## Conventions

- Work on a branch cut from the latest `origin/main`; every change lands through a PR with green CI and the GLM review.
- No secrets in the repo or the workflows.
- Keep `CHANGELOG.md` current under `Unreleased`.

<!-- shared-rules:start -->

## Working practices

- Follow explicit task instructions over the default workflow below.
- Writing the code is not finishing the task. A task is finished when
  its changes are merged to main through a PR that passed CI and review,
  or when the user explicitly accepts a different end state.
- Start every task on current code. Fetch first, then cut the task
  branch from origin/main — never from a stale local branch or an old
  checkout. To continue existing work, rebase or merge the latest
  origin/main into it before editing. Never overwrite existing work to
  update.
- Resolve ambiguity before making consequential changes. State low-risk
  assumptions; ask when scope, safety, or expected behavior is unclear.
- Keep changes focused. Do not modify unrelated code, formatting, or comments.
- Prefer surgical edits over whole-file rewrites when the result is equivalent.
- Stage only intended files. Inspect the diff before committing.

## Communication

- Be concise, factual, and direct. Preserve necessary context and uncertainty.
- Avoid praise, motivational filler, emojis, and em dashes in new prose.
- Address the reader directly in user-facing copy.
- Report what was verified and what remains unverified. Never imply that an
  unavailable check passed.

## Code design

- Prefer early returns and shallow nesting. Separate logical blocks with
  blank lines.
- Use descriptive constants or enums for meaningful or repeated values.
  Use existing standard definitions for protocol/specification constants.
  Keep obvious, one-off values inline.
- Use enums for behavioral modes that would otherwise require ambiguous
  boolean arguments.
- Default members to private. Widen visibility only for required consumers,
  and review the change as an API design decision.
- Follow the repository's declared dependency boundaries. UI and controllers
  must use application services rather than directly accessing databases,
  subprocesses, sockets, or other low-level mechanisms.
- Encapsulate low-level mechanics behind domain-oriented interfaces.
- Reuse genuinely shared logic. Avoid speculative abstractions and layers
  that only forward calls.
- Prefer pure functions for business rules and immutable data where practical.
  Isolate side effects; document non-obvious state ownership or synchronization.
- Explain non-obvious intent, constraints, and tradeoffs in comments.
  Do not narrate obvious code. Add examples or diagrams when they clarify it.

## Validation and errors

- Validate untrusted input at entry points. Where practical, represent valid
  states in types and enforce persistent invariants in database schemas.
- Represent absence and failure explicitly.
- Use assertions for internal programming invariants, not external-input
  validation or required runtime error handling.
- Prefer explicit, actionable errors over silent failure or undocumented
  fallback. Document intentional recovery behavior.
- Never report a skipped or failed operation as successful.

## Bug fixes

1. Identify the root cause and define an observable success criterion.
2. Add a regression test and observe the relevant failure before fixing it.
3. Implement the fix and observe the test passing.
4. Check surrounding behavior for regressions and architectural consistency.

If an automated regression test is impractical, document the reproduction
and verification procedure. State any inability to reproduce the failure.

## Verification

- Run relevant tests and lint after changes.
- Choose coverage by affected behavior and risk, not patch size.
- Use integration or end-to-end tests for critical workflows and boundaries;
  test isolated business rules at the lowest effective level.
- Run broader suites for cross-cutting or high-risk changes, and the full
  required release checks before releasing.
- Validate the requested command, options, platform, and configuration.
  Unrelated green CI is not proof that the reported problem is fixed.
- Recheck after the final edit. Distinguish local checks from CI results.

## Commit messages

- Use a capitalized, imperative subject without a final period.
- Target 50 characters; never exceed 72.
- Separate the subject and body with one blank line.
- Wrap body text at 72 characters.
- Explain what changed and why. Leave implementation mechanics to the code.

## Implementation and review

Unless explicitly instructed otherwise:

1. Work on a focused branch cut from the latest origin/main and open a PR
   against main before reporting the task as done.
2. Inspect CI results and completed review feedback for the latest commit.
   A successful reviewer job does not mean the review found no problems.
3. Address important findings or explain why they do not apply. Handle minor
   findings according to the stopping rules below.
4. Evaluate each fix in the surrounding project, add regression coverage,
   and rerun affected checks before pushing.
5. Repeat until a stopping criterion is met.
6. Merge without asking again once the stopping criterion is met, required
   checks pass on the latest commit, and no unresolved blockers or required
   human review requests remain.

### Reviewer context limits

The automated PR reviewer does not see the user's original prompt or
conversation. It may suggest changes that go against or beyond what the
user asked for. Do not implement such suggestions. Note each conflict and
report it to the user at the end of the thread.

### Automated review stopping rules

Judge findings by verified impact, not the reviewer's severity label.
Important findings concern correctness, security, data loss, broken builds,
or materially degraded behavior/performance.

Track completed review rounds and consecutive rounds without important
findings. Reruns of the same revision and integration failures do not count.

- No applicable actionable feedback: finish immediately.
- First minor-only round: optionally fix worthwhile, low-risk findings.
  Do not manufacture another push merely to obtain another review.
- Two consecutive rounds without important findings: stop responding to
  automated nitpicks, even if actionable minor suggestions remain.
  Defer worthwhile leftovers rather than continuing the cycle.
- A confirmed important finding resets the minor-only streak. Address it
  and verify the fix before continuing.

After ten completed rounds, enter stabilization:

- Stop optional cleanup, refactoring, and nitpick fixes.
- One completed review without confirmed important findings is sufficient
  to finish, even if minor suggestions remain.
- Continue only for confirmed important defects. If resolving them stalls,
  report the blockers rather than continuing indefinitely.

These limits end optional automated-feedback work. They do not waive
confirmed blockers, unresolved human review requests, or required checks.

### Reviewer integration failures

After two consecutive reviewer-integration failures, stop and report the
review gap. Do not treat failures as approval. An explicit user instruction
may waive review; report that waiver rather than claiming review passed.

## Ending a task

- A task ends with its changes merged to main — not with code written,
  and not with a PR merely opened. An open PR is work in progress:
  monitor CI on the latest commit, address review findings per the
  stopping rules, and merge once the criteria are met.
- Never finish with uncommitted changes or unpushed commits in the
  worktree. Commit, push, and open or update the PR first.
- If a step is impossible (missing push access, CI failure, reviewer
  outage), report the exact blocker instead. Never present unreviewed or
  unmerged work as finished.
- Before finishing, confirm: the requested behavior is implemented
  without unrelated changes; relevant checks pass on the latest code;
  important review findings are addressed or rejected with reasons;
  deferred suggestions, remaining risks, and validation gaps are
  disclosed.
- The final response states where the work stands: branch, PR, CI
  status, review rounds completed, and whether it is merged.

<!-- shared-rules:end -->
