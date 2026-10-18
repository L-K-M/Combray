# Combray

> [!IMPORTANT]
> LLM disclosure: This codebase was written with substantial help from large language models: AI coding agents working from the [`AGENTS.md`](AGENTS.md) brief in this repo.

**Latest release:** v<!-- version -->0.1.0<!-- /version --> · [Download](https://github.com/L-K-M/Combray/releases/latest)

An Android app — in early development. The repository has its build, release,
and CI tooling in place ahead of the app itself.

## Building

Requires JDK 17+ and the Android SDK.

```sh
scripts/build.sh              # release APK -> dist/
scripts/build.sh --debug      # debug APK -> dist/
scripts/build.sh --install    # build, install on the connected device, launch
scripts/install.sh            # debug build straight onto the device
```

Release APKs are signed with the checked-in debug keystore and are meant for
sideloading (see [docs/decisions/0001](docs/decisions/0001-checked-in-debug-keystore.md)).

## Releasing

`scripts/release.sh X.Y.Z --push` bumps the version, tags `vX.Y.Z`, and pushes;
the tag builds, tests, and publishes the APK to GitHub Releases.
