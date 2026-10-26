# Combray

> [!IMPORTANT]
> LLM disclosure: This codebase was written with substantial help from large language models: AI coding agents working from the [`AGENTS.md`](AGENTS.md) brief in this repo.

**Latest release:** v<!-- version -->0.1.0<!-- /version --> · [Download](https://github.com/L-K-M/Combray/releases/latest)

A personal Android browser powered by **Servo**, with controls at the bottom and one place to return to open tabs and recently visited pages.

<img src="media-sources/icon.png" alt="Combray: an ivory folded C on a square pine-green background" width="160" height="160">

Combray connects to **Karakeep** for quick saves, **Hister** for long-term history and full-text search, and **Kagi** for web search. The initial audience is its owner, for personal use.

## Status

Combray runs on a pinned commit of [our downstream Servo](https://github.com/L-K-M/servo/tree/combray/0.6), based on v0.6.0, through its own native bridge (M0). The app (M1–M3) has bottom controls, independent tabs that survive process death, one Recent list for tabs and recent history, Kagi search, one-tap Karakeep saves through a durable queue, and opt-in Hister capture of rendered page text with long-term search. All 18 device checks pass on an x86_64 emulator against synthetic pages and fake services; **no physical device, real service, live site or login has been tried yet**. M4 has started with verified APK staging, local signing support and third-party notices in the app; personal signing and release CI remain pending. Status per check, open engine blockers and the next task are in the [implementation plan](docs/IMPLEMENTATION_PLAN.md#9-milestones-and-implementation-tasks).

## Start here

1. Read [AGENTS.md](AGENTS.md) for implementation constraints and working instructions.
2. Read the [implementation plan](docs/IMPLEMENTATION_PLAN.md). It is the authoritative, self-contained handoff: requirements, architecture, data model, integration contracts, milestones, and acceptance checks.
3. Consult [integration evidence](docs/INTEGRATIONS.md) before changing the HTTP clients.
4. Open the [design reference](docs/design/README.md) and its interactive HTML concept. Both Recent layouts are retained; the final layout has not been selected.
5. Read the [engine spike report](docs/ENGINE_SPIKE.md) and [build instructions](docs/BUILDING.md) before building the app or touching the engine.

## Repository check

Requires Python 3.10 or later; no third-party Python packages:

```sh
python3 scripts/check_docs.py
git diff --check
```

These checks validate repository documentation and assets. The `checks.yml` workflow runs the documentation check on pushes and pull requests once committed and pushed.

The app's unit tests and lint run with `./gradlew testDebugUnitTest lintDebug` and need only a JDK and the Android SDK. Building the engine needs the pinned Servo tree and an Android NDK; device checks (`scripts/check-app.sh`) need a device or emulator. See [BUILDING.md](docs/BUILDING.md). The engine bridge's host unit tests run with `cd native && cargo test --lib` after `scripts/m0/fetch-servo.sh`.

## Build and release tooling

Build, install, release, and CI tooling works with the app's Gradle project; see [AGENTS.md](AGENTS.md#build-and-release-tooling). Routine CI checks Kotlin, schemas, scripts and formatting; its APK has no engine. The separate Engine workflow builds both native ABIs and runs x86_64 fixture checks for engine-related PRs. `scripts/build.sh` builds the engine first from the exact downstream commit, then checks the packaged engine and architecture before staging. Local release signing uses environment variables; see [BUILDING.md](docs/BUILDING.md#local-release-signing). Publishing still needs the signed native release path (M4).

```sh
scripts/build.sh                 # release APK -> dist/
scripts/build.sh --debug         # debug APK -> dist/
scripts/build.sh --install       # build, install on the connected device, launch
scripts/release.sh X.Y.Z --push  # bump, tag vX.Y.Z, push; the tag publishes the APK
```

## Contents

| Path | Purpose |
| --- | --- |
| `docs/IMPLEMENTATION_PLAN.md` | Complete implementation brief and handoff |
| `docs/INTEGRATIONS.md` | Source-backed API observations and compatibility caveats |
| `docs/design/` | Existing UI concepts, sample interactions, and design guidance |
| `docs/ENGINE_SPIKE.md`, `docs/BUILDING.md` | M0 results, decisions and reproducible build |
| `app/`, `core/`, `engine/` | The Android app: Compose UI; logical tabs, history, outbox and service clients; Kotlin engine port |
| `native/` | Combray's Rust bridge to the downstream Servo commit in `scripts/m0/servo-pin.sh` |
| `testing/fixtures/` | Synthetic pages and fake Karakeep/Hister servers for the device checks (`scripts/check-app.sh`) |
| `spike/` | M0 engine harness app and fixture site (test tooling) |
| `scripts/m0/` | Fetch, build, emulator and check scripts for M0 |
| `media-sources/` | Original square icon and generation provenance |
| `scripts/check_docs.py` | Dependency-free documentation and asset checks |
| `scripts/build.sh`, `install.sh`, `release.sh` | Build, device install, and release helpers |

## License

Combray's own code is released under the [Unlicense](LICENSE). The APK bundles Servo and its Rust crates, SpiderMonkey and the other C/C++ libraries compiled into the engine, the Rust standard library, the NDK's LLVM runtime, and the AndroidX, Jetpack Compose and Kotlin libraries, which keep their own licenses. Their notices ship inside the APK (`assets/notices/native.json`, `assets/notices/Rust-Licenses.html` and Licensee's `assets/app/cash/licensee/artifacts.json`) and appear under Settings › About › Third-party notices, next to the exact engine source. The license files in `native/licenses/` and `native/src/vendor/` are those components' texts, not Combray's. See [third-party notices](THIRD_PARTY_NOTICES.md) for the inventory.
