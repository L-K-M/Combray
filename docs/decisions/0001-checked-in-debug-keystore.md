# 0001 — Both build types are signed with a checked-in debug keystore

Combray is distributed sideload-only: built locally or by CI, installed by
hand. Both `debug` and `release` build types are signed with
`app/debug.keystore` — a committed, zero-secret debug certificate (the standard
`androiddebugkey` / `android` credential, PKCS12, RSA 2048, valid 100 years),
the same arrangement the sibling Android apps (Asterinked, Wortkatze) use.

## Why

- **Upgrade compatibility across machines.** Android refuses to update an app
  when the signature changes. An auto-generated per-machine debug key would
  force an uninstall — losing app data — on every switch between a locally
  built APK, a CI artifact and a release APK. A committed key makes every
  build compatible with every other.
- **Zero-secret CI.** Nothing in the release pipeline needs a protected
  secret, so any checkout can build and verify the same artifact.

## Consequences

- Anyone can produce a validly "signed" Combray APK — the signature proves
  continuity, not authorship. Acceptable while the app is sideload-only and
  requests no signature-level permissions.
- Moving to a real signing key later (e.g. for Play distribution) breaks
  upgrades for every installed user; that switch is a product decision
  requiring a new ADR, ideally before the first public release.
- Do not "rotate" the keystore or gitignore it. It is deliberately committed.
