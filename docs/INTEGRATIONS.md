# Integration contracts and source evidence

Inspected on **18 October 2026**. This is source/documentation research, with **no live service or Android runtime tests**. The owner's versions and authentication modes are unknown. The [implementation plan](IMPLEMENTATION_PLAN.md) defines Combray's intended behavior; this appendix records the external evidence behind it.

## Hister

Inspection used commit **`dce14327638b4177fcaad7cd11827c26b6cd17cd`** from [asciimoo/hister](https://github.com/asciimoo/hister/tree/dce14327638b4177fcaad7cd11827c26b6cd17cd). Links below are pinned to that revision. Do not substitute a current API assumption for the deployed server's behavior.

| Concern | Observed contract | Evidence |
| --- | --- | --- |
| Ingest | `POST /api/add`; JSON or form. JSON supports `url`, `title`, `text`, `html` and additional optional fields. `url` required. | [endpoint schema](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/api.go#L290), [serveAdd](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L780) |
| Acknowledgment | Normal handler success is 201; no required JSON response body. Rules can skip indexing, so acceptance alone does not demonstrate searchability. Before indexing, skip rules, an allow list the URL does not match, or Hister's own host refuse the document with 406; sensitive content refuses it with 422 (for web pages only submitted HTML is checked). | [serveAdd](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L780), [submission rules](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/submission.go#L19), [sensitive check](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/document/document.go#L161), [write preparation](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/indexer/indexer.go#L1298) |
| URL only | The inspected normal add path does **not fetch the remote web page** when only a URL is submitted. HTML extraction runs when HTML is present; supplied text is usable directly. Send loaded text for full-text history. | [Document.Process and extraction](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/document/document.go#L149) |
| Document identity | Web URL normalization strips fragments and UTM parameters; ownership participates in document identity. Repeated keys update stored content rather than create independent Combray-style visits. | [normalization and ID](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/document/document.go), [update preparation](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/indexer/indexer.go#L1298) |
| Token auth | `X-Access-Token` or `Authorization: Bearer ...`; multi-user mode resolves per-user tokens or sessions; proxy auth can be configured. A server-wide token and per-user token are not interchangeable assumptions. | [requestAccessToken, auth and user context](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/server.go#L199) |
| CSRF | Mutating add is CSRF guarded. `Origin: hister://` is an explicit native/CLI convention in the inspected middleware; otherwise the session/CSRF-header flow must be followed. Authentication still applies. | [withCSRF](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/server.go#L370) |
| Search | `GET /search?q=...&format=json`, or request JSON via `Accept`. A nonempty text query is required for this JSON mode. `page_key` is an opaque cursor; `include_html` is unnecessary for result rows. | [search schema](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/api.go#L121), [parameter parsing and HTTP branch](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L472) |
| Result shapes | `documents` and promoted `history` are different arrays; result metadata includes `total`, `query`, `page_key`, `error` and search/semantic/facet fields. Do not drop one array. | [Results type](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/indexer/indexer.go#L282), [history promotion](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L635) |
| Body limits | General requests 1 MiB; add/document uploads use configurable `server.max_batch_body_size`, default 40 MiB at this revision. Combray should use smaller independent payload bounds. | [request limits](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/request_limits.go), [config default](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/config/config.go#L87) |
| Discovery | `/api/config` exposes authentication/capability information, and version only when enabled. The API description surface lists endpoint metadata. Do not assume version is always disclosed. | [endpoint definitions](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/api.go), [serveAPI](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L1700) |
| Optional deletion | `POST /api/delete` accepts JSON `query` and `dry_run`; returns `matched` or `deleted` counts. Scope/auth and query escaping must be tested before exposing remote deletion. This is not automatically part of local forgetting. | [serveDelete](https://github.com/asciimoo/hister/blob/dce14327638b4177fcaad7cd11827c26b6cd17cd/server/endpoints.go#L1965) |

Minimal intended ingestion:

```http
POST /api/add
Content-Type: application/json
Authorization: Bearer <configured-token>
Origin: hister://

{"url":"https://example.org/article","title":"Example","text":"Captured readable content."}
```

The placeholder token is illustrative, not a credential. Establish the actual server's auth mode and namespace before enabling content transfer. Search's `Public` endpoint metadata does not prove private history is accessible without authentication in every deployment; middleware, server mode and user context determine scope. Do not send admin target-user headers or bypass server skip rules for routine capture.

No source finding establishes a conditional “write only if newer” API for web documents. The plan's coalescing/order/reconciliation policy is necessary; client timestamps alone are not a remote concurrency guarantee. Server versioning, if enabled, is separate from one immutable snapshot per local Visit.

## Karakeep

The [API introduction](https://docs.karakeep.app/api/karakeep-api/) and [create endpoint](https://docs.karakeep.app/api/create-bookmark/) displayed **v0.34.0**; some linked pages displayed v0.33.0. The documentation site is mutable. Recheck the deployed version and its schema in M2.

- Base API prefix: `/api/v1`, as shown by the official [single-bookmark examples](https://docs.karakeep.app/api/get-bookmark/). Configure the server root and construct the prefix once.
- Native auth: `Authorization: Bearer <API key>`, created in Settings → API Keys. The key is not a web-page credential. [Authentication](https://docs.karakeep.app/api/karakeep-api/).
- Create link: `POST /api/v1/bookmarks`, JSON `type: "link"`, `url`, optional `title`. **201** is new; **200** returns an existing link for that URL. **400** is invalid input; **401** is missing/invalid/expired auth. [Create semantics](https://docs.karakeep.app/api/create-bookmark/).
- The inspected [request/response types](https://github.com/karakeep-app/karakeep/blob/main/packages/shared/types/bookmarks.ts) bound titles to 1000 characters and link schemes to HTTP(S). Bookmark data includes an ID and separate crawl/tagging/summarization statuses. Pin the actual matching source/schema before implementing its parser; this `main` link is research, not a dependency lock.
- Tags use a separate `POST /bookmarks/:bookmarkId/tags` under the API prefix; identifiers can be tag IDs or names. [Attach tags](https://docs.karakeep.app/api/attach-tags-to-bookmark/). Lists use `PUT /lists/:listId/bookmarks/:bookmarkId`, with **204** success and idempotent membership addition. [Add to list](https://docs.karakeep.app/api/add-bookmark-to-list/). Validate request bodies when implementing these optional follow-ups.
- List endpoints use cursor/limit and `nextCursor`; no listing of the entire bookmark collection is needed for one-tap saves. [Pagination](https://docs.karakeep.app/api/karakeep-api/).
- Checked against the [OpenAPI specification](https://github.com/karakeep-app/karakeep/blob/main/packages/open-api/karakeep-openapi-spec.json) on `main` (21 October 2026, mutable): `GET /lists` returns `lists` with `id`, `name`, `icon`, `type` (`manual` or `smart`) and `userRole`; only manual lists take bookmarks. `GET /bookmarks` (newest first) and `GET /bookmarks/search?q=` take `limit`, `cursor` and `includeContent` and return `bookmarks` plus `nextCursor`; a link bookmark has `content.type: "link"`, `content.url` and `content.title`, and its top-level `title` is the user's own. Combray sends no `searchMode`, which older servers may not know. Not yet tried against a deployed server.

Combray should treat saved, crawled and searchable as separate facts. A create acknowledgment should not wait for remote AI/crawler work, and a partial metadata failure should not erase the saved bookmark state. Duplicate response behavior supports retries after lost acknowledgments, but still verify URL normalization on the installed server.

## Kagi

The official [default search configuration](https://help.kagi.com/kagi/getting-started/setting-default.html) supplies `https://kagi.com/search?q=%s`. Encode a submitted query once and navigate in Servo. Use ordinary browser login/cookies. Combray does not need to call a separate Search API or parse search results to satisfy the requirement.

The same page advertises autosuggest, which is intentionally deferred so typing stays local. Kagi documents [private browser sessions](https://help.kagi.com/kagi/privacy/private-browser-sessions.html); a login link is sensitive and is not an ordinary history item. Do not implement a guessed token parameter. Native integration keys must not be inserted into web URLs.

## Compatibility work still required

1. Record actual deployed versions, path prefixes, auth identity, TLS/network reachability and server limits.
2. Run add/create/search contract fixtures against those versions in an isolated test environment.
3. Verify Hister ingestion lands in the intended user scope and a unique captured phrase becomes searchable. Investigate accepted-but-skipped documents.
4. Verify Karakeep's success body, duplicate identity and optional tag/list body formats before using them.
5. Verify Kagi login/search and cookie persistence with the selected Servo build on Android.
6. Exercise timeout-after-write, out-of-order same-document updates, destination switch and local forgetting. No live tests were performed for this handoff.
