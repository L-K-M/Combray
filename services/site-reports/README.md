# Combray site-report service

A small self-hosted service that receives Combray's "Report this site" reports from your phone and serves them to Paseo, which hands them to coding agents. It is Python standard library code in one Docker Compose service, with SQLite and one PNG file per screenshot in a named volume.

**Reports hold private browsing data.** Each report stores what the phone sent: a screenshot of the page as it was on screen, the page address (as you left it after any edit), its title, your comment, Combray's, Servo's and Android's versions, the text size and the screen density. The service adds the report's status, agents' notes and a history of status changes. Treat the volume, backups and anything an agent downloads as private.

Run it on a loopback or tailnet address behind TLS, never on the public internet. Python's `http.server` [is not recommended for production](https://docs.python.org/3/library/http.server.html); the service adds request limits, timeouts and token checks, but it is not hardened against a hostile network.

Never name a reported site (URL, host, title, page content or screenshot) in commits, branches, PRs, issues or tests, and never commit report data. Describe the bug through a synthetic test case, as [AGENTS.md](../../AGENTS.md) requires for site checks.

## Set up

You need Docker with Compose v2. Run every command in this directory.

```sh
cp .env.example .env
docker compose run --rm site-reports token   # for the phone (reporter)
docker compose run --rm site-reports token   # for Paseo (agent)
```

Each `token` run builds the image if needed and prints a new token (`token=`) and its SHA-256 (`sha256=`). Put the first hash in `REPORTS_REPORTER_TOKEN_SHA256` and the second in `REPORTS_AGENT_TOKEN_SHA256` in `.env`. Keep the tokens themselves in your password manager until you enter them in Combray and Paseo: the service stores only the hashes, so a lost token cannot be recovered, only replaced.

```sh
docker compose up -d --build
curl -fsS http://127.0.0.1:8787/healthz   # {"ok":true}
```

The service refuses to start when a hash is missing or is not 64 hexadecimal digits, or when both roles share one. `docker compose logs site-reports` names the variable, never its value. The other settings in `.env.example` are explained there; an empty value means the default.

