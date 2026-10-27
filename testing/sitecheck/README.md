# sitecheck

`sitecheck` loads websites in a local desktop build of Combray's downstream Servo
(`servoshell`) and in headless Chromium, compares the two, and keeps a private,
prioritized list of pages that do not work in Servo. The pages come from the owner's
browsing history in Hister, plus a public seed list. The nightly engine maintenance task
([docs/SITECHECK.md](../../docs/SITECHECK.md)) uses it to find Servo bugs worth fixing
and to confirm the fixes.

It is a measuring instrument: it never changes Servo, never talks to GitHub, and never
contacts the upstream Servo project.

## Privacy and safety

- **History stays private.** Everything derived from history (URLs, hosts, screenshots,
  page text, logs, results, issue signatures, reports) lives in the data directory
  (`~/.local/share/combray-sitecheck/`, mode 0700, files 0600). Nothing is written to the
  repository. Progress lines show a page's host, never its URL. `sitecheck leakcheck`
  scans commits, branch names and PR text for hosts and URLs from the store, and for the
  hosts pages led to while loading (redirect targets, script sources), before anything
  is pushed.
- **Excluded pages are kept by hash.** A history URL the policy excludes is stored as
  `excluded:<hash>` with its host and site only, so tokens and search queries in such
  URLs are not kept, while the host still feeds the leak check.
- **The Hister token** is read from `hister.env` or the environment, sent only in the
  `X-Access-Token` header to the configured origin (redirects to other origins are
  refused, proxies are bypassed), and never printed, logged or passed on a command line.
  Once read, the Hister settings are removed from the process environment, and
  `servoshell`, Playwright's driver and Chromium get an allowlisted environment, so the
  token never reaches an engine.
- **Re-visiting history must not act for the owner.** Both engines load each page with
  a fresh, empty profile (no cookies, no storage, no disk cache, Chromium's service
  workers blocked), using GET only. The URL policy (`sitecheck/urlpolicy.py`) skips
  anything that looks like an action or carries a secret: sign-in and sign-out,
  confirmation, reset, unsubscribe, RSVP, accept, claim and redeem links, payment,
  account and admin pages, opaque tokens in query values or path segments (a JWT, long
  hex, long mixed runs), e-mail addresses in query values, private and local hosts
  (including router, NAS and tailnet names such as `fritz.box` or `*.ts.net`, judged
  as the browser maps them), non-default ports, paths with `..` segments, downloads and
  search result pages. Before a run loads a planned page, its host is resolved: a host
  that resolves to a private address is excluded for good. A page that ends up at a URL
  the policy excludes (a redirect, a script navigation) is discarded and never planned
  again; Chromium blocks such script navigations, but a server redirect is followed once
  by Servo before the harness can see it. When in doubt, a URL is skipped. The policy
  (with the deny list) is applied again to the stored pages before every run, `select`,
  `seed` and `sync`. URLs named on the command line skip the history-oriented
  exclusions (local test servers and files are how reduced test cases are checked) but
  are still refused when they carry credentials, match the deny list, point at the
  Hister server or look like an action.
- **No engine outlives a command.** Each page gets its own servoshell process group,
  killed when the page is done; an atexit handler and the SIGINT, SIGTERM and SIGHUP
  handlers kill any that remain. The group is led by a small supervisor
  (`sitecheck/supervisor.py`) that kills it, and removes the page's profile, when the
  command dies without running any handler (SIGKILL, a memory-pressure kill). Chromium
  stops with the command.
