# Site checks and nightly engine maintenance

Combray's engine is only as useful as the sites it renders. This document describes how the sites the owner actually visits are checked in Servo, and the nightly task that fixes what breaks, follows upstream releases and moves the engine pin.

**Upstream stays fetch-only.** Nothing here contacts `servo/servo` beyond `git fetch`/`git ls-remote` of public source. All fixes, PRs and reviews live in `L-K-M/servo` and this repository.

## Pieces

| Piece | Where | Role |
|---|---|---|
| Site-check harness | [`testing/sitecheck/`](../testing/sitecheck/README.md) | Loads pages in a desktop servoshell and in headless Chromium, compares them, records verdicts |
| Desktop Servo build | `~/Documents/GitHub/servo-sitecheck` (a worktree of the downstream repository) | `./mach build --profile medium --media-stack dummy`; the same engine source as the app's pin, with Combray's preferences, user agent and phone viewport |
| Combray worktree | `~/Documents/GitHub/combray-sitecheck` | The harness checkout the nightly task runs, and where it prepares pin and harness PRs |
| History source | the owner's Hister server | `GET /api/history`, paged; credentials in `~/.config/combray-sitecheck/hister.env` |
| Private state | `~/.local/share/combray-sitecheck/` | Page inventory, results, screenshots, reports, the nightly ledger |
| Nightly task | a scheduled Claude Code task on the owner's Mac, 01:00 local time | Runs the procedure below |

The desktop build approximates the Android engine: same Servo source, preferences mirrored from `native/src/engine.rs` (a unit test fails when they drift), Servo's Android user agent and a 412×915 CSS-pixel viewport. Platform code (fonts, GPU, input) differs, so a clean desktop result is evidence, not proof, of a working page on the phone.

## Privacy and safety

- Browsing history is private. URLs, hosts, titles, page text, screenshots, error messages, issue signatures and the site-to-bug mapping stay in the private state directory (mode 0700). Commits, branches, PRs, issues and tests never name a site from the history or quote what it produced; bugs are described through reduced test cases written from scratch. `sitecheck leakcheck` scans commits (their lines, file names and messages), branch names and PR text for hosts and URLs from the inventory before anything is pushed; a host the repository already mentions elsewhere is no exception in messages or PR text.
- Re-opening history entries must not act on the owner's behalf. Both engines load each page with a fresh, empty profile (no cookies, storage or cache), and the URL policy skips anything that looks like an action or carries a secret: sign-in and sign-out, confirmation, unsubscribe, reset, RSVP and voucher links, payment and account pages, opaque tokens in query values or path segments, private and local hosts (router, NAS and tailnet names included, and hosts that resolve to private addresses), downloads, and search result pages. A page that redirects or navigates to such a URL is discarded and not loaded again. The deny list and the policy apply to pages already stored. When in doubt, a URL is skipped.
- The Hister token is read from its file by the harness, never printed or passed on a command line, and kept out of the environment of every engine process.
- No engine outlives the harness: each servoshell runs under a supervisor that kills it when the harness dies, however it dies. servoshell's WebDriver server still listens on every interface until the downstream build binds it to loopback; `sitecheck doctor` reports it, and incoming connections to the Mac should be blocked while the nightly runs.

## Configure Hister

Edit `~/.config/combray-sitecheck/hister.env` (keep it `chmod 600`):

```sh
HISTER_URL=https://hister.example.net
HISTER_TOKEN=<access token of your Hister user>
```

`sitecheck doctor` reports whether it is configured and reachable without printing the token. Without it the harness checks the public seed list in `testing/sitecheck/seeds.txt`.

## Verdicts

Each page gets one verdict from comparing Servo with Chromium on the same URL, user agent and viewport: `crash`, `hang`, `blank`, `content-missing`, `js-error`, `layout-overflow`, `images`, `slow-load`, `visual-diff`, `flaky` or `ok`. Pages without a viewport meta tag, which Chromium's mobile mode lays out in a wider default viewport, overflow under their own signature (`layout-overflow:viewport`): that is what the phone shows today, and a Servo gap rather than a site bug. Pages Chromium cannot load (or that hang it too) are `reference-failed`, pages checked without Chromium are `not-compared`, pages discarded by the URL policy are `excluded`, and none of these enter the triage queue. Failures against a cached Chromium result are judged again against a fresh one, and failures are retried once in a fresh process before they count. Root-cause signatures (a panic location, a normalized script error) group one cause across sites into one issue; symptom signatures (`blank:text`, `hang:...`, `content-missing`) carry a tag of the page's site, so marking one site's symptom `wontfix` leaves other sites alone. Details and thresholds: the harness [README](../testing/sitecheck/README.md).

## The nightly task

1. **Follow-up:** reads its private ledger, handles review feedback on its open PRs and merges the ones that passed their gates.
2. **Upstream** (Sundays, or after seven days without a check): merges new `release/v0.N` backports into the current `combray/0.N` branch, or starts `combray/0.M` for a new upstream release and merges the release tag there. Validates the merge with a build, tidy checks, our regression tests and a full site check, then drops downstream commits upstream now covers.
3. **Site check:** syncs new pages from Hister, selects about 60 pages (open failures first, then unchecked and least recently checked sites, at most three pages per site), runs them and writes a private report. A run that could not judge most of its pages exits non-zero and is reported instead of triaged.
4. **Fixes:** picks at most two issues by severity and reach, reduces each to a synthetic test case, backports an upstream fix when one exists or writes a spec-conformant fix, adds a regression test, validates (build, tidy, WPT directories of the area, affected pages, a regression sweep), gets an independent review from another model family, and merges into `combray/0.N` in `L-K-M/servo` (which has no CI of its own).
5. **Pin:** when the downstream source changed, opens a Combray PR moving `scripts/m0/servo-pin.sh` (following [BUILDING.md](BUILDING.md#updating-servo)) and merges it once the Engine workflow's native builds and emulator checks and the GLM review pass.
6. **Report:** updates the ledger and writes a private run report; its final message to the owner lists what landed and what needs them.

The task may not force-push, rewrite history, touch other repositories, or change its own instructions; it opens PRs that would widen its own permissions for the owner to decide.

## Run it by hand

```sh
cd ~/Documents/GitHub/combray-sitecheck/testing/sitecheck
uv sync && uv run playwright install --only-shell chromium
uv run sitecheck doctor
uv run sitecheck sync
uv run sitecheck run --servo ~/Documents/GitHub/servo-sitecheck/target/medium/servoshell --budget 20
uv run sitecheck report
uv run sitecheck check --servo ~/Documents/GitHub/servo-sitecheck/target/medium/servoshell https://example.com
```