The container runs as uid 10001 on a read-only root filesystem, without capabilities, with `no-new-privileges`, 512 MiB of memory and at most 64 processes. Its log (Docker's `local` driver, at most three 10 MB files) has one line per request: method, route, status, bytes in and out, milliseconds, role and report id. It never contains a token, a query, a URL, a title, a comment, a note or screenshot bytes.

## Reach it from the phone

Proposed default: keep the loopback bind and publish the service on your tailnet with `tailscale serve` over HTTPS. The phone then gets a certificate it trusts and Combray shows no cleartext warning.

```sh
tailscale serve --bg --https=8443 http://127.0.0.1:8787
tailscale serve status
```

The service is then at `https://<machine>.<tailnet>.ts.net:8443`. Pick a free HTTPS port: Hister may already use 443 on the same machine. This is the Tailscale 1.52 and later syntax; check `tailscale serve --help` on your version. HTTPS needs MagicDNS and HTTPS certificates turned on for the tailnet. `tailscale serve --https=8443 off` stops it.

Alternatives: a reverse proxy with TLS in front of `127.0.0.1:8787`, or `REPORTS_BIND_ADDRESS` set to the machine's tailnet IPv4 address over plain HTTP. WireGuard encrypts that hop, but Combray's settings warn about any plain HTTP address. Never bind to a public address.

On the phone, Combray › Settings › Site reports takes the address (the root above, without `/v1`) and the reporter token, and Check connection asks the service who the token belongs to. Then use More › Report this site on a page: the preview shows everything the report sends, and nothing leaves the phone until you tap Send.

## Use it from Paseo

Keep the agent token in a header file, readable only by you, so it never appears in a process list or shell history:

```sh
ROOT=https://reports.example.ts.net:8443
H="$HOME/.config/paseo/site-reports.headers"   # one line: Authorization: Bearer <agent token>
chmod 600 "$H"
D="$(mktemp -d)"   # report data stays out of any Git checkout; delete it when done

# Open reports, oldest first; pass the answer's "next" as &after= for the next page
curl -sS --fail-with-body -H @"$H" "$ROOT/v1/reports?status=open&limit=20"

# Claim one; 409 status_conflict means another agent took it
curl -sS --fail-with-body -H @"$H" -H 'Content-Type: application/json' \
  -d '{"status":"claimed","expect":"open","note":"paseo: investigating"}' \
  "$ROOT/v1/reports/$ID/status"

# Details and screenshot; compare the digest with .screenshot.sha256
curl -sS --fail-with-body -H @"$H" "$ROOT/v1/reports/$ID" > "$D/$ID.json"
curl -sS --fail-with-body -H @"$H" -o "$D/$ID.png" "$ROOT/v1/reports/$ID/screenshot"
sha256sum "$D/$ID.png"

# Resolve (or "rejected"); either can be reopened with "open"
curl -sS --fail-with-body -H @"$H" -H 'Content-Type: application/json' \
  -d '{"status":"done","note":"fixed with a synthetic regression test"}' \
  "$ROOT/v1/reports/$ID/status"

# Release a claim a crashed agent left behind (list them with status=claimed)
curl -sS --fail-with-body -H @"$H" -H 'Content-Type: application/json' \
  -d '{"status":"open","expect":"claimed"}' "$ROOT/v1/reports/$ID/status"

# Purge a report's contents now, keeping only its id
curl -sS --fail-with-body -H @"$H" -X DELETE "$ROOT/v1/reports/$ID"
```

`-H @file` needs curl 7.55 or later and `--fail-with-body` 7.76 or later. Claims have no lease: a claim stays until an agent changes it.

## API reference

Common rules:

- Every route but `/healthz` needs `Authorization: Bearer <token>`, never a query parameter. A missing or unknown token gets 401 `unauthorized` with `WWW-Authenticate: Bearer realm="site-reports"`; a valid token of the wrong role gets 403 `forbidden`.
- Request bodies are JSON: `Content-Type: application/json` (a `charset=utf-8` parameter is allowed) and a `Content-Length` header. Chunked bodies are refused with 411. A body over the route's limit gets 413 before it is read. Duplicate object keys are 400 `duplicate_key`.
- Errors are `{"error": "<code>"}`, plus `"field": "<json path>"` for a validation error.
- Paths are matched segment by segment without decoding: `%2F`, `..`, empty segments and a trailing slash match nothing (404 `not_found`), and so do `/`, `/v1/` and `/v1/reports/`. A known path with another method gets 405 `method_not_allowed` with `Allow`; `HEAD` and `OPTIONS` are not served. A query string on any route but `GET /v1/reports` is 400 `invalid_query`.
- Responses carry `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, and no `Server` or CORS headers. 429 and 503 `busy` carry `Retry-After` in seconds. Responses never contain absolute or root-relative links, so the service can sit under a path prefix behind a proxy.
- Timestamps are RFC 3339 UTC: the phone sends `2026-11-04T09:12:44.123Z` or `2026-11-04T09:12:44Z`; the service always answers with milliseconds.

| Route | Role | Answer |
| --- | --- | --- |
| `GET /healthz` | none | 200 `{"ok":true}` when the database answers and staging is writable, else 503 `{"ok":false}` |
| `GET /v1/whoami` | either | 200 `{"service":"combray-site-reports","api":1,"role":"reporter"}` |
| `POST /v1/reports` | reporter | 201 created, 200 repeat: `{"id":…,"status":…,"received_at":…}` |
| `GET /v1/reports` | agent | 200 `{"reports":[…],"next":"57"}`, oldest first |
| `GET /v1/reports/{id}` | agent | 200 report with `history` |
| `GET /v1/reports/{id}/screenshot` | agent | 200 `image/png` |
| `POST /v1/reports/{id}/status` | agent | 200 report without `history` |
| `DELETE /v1/reports/{id}` | agent | 204 |

### `POST /v1/reports`

The body is one JSON object with exactly these members; [tests/data/report-v1.json](tests/data/report-v1.json) is a complete example:

- `id`: a lowercase UUIDv4 the phone generates; it makes the request idempotent.
- `created_at`: when the report was made, RFC 3339 UTC.
- `page`: `url` (1 to 8192 UTF-8 bytes), `url_edited`, `load_failed` and `crashed` (booleans), `title` (null or up to 4096 characters), `display` (`"page"` or `"reader"`).
- `comment`: null or up to 8000 characters; newlines and tabs allowed.
- `client`: at most 24 entries with keys `[a-z0-9_]{1,32}` and values that are text up to 256 characters, 64-bit integers, finite numbers or booleans, never null. `app`, `app_version`, `engine_version`, `engine_revision` and `android_release` are required.
- `screenshot`: null, or `media_type` (`"image/png"`), `width` and `height` (1 to 8192), `bytes`, `sha256` (lowercase hex of the PNG), `scaled` (boolean) and `data` (the PNG in standard base64 with padding, no line breaks).
- `screenshot_omitted`: null when there is a screenshot, else why not: `"user"`, `"reader_view"`, `"not_shown"`, `"page_changed"`, `"copy_failed"` or `"too_large"`.

Text may not contain control characters except where noted. The PNG is checked without decoding it: signature, an `IHDR` matching the declared size, valid chunk lengths and CRCs, at least one `IDAT`, and `IEND` at the very end.

A repeat of an existing id with the same content (everything but `data`, compared through a canonical digest) is 200 with the report's current status, even `claimed`, a resolved status or `deleted`: a phone retry never reopens a report. A repeat with different content is 409 `id_conflict`.

### `GET /v1/reports`

Query: `status` is `open` (default), `claimed`, `done`, `rejected` or `all` (every status but deleted); `limit` is 1 to 100 (default 20); `after` is the `next` value of the previous page. `next` is null on the last page. Any other key, a repeated key or a bad value is 400 `invalid_query`.

A report:

```json
{
  "id": "6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d",
  "status": "open",
  "status_note": null,
  "received_at": "2026-11-04T09:12:45.120Z",
  "updated_at": "2026-11-04T09:12:45.120Z",
  "created_at": "2026-11-04T09:12:44.123Z",
  "page": {"url": "https://example.org/article?x=1#part", "url_edited": false, "title": "Example article", "display": "page", "load_failed": false, "crashed": false},
  "comment": "The menu covers the first paragraph.",
  "client": {"app": "combray", "app_version": "0.1.0", "…": "…"},
  "screenshot": {"media_type": "image/png", "width": 1080, "height": 2088, "bytes": 412345, "sha256": "…", "scaled": false},
  "screenshot_omitted": null,
  "purged": false
}
```

A purged report has `"purged": true` and null `created_at`, `page`, `comment`, `client`, `screenshot` and `screenshot_omitted`. `GET /v1/reports/{id}` adds `"history": [{"status": "open", "at": "…", "actor": "reporter", "note": null}, …]`, where `actor` is `reporter`, `agent` or `retention`.

### `GET /v1/reports/{id}` and its screenshot

An unknown or malformed id is 404 `not_found`; a deleted report is 410 `deleted`. The screenshot route answers with `Content-Length` and `Content-Disposition: attachment; filename="<id>.png"`, or 404 `no_screenshot` when the report has none or it was purged.

### `POST /v1/reports/{id}/status`

Body (at most 16 KiB): `{"status": "claimed", "expect": "open", "note": "paseo: investigating"}`. `status` is `open`, `claimed`, `done` or `rejected`; `expect` (optional) is one of the same, and the change happens only when the report has that status; `note` (optional) is up to 2000 characters and becomes the status note; a change of status without one clears it.

Allowed changes: open to claimed, done or rejected; claimed to open, done or rejected; done or rejected to open. Setting the current status again changes nothing, except that a given note replaces the status note. Errors: 409 `{"error":"status_conflict","status":"<current>"}` when `expect` differs; 400 `invalid_transition`; 409 `purged` when reopening a purged report; 404; 410.

### `DELETE /v1/reports/{id}`

Deletes the screenshot, the contents and every note now, and keeps the id and content digest as a tombstone, so a late phone retry gets 200 `deleted` instead of storing the report again. 204, also when it was already deleted; 404 for an unknown id.

### Error codes

| Status | Codes |
| --- | --- |
| 400 | report and status validation: `invalid_json`, `duplicate_key`, `unknown_field`, `missing_field`, `invalid_id`, `invalid_time`, `invalid_page`, `invalid_url`, `invalid_title`, `invalid_display`, `invalid_flag`, `invalid_comment`, `invalid_client`, `invalid_screenshot`, `unsupported_media_type`, `screenshot_digest_mismatch`, `invalid_png`, `invalid_status`, `invalid_note`; also `invalid_query`, `invalid_transition`, `bad_request` (a malformed request line or `Content-Length`), `incomplete_body` (the client closed early) |
| 401, 403 | `unauthorized`, `forbidden` |
| 404, 405, 410 | `not_found`, `no_screenshot`; `method_not_allowed`; `deleted` |
| 408 | `timeout`: the body did not arrive in time |
| 409 | `id_conflict`, `status_conflict`, `purged` |
| 411, 413, 415 | `length_required`; `too_large`, `screenshot_too_large`; `unsupported_content_type` |
| 414, 431, 501, 505 | `request_uri_too_long`, `request_header_fields_too_large`, `not_implemented`, `http_version_not_supported` |
| 429 | `rate_limited`, with `Retry-After` until the next clock hour |
| 500 | `internal_error` |
| 503 | `busy`, with `Retry-After: 5` |
| 507 | `storage_full` |

## Limits

| Limit | Default | Setting |
| --- | --- | --- |
| `POST /v1/reports` body | 12 MiB (413 `too_large`) | `REPORTS_MAX_REQUEST_MB` |
| Decoded screenshot | 8 MiB (413 `screenshot_too_large`) | `REPORTS_MAX_SCREENSHOT_MB` |
| Status change body | 16 KiB | fixed |
| Request headers | 32 KiB after the request line (431) | fixed |
| Screenshots stored | 1024 MiB (507) | `REPORTS_MAX_STORAGE_MB` |
| New reports per reporter token | 60 per clock hour (429); repeats and refused reports do not count | `REPORTS_MAX_REPORTS_PER_HOUR` |
| Requests in progress | 8, of which 2 uploads (503 `busy`) | fixed |
| Open connections | 32; more are closed without an answer | fixed |
| Socket inactivity | 30 s per read or write | fixed |
| Upload time | 300 s for the whole body (408) | fixed |

The app change will treat 5xx answers, 507 included, as worth retrying later: storage frees up as reports are resolved and purged.

## Retention

The service sweeps at start and every hour:

- Open and claimed reports are kept until an agent resolves or deletes them.
- A done or rejected report keeps its contents and screenshot for `REPORTS_RESOLVED_RETENTION_DAYS` (30) after it was resolved. Then the contents and screenshot are deleted; its id, status and history stay.
- A purged or deleted report's tombstone is deleted after `REPORTS_TOMBSTONE_RETENTION_DAYS` (365). Until then, a late retry from the phone is recognised instead of stored again.
- Staging files and screenshots without a report are deleted.

`docker compose exec site-reports python -m site_reports sweep` runs a sweep now and prints what it removed.

## Backup

Reports are short-lived work items, so the proposed default is no backup. If you want one, remember that a backup holds the same private browsing data as the service: store it encrypted, outside the Git checkout so it is never committed, and delete it when it is no longer needed.

```sh
docker compose exec site-reports python -m site_reports backup /data/backup-2026-11-05
mkdir -p -m 700 "$HOME/site-reports-backups"
docker compose cp site-reports:/data/backup-2026-11-05 "$HOME/site-reports-backups/backup-2026-11-05"
docker compose exec site-reports rm -r /data/backup-2026-11-05
```

`backup` copies the database with SQLite's backup API and checks each screenshot's digest while copying it into a new directory laid out like `/data`; it refuses a directory that exists, and it removes its directory again when the copy fails. To restore, stop the service and replace the volume's `reports.sqlite3` and `screenshots/` with the backup's, owned by uid 10001, with no `reports.sqlite3-wal` or `reports.sqlite3-shm` left beside them.

## Rotate a token

1. Run `docker compose run --rm site-reports token` and append the new hash to the role's variable in `.env`, after a comma.
2. `docker compose up -d` (both tokens work now).
3. Enter the new token in Combray or Paseo.
4. Remove the old hash from `.env` and run `docker compose up -d` again.

## Upgrade

Pull the new commit and run `docker compose up -d --build`. The base image is pinned by tag and digest, and Dependabot proposes updates, so `--pull` is not needed. Reports survive in the volume. The database records its schema version, and a service version that does not know it refuses to start (exit 2) instead of misreading the data.

## Development

The service needs Python 3.12 or later and no third-party packages. From the repository root:

```sh
python3 -I -m unittest discover -s services/site-reports/tests -t services/site-reports
```

`tests/smoke.py` checks a running service with an empty data directory: it posts the golden report and reads it back. CI runs it against the container Compose starts (`.github/workflows/site-reports.yml`).

```sh
SMOKE_REPORTER_TOKEN=… SMOKE_AGENT_TOKEN=… python3 -I services/site-reports/tests/smoke.py --root http://127.0.0.1:8787
```