- **servoshell's WebDriver server listens on every interface** (the downstream build
  has no option for the address). The harness takes the session as soon as the server
  is ready, but until the downstream build binds 127.0.0.1, a host on the network that
  connects first could drive the engine and read local files. `sitecheck doctor` fails
  and `run`/`check` warn when the port answers on the machine's network address; block
  incoming connections to this machine (the macOS firewall's "Block all incoming
  connections") while the harness runs.

## Setup

```sh
cd ~/Documents/GitHub/combray-sitecheck/testing/sitecheck
uv sync                                                    # Python 3.12, Playwright, Pillow
uv run playwright install chromium --only-shell           # headless Chromium (~200 MB)
```

Build the desktop Servo in the `servo-sitecheck` worktree of the downstream Servo
repository (same source as the app's engine pin):

```sh
cd ~/Documents/GitHub/servo-sitecheck
./mach build --profile medium --media-stack dummy
```

`sitecheck` finds `~/Documents/GitHub/servo-sitecheck/target/medium/servoshell` by
default; `--servo PATH` or `COMBRAY_SITECHECK_SERVO` picks another build. The Servo
version for the user agent is read from the checkout's `Cargo.toml`, and the revision
recorded with each run from `git rev-parse HEAD` in the checkout.

### Configuration

All optional, in `~/.config/combray-sitecheck/` (or `$COMBRAY_SITECHECK_CONFIG`):

- `hister.env` (keep it `chmod 600`; sitecheck warns otherwise). `KEY=VALUE` lines,
  `#` comments, optional quotes. It is parsed as data, never executed. `HISTER_URL` and
  `HISTER_TOKEN` in the environment override it.

  ```sh
  HISTER_URL=https://hister.example.net
  HISTER_TOKEN=<access token of your Hister user>
  ```

- `deny.txt`: one host suffix (`example.com` also covers `www.example.com`) or http(s)
  URL prefix per line (an exact URL from the address bar works too). Matching pages are
  never loaded, including pages stored before the entry was added.
- `seeds.txt`: extra seed URLs, one per line, added by `sitecheck seed`.

Environment: `COMBRAY_SITECHECK_DATA` moves the data directory (tests and trial runs use
a temporary one), `COMBRAY_SITECHECK_CONFIG` the configuration directory,
`COMBRAY_SITECHECK_SERVO` the default servoshell.

## Commands

Run them with `uv run sitecheck <command>` (or `uv run python -m sitecheck`). Exit
codes: 0 success; 1 findings (`check`, `leakcheck`), a failed check (`doctor`) or a run
that could not do its job (more than half of its pages could not be judged); 2 a usage
or configuration error; 3 `check` found no failure but could not judge every page
(`reference-failed`, `not-compared`, `excluded`, `harness-error`); 128 + N when stopped
by signal N. Commands that load pages or write the store hold the data directory's
lock, so a second one fails at once instead of competing for memory.

| Command | What it does |
|---|---|
| `doctor [--servo PATH] [--offline]` | Checks the data directory, store, Hister settings (prints the host only; `--offline` skips contacting it), deny and seed lists, servoshell (and whether its WebDriver port answers on the network), Pillow, Chromium and free disk |
| `sync [--full] [--max-pages N]` | Imports history from Hister into the store and prints counts only. Incremental after the first sync (one day of overlap); `--full` rereads everything |
| `seed [--file PATH]` | Adds the bundled `seeds.txt` (or `PATH`) and the private `seeds.txt` as seed pages |
| `select [--budget N] [--seed S] [--json]` | Prints the pages a run would check |
| `run [--servo PATH] [--budget N \| --urls FILE \| URL ...]` | Checks pages and records the results, then writes the report (also for a run that aborted). Options: `--label L`, `--no-reference` (judges only crash and hang), `--fresh-reference`, `--minutes M`, `--compare RUN\|latest`, `--retries N` |
| `check [--servo PATH] URL ... [--repeat N] [--json] [--record]` | Checks pages now, always against a fresh Chromium result; writes no store rows unless `--record`. Artifacts go to `checks/`, and a later `check` removes those older than a week |
| `report [--run ID\|latest] [--label L] [--compare ID]` | (Re)writes a run's `report.md` and `report.html`; `--label` picks the latest run with that label |
| `issues [--run ID\|latest] [--label L] [--json] [--all]` | Lists a run's open issues by priority; `--all` adds wontfix and upstream ones. The JSON says which run (`run_id`, `label`), whether it `finished`, and its page and verdict counts |
| `urls [--run ID\|latest] [--label L] [--verdict V ...] [--limit N]` | Prints a run's pages whose final verdict is one of `V` (default `ok`), one per line, every site before any site's second page; for regression sweeps with `run --urls`. The list is history: keep it in the data directory |
| `mark SIGNATURE --status open\|fixed\|wontfix\|upstream [--note TEXT]` | Sets an issue's status |
| `leakcheck [--git-range BASE..HEAD] [--repo PATH] [--exclude-ref REF ...] [--text FILE ...]` | Scans every commit of a range (added lines, file names, message, a merge's own lines), the branch name, and text files for hosts and URLs from the store, and commit messages and text files for upstream contact (references to the upstream project's issues and pull requests, and @-mentions in text files outside code), which GitHub would turn into links or notifications on the upstream side; `--exclude-ref` skips commits reachable from REF, such as a merged upstream release. Refuses (exit 2) an inventory with nothing to look for, or one without Hister pages while Hister is configured, unless `--allow-empty-inventory` |

Examples:

```sh
uv run sitecheck doctor
uv run sitecheck sync
uv run sitecheck seed
uv run sitecheck select --budget 20
uv run sitecheck run --budget 60 --label nightly --minutes 90
uv run sitecheck run --urls my-urls.txt --label regression
uv run sitecheck run https://example.com tests/fixtures/simple.html
uv run sitecheck check https://example.com --repeat 3
uv run sitecheck check http://127.0.0.1:8000/reduced.html --repeat 3 --json
uv run sitecheck report --run latest
uv run sitecheck issues --json --label nightly
uv run sitecheck mark 'js:foo is not a function' --status fixed --note "fixed in 1a2b3c4"
uv run sitecheck leakcheck --repo ~/Documents/GitHub/servo-sitecheck --git-range combray/0.6..HEAD --text pr-body.md
uv run sitecheck leakcheck --repo ~/Documents/GitHub/servo-sitecheck --git-range combray/0.6..HEAD --exclude-ref v0.7.0
```

Local files can be named by path; they become `file:` URLs. Neither they nor pages of
local servers (loopback or private hosts, non-default ports) are ever cached.

## How a run works

1. **Selection** (`--budget N`, default 60): re-checks of pages whose latest verdict was
   a failure not marked wontfix or upstream (at most 40 % of the budget, longest
   unchecked first, most severe first within a day), then never-checked pages one site
   at a time by visit weight (log2 of visits, plus a bonus for sites visited in the last
   14 days), leaving at least 20 % for the longest-unchecked pages, which come next. A
   site offers at most three pages: its most visited root page, then the most recent page
   of each of its two most recent URL templates (`/article/{}`; numbers, ids and
   article-title slugs become `{}`), so fifty articles of one news site cost one check.
   Only verdicts about Servo count as a check: a page loaded without one (no baseline, a
   harness failure) is planned as new again after three days. The run id seeds the
   plan, so it can be reproduced. Planned hosts are resolved, and pages whose host
   resolves to a private address leave the selection (when most of a plan does, the run
   stops instead: a proxy in fake-IP mode answers for everything).
2. **Per page, one engine at a time:** a fresh servoshell (`--headless`, 412×915 CSS px at
   device pixel ratio 1, Servo's Android user agent, the preferences of Combray's
   `native/src/engine.rs` and the page script it injects (`native/src/animations.js`),
   an empty temporary profile) loads the page over WebDriver,
   waits until the page replaced the initial `about:blank` and finished loading (at most
   45 s), waits 3 s, scrolls to the bottom and back, runs `probe.js` and takes a
   screenshot. Then Chromium does the same with the same user agent, viewport, pixel
   ratio, script and timings (and, like desktop servoshell, no touch screen), unless a
   reference result for the URL younger than three days is cached. A page that Servo or
   Chromium found at an excluded URL afterwards is discarded (`excluded`).
3. **Verdict** (`sitecheck/compare.py`), worst first:

   | Verdict | Severity | When |
   |---|---|---|
   | `crash` | 5 | servoshell panicked (a panic Servo caught and survived is only noted) or exited; signature `crash:<file>:<line>` |
   | `hang` | 4 | a script or a command got no answer, or the 90 s page wall clock ran out, before the probe answered; signature `hang:<cause>:<step>`. When Chromium also hung on the page, the verdict is `reference-failed` |
   | `blank` | 4 | Servo's screenshot is near-uniform while the reference's is not (`blank:screenshot`), Servo has under 5 % of the reference's text (`blank:text`), or Servo showed its network error page (`navigation-error`) |
   | `content-missing` | 3 | under 60 % of the reference's most frequent words appear in Servo's text (form controls such as `<select>` options are left out of both) |
   | `js-error` | 2 | an uncaught error or unhandled rejection only Servo reported, matched by normalized message or by the script and line both engines report; console messages are not compared; signature `js:<normalized message>` (up to 5) |
   | `layout-overflow` | 2 | Servo's document is over 10 % wider than its viewport while the reference's fits; `layout-overflow:viewport` when Chromium widened its own viewport for a desktop-only page |
   | `images` | 2 | at least 3 more broken images, or under half the images the reference loaded |
   | `slow-load` | 1 | only Servo's load event timed out, and nothing else fired |
   | `visual-diff` | 1 | screenshot similarity under 0.55 (the lower of a pixel and an average-hash measure) |
   | `flaky` | 1 | a failure that did not repeat; signature `flaky:<first signature>` |
   | `ok` | 0 | none of the above |
   | `reference-failed` | 0 | Chromium could not load the page either, or hung on it too; excluded from triage |
   | `not-compared` | 0 | run with `--no-reference`: only crash and hang were judged; excluded from triage |
   | `excluded` | 0 | the page ended up at a URL the policy excludes; discarded and kept out of later runs |
   | `harness-error` | 0 | the harness failed, or Servo's probe and screenshot gave no evidence either way; excluded from triage |

   A failure judged against a cached reference is judged again against a fresh one.
   A failing page is loaded once more in a fresh servoshell (`--retries`); the page's
   final verdict is the most severe failure over its attempts, with every attempt's
   signatures (or `flaky` when another attempt was ok). Five harness errors in a row
   stop the run. A page without a Servo screenshot (its rendering never became ready)
   is marked so in the report, since the screenshot rules could not run.
4. **Issues** group findings by signature across pages and runs. `crash:<file>:<line>`
   and `js:` signatures name a root cause and are shared across sites. Symptom
   signatures (`blank:text`, `hang:...`, `content-missing`, `images`, `visual-diff`,
   `slow-load`, `layout-overflow`, a crash without a location) end in `@<tag>`, a keyed
   hash of the page's site, so one site's mark never hides another site's pages.
   Priority is severity times the number of affected sites, ties broken by visit weight.
   An issue marked `fixed` reopens when a later run sees it again (the run summary lists
   reopened issues); `wontfix` and `upstream` stay.
5. **Report**: `runs/<run_id>/report.md` and `report.html` (self-contained, side-by-side
   thumbnails), with regressions and fixes against the previous run of the same label.

Page text is used for the verdict, then kept as `*.text.gz` in the page directory and,
for cached references, in the reference cache entry (its `reference.json` holds the
whole Chromium result). The store's results hold the probe's measurements (the page's
final URL included), script error messages and their script URLs, but no page text;
reports show counts, ratios and error signatures.

## Data layout

```
~/.local/share/combray-sitecheck/          0700
  state.sqlite3                            pages, runs, results, issues, meta
  lock                                     single-command lock (flock)
  signature.key                            key of the site tags in symptom signatures
  reference-cache/<page_key>/              cached Chromium results with page text, 3 days
  checks/<timestamp>/pages/<page_key>/     `check` artifacts, pruned after 7 days
  runs/<run_id>/                           pruned after 30 days, except the latest run
                                           of each label and where open issues began
    report.md, report.html
    pages/<page_key>/                      page_key = first 16 hex of sha256(url)
      servo.png, servo.log, servo.json, servo.text.gz
      reference.png, reference.json, reference.text.gz
      attempt-2/                           a retry's servo.* files
```

## For the nightly agent

1. `sitecheck sync`, then `sitecheck run --budget 60 --label nightly --minutes 90`. A
   non-zero exit means the run did not do its job (for example no network): report it
   rather than triaging.
2. `sitecheck issues --json --label nightly` gives the open issues of the latest nightly
   run, highest priority first; check `finished` (an interrupted run's list is
   incomplete). Each issue has its `signature`, `kind`, `severity`, `priority`, `sites`,
   `urls`, `example_url` and `example_page_dir` (screenshots, `servo.log`, the JSON
   records and the page text of the most visited affected page).
3. Reduce an issue to a synthetic test case, serve it locally, and confirm the bug with
   `sitecheck check http://127.0.0.1:PORT/case.html --repeat 3 --json`. Judge by the
   JSON `verdict` and `signatures`: exit 1 means a failure, 0 a judged pass, and 3 that
   a page could not be judged (no reference, a harness error), which confirms nothing.
   Do not pass `--no-reference`: without Chromium only crashes and hangs are judged.
   After the fix, check the affected URLs again with `check` and run a regression sweep
   over pages that were fine: `urls --label nightly --limit 20 > <data dir>/sweep.txt`,
   then `run --urls <data dir>/sweep.txt --label fix-check --compare <nightly run id>`.
4. `sitecheck mark SIGNATURE --status fixed --note "..."` once the fix is merged;
   `wontfix` for site bugs, `upstream` when an upstream release fixes it. A symptom
   signature (ending in `@<tag>`) belongs to one site: marking it affects that site's
   pages only.
5. Before pushing anything, `sitecheck leakcheck --repo <repo> --git-range <base>..HEAD
   --text <pr-body.md>` must exit 0 (for an upstream merge, add `--exclude-ref <upstream
   tag>` so only our own commits are scanned). Commits, branches and PRs describe bugs
   only through the synthetic test case. Signatures, error messages, finding details and
   page text are history-derived: never quote them in commits, branches or PRs; describe
   the reduced case instead.

## Limits

- The desktop build approximates the phone: same engine source and preferences, but
  different fonts, GPU and input. A clean result is evidence, not proof.
- Chromium emulates a phone (`is_mobile`), so a page without a mobile viewport is laid
  out 980 px or wider there and at 412 px in Servo, which does not implement that
  behaviour; such pages get `layout-overflow:viewport`.
- SpiderMonkey and V8 word some errors differently. Uncaught errors are matched by the
  script and line both report, and the common property-access wording is mapped to one
  form, but an unhandled rejection worded differently can still appear as a Servo-only
  `js:` signature. Servo's log also reports errors from iframes that Chromium's page
  error event does not. Console messages are not compared at all.
- Redirects are followed by the engines, not the harness: a server redirect to an
  excluded URL is loaded once by Servo before the page is discarded. Chromium blocks
  script navigations to such URLs but cannot see server redirect hops.
- The thresholds are first estimates; calibrate them on real runs.
- Pages behind a login, a cookie wall or bot protection are compared as anonymous
  visitors see them. Pages that differ per load (ads, rotating content) add noise.
- `site` is an approximation without the Public Suffix List (`github.io` counts as one
  site).
- The leak check matches hosts, sites and URLs as text in the committed bytes, read as
  UTF-8 whatever git attributes or diff drivers say. A binary file's bytes count as
  text, but a host in another encoding (UTF-16) or in compressed data is not found,
  and in a merge commit's own changes binary files are read by name only. It refuses
  to scan while the repository's `.git/info/attributes` sets anything, since git
  applies that file to the scan. It skips single-label hosts and non-public IP
  addresses, and does not look for hosts of public references the engine work cites
  (specifications, engine trackers, the project's own repositories;
  `leakcheck.PUBLIC_REFERENCES`).
- POSIX only (process groups, `flock`); macOS and Linux.

## Development

```sh
# from the repository root, with a bare CPython 3.12 (no third-party packages):
python3.12 -I -m unittest discover -s testing/sitecheck/tests -t testing/sitecheck
# in the uv environment, which also runs the Pillow tests:
cd testing/sitecheck && uv run python -m unittest discover -s tests -t .
uvx ruff@0.17.2 check sitecheck tests && uvx ruff@0.17.2 format --check sitecheck tests
```

Tests use fakes for both engines and Hister and need no network. With
`SITECHECK_LIVE_SERVO=<path to servoshell>`, `tests/test_servo.py` also runs local
`file:` fixtures in the real servoshell, and with `SITECHECK_LIVE_CHROMIUM=1` (in the uv
environment), `tests/test_reference.py` runs them in Playwright's Chromium.
`tests/fixtures/servo_gap.html` is a manual live-check fixture that Servo 0.6.0 renders
blank (it relies on `Element.animate()`).
