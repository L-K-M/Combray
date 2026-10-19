# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Page controls: `<select>` pickers, `alert`/`confirm`/`prompt` dialogs
  naming the asking site (with a way to block a page's repeated dialogs),
  and file upload through Android's document picker.
- Local release signing through environment variables, with explicit validation
  of incomplete configuration and no committed signing material.
- APK build-script regressions in CI and SHA-256 sidecars for local builds.
- App icon master (`media-sources/icon.png`).
- Implementation plan, integration evidence, design reference, and
  documentation checks.
- Repository tooling ahead of the app: build, install, and release scripts;
  CI and tag-triggered release workflows; GLM code review; Dependabot.
- M0 engine spike: Servo v0.6.0 pinned with three patches, Combray's Rust
  bridge (`native/`), an Android test harness and fixture site (`spike/`),
  build and check scripts (`scripts/m0/`), and the results in
  `docs/ENGINE_SPIKE.md` and `docs/BUILDING.md`.
- The Combray app (`app`, `core`, `engine` Gradle modules): Servo tabs with
  durable logical state, bottom bar, address and Kagi search, unified Recent
  with local filter, retention and forgetting, external links, page-opened
  windows, and recovery after process death.
- Karakeep saves: protected credentials bound to their server, a durable
  outbox with fenced claims, retries and honest pending/saved/failed states.
- Hister: opt-in capture of rendered page text with exclusions, budgets and
  per-document ordering, plus explicit long-term search in Recent.
- Servo patch 0004: cookies and other profile state are written on request
  (and atomically), so logins survive Android killing the app.
- Device checks against the real engine (`scripts/check-app.sh`) with
  synthetic pages and fake services (`testing/fixtures/`).
- Internationalized host names show in Unicode only when they cannot
  imitate another site; other hosts show as punycode.

### Fixed

- Local APK names now use the packaged architecture instead of always saying
  arm64. Staging rejects missing native libraries, wrong ELF architectures and
  stale APKs that do not match Gradle output metadata. Failed copying or
  hashing preserves the previous APK and checksum. Release CI applies the same
  packaging checks before staging an arm64 artifact.
