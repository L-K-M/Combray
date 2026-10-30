# Building Combray

This builds the Combray app (pinned, patched Servo, Combray's native bridge, the Kotlin app) and the M0 engine harness used for engine-level checks. Results and the reasoning behind engine choices are in [ENGINE_SPIKE.md](ENGINE_SPIKE.md).

```text
servo-checkout/servo           exact downstream Git checkout (ignored by Git)
native/                       Rust cdylib libcombray_engine.so: JNI bridge, engine thread
engine/  core/  app/          Gradle modules: engine port, logical state and integrations, Compose UI
engine/jniLibs/<abi>/         native libraries staged by scripts/build-native.sh (ignored by Git)
testing/fixtures/server.py    synthetic pages and fake Karakeep/Hister for the device checks
scripts/                      build-native.sh, check-app.sh, generate-launcher-icons.py, build.sh, ...
spike/android/                M0 harness app ch.lkmc.combray.enginespike (engine checks)
spike/fixtures/server.py      M0 fixture site
scripts/m0/                   env.sh, fetch-servo.sh, build-*.sh, emulator.sh, run-checks.sh, smoke-upstream.sh
```

## Pinned inputs

| Input | Version | Source of truth |
| --- | --- | --- |
| Downstream Servo | `L-K-M/servo`, git `331149c771f2ab018a0412c366c810f67197e900` (`combray/0.7`) | `scripts/m0/servo-pin.sh`; the newest `combray/0.N` branch is the downstream line, not a moving build input |
| Upstream base | v0.7.0, git `aac43a3f31a259f04a574f5ec4e959c943ad7cc7` | `COMBRAY_SERVO_UPSTREAM_REVISION` in the same pin file; also fetched from our repository |
| Engine changes | Document identity and guarded evaluation, render-error logging, profile persistence, DOM-only `:has()` queries, fixed-layer scroll chaining, readable default colors, loopback-only WebDriver, patched TLS, crypto and zstd crates, a backported timer GC fix, the `xmlns` attribute prefix, `console.table` and `console.dirxml`, every PerformanceTiming name in `performance.measure()`, backported flex layout reuse, SVG `getTotalLength()` and `getPointAtLength()`, and resolved styles and `var()` in rasterized SVG subtrees | Downstream commits; the old patch directory is retired. Git source needs no vendor workaround |
| Page input | Focus defaults on shadow-root fields; context-menu paste text bound to the original document and node | [Reproduction and checks](ENGINE_SPIKE.md#33-shadow-root-fields-and-paste-30-october-2026) |
| Scroll repaint fix | Restore scroll offsets when iframe pipelines start or exit and when a shared-context view closes | [Regression and validation](ENGINE_SPIKE.md#32-scroll-position-flicker-30-october-2026) |
| Rust | 1.97.1 with `aarch64-linux-android`, `x86_64-linux-android` | `native/rust-toolchain.toml` (same as Servo) |
| Crates | Servo's release `Cargo.lock`, plus this crate | `native/Cargo.lock` (exact versions, crates.io checksums) |
| PDFium-wasm | `@embedpdf/pdfium` 2.15.1: `pdfium.wasm` and `index.browser.js`, committed `gzip -9n` in `native/src/pdf/vendor/` | `scripts/vendor-pdfium.py` (npm integrity and SHA-256 pins), the same SHA-256s in `native/src/pdf.rs` tests ([Updating PDFium](#updating-pdfium)) |
| SpiderMonkey | prebuilt `libmozjs-<target>.tar.gz` for `mozjs_sys 153.3.0-1`, downloaded by its build script | Servo's lockfile |
| Android NDK | r29, `29.0.14206865` | `env.sh`, which also rejects a `PATH` where the linker wrapper named in `native/.cargo/config.toml` resolves outside it; Servo's build rejects other majors |
| Android SDK | platform `android-37` (compileSdk), build-tools 36.0.0 | `app/build.gradle.kts`, `spike/android/app/build.gradle.kts` |
| App: Gradle / AGP / Kotlin | Gradle 9.8.1 (distribution SHA-256 pinned), AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12 | `gradle/wrapper/`, `gradle/libs.versions.toml` |
| App libraries | Compose BOM 2026.09.00 (Material 3 1.4.0), Room 2.8.5, WorkManager 2.12.0, Lifecycle 2.11.0, kotlinx coroutines/serialization 1.11.0; tests: Robolectric 4.17, JUnit 4.13.2 | `gradle/libs.versions.toml` |
| Harness: Gradle / AGP | Gradle 9.5.1 (distribution SHA-256 pinned), Android Gradle Plugin 9.2.1 | `spike/android/gradle/wrapper/`, `spike/android/build.gradle.kts` |
| Maven artifacts | By version only: no Gradle dependency verification or lockfile yet | Google Maven, Maven Central. Checksum verification should be generated on each supported host OS before a release (M4) |
| JDK | 21 (verified); Servo's CI uses 17 | host |
| API levels | App: minSdk 30, targetSdk 36. Harness: minSdk 30, targetSdk 34 | the native library links with API 30 linker wrappers |

## Host prerequisites

Verified on Linux x86_64 (Debian 12 container, 16 cores, 125 GiB RAM). The scripts also select the NDK's macOS tools, but no macOS build has been verified yet. Plan for about **25 GB** of free disk: Servo source without `vendor/` (1.3 GB), Cargo registry (about 2 GB), shared build directory (about 5 GB per ABI and profile), Gradle cache, NDK (2.4 GB) and an emulator image.

- JDK 17 or 21, `curl`, `git`, `make`, `cmake` (aws-lc-sys), `python3` ≥ 3.11 and [`uv`](https://docs.astral.sh/uv/) (mach and Servo's code generators). On macOS: `xcode-select --install`, then `brew install cmake uv`.
- `rustup`. Run Cargo through rustup's proxy (`~/.cargo/bin`); `env.sh` puts it first so cargo wrappers do not change the build. The build installs Rust 1.97.1 and the Android target it needs.
- The Android SDK with its command-line tools (Android Studio's SDK Manager: "Android SDK Command-line Tools"). It is found through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties` or the default locations (`~/Library/Android/sdk`, `~/Android/Sdk`). The build installs NDK 29.0.14206865 with `sdkmanager` when it is missing; Gradle installs the platform and build tools.

- A **host** C/C++ compiler for build scripts, `clang`/`clang++` by default. Set `COMBRAY_HOST_CC` and `COMBRAY_HOST_CXX` to use another; changing them recompiles every build script. The reference host has no system compiler; it used `zig cc` 0.17 through two wrapper scripts (`COMBRAY_HOST_CC=servo-checkout/hostbin/host-cc`, `COMBRAY_HOST_CXX=servo-checkout/hostbin/host-c++`, local and not in Git) that drop Cargo's `--target=` flag, pin `-target x86_64-linux-gnu.2.28` and add `-fno-sanitize=undefined`. Without that flag zig enables UBSan at `-O0`, and WebRender's build script aborts inside glsl-optimizer (`applying non-zero offset 1 to null pointer`).

`scripts/m0/env.sh` reproduces mach's Android environment (compilers, `BINDGEN_EXTRA_CLANG_ARGS`, `ANDROID_API_LEVEL`, the x86_64 compiler-rt link argument) for the external `native/` crate. Override `ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT` and `COMBRAY_ABI` (`x86_64` default, `arm64`) as needed. The reference host used `ANDROID_SDK_ROOT=$HOME/android-sdk`.

## Build the app

```sh
scripts/build.sh --debug                       # arm64 (phones) → dist/combray-debug-v<version>-android-arm64.apk
COMBRAY_ABI=x86_64 scripts/build.sh --debug    # for the emulator
scripts/build.sh --debug --install             # then install and launch on the connected device
```

With `--install`, the build first asks adb for a usable device (`scripts/install.sh --device-check`) and stops at once if there is none, naming the likely cause: USB debugging off, the phone's "Allow USB debugging?" prompt not accepted, an offline connection, or a second adb version on `PATH`. `scripts/install.sh` runs the same check before Gradle and uses the SDK's adb, as Gradle does.

`scripts/build.sh` prepares and builds the engine first through `scripts/build-native.sh`. That script installs the pinned NDK and Rust toolchain when missing and checks out the exact downstream commit with `scripts/m0/fetch-servo.sh`. Every build restores and verifies the pinned source. It then compiles the engine and stages it in `engine/jniLibs/<abi>/`. The first run downloads several gigabytes and compiles Servo, which takes an hour or more; later runs rebuild only what changed. The steps also run on their own:

```sh
scripts/m0/fetch-servo.sh             # fetch the exact downstream SHA; no Android SDK needed
scripts/build-native.sh               # cargo build + strip → engine/jniLibs/x86_64 (COMBRAY_ABI=arm64 for phones)
./gradlew assembleDebug               # → app/build/outputs/apk/debug/app-debug.apk (ch.lkmc.combray.debug)
```

After the first fetch, checking out Servo's large source tree can take several
minutes. The script announces this step and requests Git's checkout progress.
`^\` followed by `zsh: quit` means the process received SIGQUIT (normally
Ctrl-\). Rerun the same build command after an interruption: preparation
replaces partial or edited source and removes untracked leftovers, reusing
the downloaded commit and ignored build caches. `servo-checkout/servo` is
disposable; develop engine changes in a separate downstream checkout.

`scripts/build.sh [--debug] [--install]` then runs Gradle, verifies the produced APK and stages it with a SHA-256 sidecar in `dist/`. Its filename uses the actual packaged architecture (`arm64` or `x86_64`). Missing Servo/runtime libraries, mismatched ELF architectures and stale Gradle output metadata fail before staging. These checks establish packaging, not runtime support or signature validity.

Plain Gradle still builds without `build-native.sh` (CI does this); that APK contains no engine and the app says so. `scripts/build.sh` refuses to stage it. Unit tests, lint and compiling the device checks need no engine:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
```

CI runs these, then fails when the build changed or added a file in `core/schemas`: an entity changed without its exported schema being committed. `MigrationTest` holds the identity hash of every committed schema version, so a committed change to an existing version fails as well; add each new version's hash there by hand.

Build-script regression checks need Python 3.10+ and no SDK:

```sh
python3 -I -m unittest discover -s scripts/tests
```

To regenerate the launcher icon after changing `media-sources/icon.png`: `python3 scripts/generate-launcher-icons.py` (needs Pillow).

### Third-party notices

Settings › About lists the APK's third-party components with their license texts and links this repository and the exact engine commit. The list covers the parts below; `scripts/verify-apk.py` fails a build whose APK lacks the notice files. [Third-party notices](../THIRD_PARTY_NOTICES.md) has the inventory.

| Part | Source | When it updates |
| --- | --- | --- |
| Rust crates linked into the engine | `cargo about` (0.9.2) with `native/about.toml` | Regenerate after changing `native/Cargo.lock` |
| C/C++ code compiled into the engine (SpiderMonkey and its ICU, ANGLE, FreeType, HarfBuzz, …), libc++, libunwind and compiler-rt | `native/bundled-notices.toml`, read from the crates' sources and the pinned NDK | Recheck the list after changing the Servo pin; it was found by listing the linked archives' members and symbols |
| Vendored JavaScript, PDFium-wasm and the libraries built into it, and Servo's baked-in data files (Public Suffix List, HSTS preload list, GATT blocklist) | `native/bundled-notices.toml`, with `native/src/vendor/*.LICENSE.md`, `native/src/pdf/vendor/*.LICENSE` and `native/licenses/` | Recheck after updating a vendored script or PDFium ([Updating PDFium](#updating-pdfium)) or changing the Servo pin (`components/default-resources`) |
| Crates cargo-about shows without their own copyright line | `[[crate]]` entries in `native/bundled-notices.toml`, texts from the crate, `native/licenses/` or the Servo checkout | The generator fails on an SPDX template (`<year> <owner>`) or a `[[crate]]` version that no longer matches |
| Rust standard library | The pinned toolchain's `COPYRIGHT-library.html`, as text and verbatim `Rust-Licenses.html` | Regenerate after changing `native/rust-toolchain.toml` |
| Java/Kotlin libraries | Licensee Gradle plugin, at build time | Automatic; the build fails on a license other than Apache-2.0 |

```sh
cargo install cargo-about --version 0.9.2 --locked   # once
ANDROID_SDK_ROOT=... scripts/generate-notices.py     # needs a native build's registry sources
```

The script writes `app/src/main/assets/notices/native.json` and `Rust-Licenses.html` beside it, with hashes of its inputs (including the Servo pin and the Rust toolchain); `scripts/tests/test_notices.py` fails CI when an input changed without regenerating. The cargo-about target filter keeps a few crates the Android build does not link (for example `wayland-sys`); listing extra is harmless. This inventory is not a legal review.

### Local release signing

The owner confirmed `ch.lkmc.combray` as the release ID on 19 October 2026. Supply an existing personal keystore through environment variables; keep it outside Git and back it up for future app updates:

| Variable | Value |
| --- | --- |
| `COMBRAY_KEYSTORE_FILE` | Keystore path, absolute or relative to the repository root |
| `COMBRAY_KEYSTORE_PASSWORD` | Keystore password |
| `COMBRAY_KEY_ALIAS` | Signing key alias |
| `COMBRAY_KEY_PASSWORD` | Signing key password |

Set passwords through your local secret manager or hidden shell input. All four variables are required together. An incomplete set fails every Gradle invocation, including debug builds, tests and IDE sync; unset all four or complete the set. Values are not printed. Then run:

```sh
scripts/build.sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

Without signing variables, `./gradlew assembleRelease` produces an unsigned APK for CI checks. `scripts/build.sh` requires explicit signing settings for release staging. Debug signing is unchanged.

Local verification on 19 October 2026 used a disposable RSA key: the x86_64 release built, APK Signature Scheme v2 verified, and its certificate matched that key. After an unsigned rebuild, the verifier rejected the leftover signed APK because the current metadata selected the unsigned file. The disposable key and staged test-signed APK were removed. This was a signing/build test, not device acceptance.

The current arm64 engine also built and packaged successfully: `COMBRAY_ABI=arm64 CARGO_BUILD_JOBS=8 scripts/build-native.sh` (8m47s on the reference Linux host, Rust 1.97.1/NDK r29), then `scripts/build.sh --debug`. `verify-apk.py --expected-arch arm64` passed against that APK. No arm64 runtime was exercised. Interrupted-copy regression coverage verifies that a failed stage preserves the previous APK and checksum.

Before publishing: supply the personal signing key, and wire native compilation and signing into `release.yml`. Its arm64 artifact check already rejects missing or incorrectly labelled native libraries. No release tag has been cut.

## Build the M0 engine harness

```sh
scripts/m0/fetch-servo.sh             # prepare the exact downstream commit
scripts/m0/build-harness.sh           # cargo build + strip + Gradle → app-debug.apk
```

Output: `spike/android/app/build/outputs/apk/debug/app-debug.apk` (x86_64, about 142 MB; the stripped native library is about 131 MB). The Rust code is built with Cargo's `release` profile; only the Android packaging is a debug variant, so `run-as` works for collecting reports. Use `COMBRAY_ABI=arm64` for devices.

Upstream baseline (Servo's own shell at the pinned base, fetched from our repository):

```sh
scripts/m0/fetch-servo.sh upstream
scripts/m0/build-upstream.sh          # servo-checkout/servo/target/<triple>/release/servoapp.apk
scripts/m0/smoke-upstream.sh emulator-5560
```

`smoke-upstream.sh` repeats mach's Android `smoketest`: it installs `servoapp.apk`, opens a `data:` URL whose script logs `SERVO_ANDROID_SMOKETEST_OK`, and passes when that marker reaches logcat before a crash marker. mach waits 30 seconds; the script waits up to `COMBRAY_SMOKE_TIMEOUT` (900 s default) for software emulation.

`fetch-servo.sh` force-checks out the exact commit and cleans untracked source on every run, including after an interrupted first checkout. Ignored caches such as `target/` and the Python environment are reused; local source edits and commits are not build inputs. Successful Python preparation is recorded inside `.git/combray-prepared`; a cached commit, matching stamp and existing Python environment need no network. A deleted environment is prepared again. Both build scripts prepare their required mode, so a baseline build cannot silently become the app's engine.

The old `servo-checkout/servo-v0.6.0` release extraction is untouched. It is no longer used; inspect it before removing it. To reuse its compilation cache, set `CARGO_TARGET_DIR` explicitly to its `target/` directory.

Host unit tests for the engine's pure modules (no Servo build needed):

```sh
cd native && cargo test --lib
```

They still need the fetched tree because Cargo reads the Android-only path dependencies. The Engine workflow runs these tests, builds and verifies both Android ABIs, and runs the x86_64 app fixture checks on an API 30 emulator. It is path-filtered to engine-relevant PRs and can also run manually. Routine CI remains Kotlin-only; release publishing still needs its native/signing path. A green host test is not a native build or device result.

## Updating Servo

**Owner rule: source pulls only from `servo/servo`.** No issues, PRs, comments, reviews, reactions, pushes, GitHub fork API calls or outreach. All AI-assisted work and review stays in `L-K-M/servo` and `L-K-M/Combray`. The downstream repository preserves upstream history without a GitHub fork relationship, and has no copied upstream workflows, reviewer assignments or dependency bot configuration.

Follow upstream releases (owner decision, 24 October 2026). The newest `combray/0.N` branch is the published downstream line. Upstream backports to `release/v0.N` are merged into it; a new upstream release line `v0.M` starts `combray/0.M` at the current tip, and its release tag is merged there. Upstream `main` is never merged wholesale; a fix that only exists there is backported with `git cherry-pick -x`. The [nightly task](SITECHECK.md#the-nightly-task) checks upstream weekly and does this work; take security fixes promptly by hand when needed.

In a clean downstream clone, for a new release line (`0.N` is the current line, `v0.M.x` the new release tag):

```sh
git fetch origin
git push origin origin/combray/0.N:refs/heads/combray/0.M
git switch -c engine/upstream-v0.M.x origin/combray/0.N
git fetch --no-tags https://github.com/servo/servo.git refs/tags/v0.M.x
git merge --no-ff FETCH_HEAD
git diff --name-status origin/combray/0.N -- .github/
```

For backports, branch from `origin/combray/0.N` and merge `refs/heads/release/v0.N` the same way.

Resolve engine/API conflicts and inspect any reintroduced `.github` automation before pushing downstream. Keep original licenses. Open the engine PR against the new or current `combray/0.N`, merge it with a merge commit, and never rewrite published history. After a merge, drop downstream commits that upstream now covers (keep their tests where useful) and list them in the PR. A new line becomes the downstream repository's default branch once its merge lands.

In a Combray PR, set the full candidate SHA and its upstream base in `scripts/m0/servo-pin.sh`. Update this table and `ENGINE_SPIKE.md`; reconcile `native/Cargo.lock`, resources, toolchains and notices if they changed. Require host tests, both native ABI builds, packaging checks and x86_64 fixture/device evidence on that candidate. Run critical regressions in the M0 harness as needed. Merge the downstream engine PR preserving its commits, then land the Combray pin only after its CI and review pass. The Engine workflow records build/device evidence; an arm64 build does not prove arm64 runtime support.

Rollback: revert the Combray pin change to the previous tested SHA and rebuild. Keep the downstream commits available; no force-push or source deletion is needed.

## Updating PDFium

The PDF viewer's renderer is `@embedpdf/pdfium` from the npm registry. Its two runtime files are committed gzip-compressed (owner decision, 29 October 2026), so builds need no network for them and the native library embeds them with `include_bytes!` (`native/src/pdf.rs`, about 2.2 MB of the APK instead of 4.9 MB raw).

```sh
python3 -I scripts/vendor-pdfium.py                 # download, check, write native/src/pdf/vendor/
python3 -I scripts/vendor-pdfium.py --tarball <tgz> # the same from a tarball already downloaded
```

The script downloads the tarball into a new empty temporary directory, checks the registry's `sha512` integrity and the SHA-256 of `pdfium.wasm` and `index.browser.js`, and writes them with `gzip -9n` (no name or time, so the output is reproducible with the same gzip) next to the package's `LICENSE` and `LICENSE.pdfium`.

To move to another version:

1. Read the new version's `dist.integrity` from `https://registry.npmjs.org/@embedpdf/pdfium` and set `VERSION` and `INTEGRITY` in `scripts/vendor-pdfium.py`.
2. Download the tarball once, compute the two files' SHA-256, review the package's changes, and set both pins in the script and in the `native/src/pdf.rs` tests.
3. Run the script, then `python3 -I -m unittest discover -s scripts/tests` and `cd native && cargo test --lib`.
4. Recheck the libraries built into `pdfium.wasm` and `index.browser.js` against `native/bundled-notices.toml` and [native/licenses](../native/licenses/README.md), and regenerate the notices ([Third-party notices](#third-party-notices)). Strings reveal only some of them (libpng and zlib versions, FreeType, OpenJPEG, Little CMS, libc++, musl). Read the package's build recipe (`packages/pdfium` on embed-pdf-viewer's release branch: GN arguments, the PDFium fork's `DEPS` and the `deps` of `core/fxcrt`, `core/fxcodec` and `core/fxge`, and the Emscripten image) and look for the data tables of libraries without strings, such as ICU's case-mapping tables, fast_float's power-of-five table and libjpeg's `jpeg_natural_order`.
5. Record the version in `docs/PDF.md` and this table. Check the viewer on a device once it exists.

Rollback: revert the commit that changed the files and pins.

## Emulator and checks

App checks (instrumented tests against the real engine and `testing/fixtures/server.py`, then a process-death check across a killed process):

```sh
scripts/m0/emulator.sh
./gradlew assembleDebug assembleDebugAndroidTest
scripts/check-app.sh emulator-5560    # or: scripts/check-app.sh emulator-5560 ch.lkmc.combray.SaveTest
```

The default run covers every `*Test.kt` class in `app/src/androidTest`, then `ProcessDeathTest`'s two runs around a killed process; `scripts/check-app.sh --list` prints those classes without a device.

Evidence goes to `build/device-checks/<UTC time>/` (or `$COMBRAY_RESULTS`): raw instrumentation output per run, logcat and `summary.md`; the script exits 1 if a check failed, a run reported nothing, or a run stopped early (a crash or not-responding kill marks the test in progress as crashed and counts the rest as not run). Fixture pages are reached at `localhost:8766` through `adb reverse`; the fake services at `10.0.2.2:8766` (the emulator's address for the host), because a service's own host is never captured for Hister. On a physical phone, use a host address the phone reaches.

Engine-level checks of the M0 harness:

```sh
scripts/m0/emulator.sh                # creates and boots combray-m0-api30-x86_64
scripts/m0/run-checks.sh emulator-5560
```

`emulator.sh` uses KVM when `/dev/kvm` is writable; otherwise it falls back to software emulation (`-accel off`). `run-checks.sh` serves the fixtures, installs the APK with fresh app data, runs the in-app `core` scenario, drives lifecycle phases from the host (Home and return, rotation, clean shutdown, force-stop) and finally runs the stale-frame reproduction. It takes 20–35 minutes on the software-emulated reference AVD. Evidence goes to `servo-checkout/results/<UTC time>/` (or `$COMBRAY_RESULTS`): one JSON report per phase, logcat, screenshots and `summary.md`. The script exits with status 1 when any check failed, a phase produced no report, or a report holds no results, so a run with the known defects ([stale frame](ENGINE_SPIKE.md#6-open-defect-stale-frame), cookies after a kill) exits 1. To run single scenarios: `scripts/m0/run-checks.sh emulator-5560 core diag-tail2:dwell-eight-hidden-tabs`.

Under software emulation, Android's System UI often misses its ANR deadline and leaves a "System UI isn't responding" dialog over the app. It does not affect the M0 harness checks, which inject input into the harness view directly, but it spoils screenshots; `adb root && adb shell pkill -f com.android.systemui` clears it. For the app checks the dialog dims the screen and takes input focus (`screen shows` a darkened colour, `Failed to inject touch input`); `check-app.sh` and the tests close it with the `CLOSE_SYSTEM_DIALOGS` broadcast before each run, screen check and tap. The broadcast does not always close it: if every check fails with a darkened screen (`control page not painted`, `screen shows`), restart System UI as above and rerun. The `hide_error_dialogs` setting does not prevent it on Android 11.

For an arm64 device: `COMBRAY_ABI=arm64 scripts/m0/build-harness.sh`, then `scripts/m0/run-checks.sh <serial>`. M0 built and packaged arm64 (134 MB APK; 6–8 minutes on the 16-core host once host tools were cached) but **did not run** it: no arm64 device was available. Switching ABIs in one build directory triggers partial rebuilds, because some build scripts track ABI-specific environment variables; give each ABI its own `CARGO_TARGET_DIR` if you alternate often.

## Problems met and their fixes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `installDebug`: `No connected devices!` although the phone is plugged in | adb does not list the phone as a usable `device`: USB debugging off, the authorization prompt not accepted, a charge-only cable, or two adb versions restarting each other's server | `scripts/install.sh` checks with the SDK's adb before Gradle and names the cause; run `<sdk>/platform-tools/adb devices -l` to see the state |
| `Servo currently only supports NDK r29.` | The Servo book still mentions NDK 28.2; v0.6.0's mach requires r29 | Install `ndk;29.0.14206865` |
| `current package believes it's in a workspace when it's not` in `mozjs_*_glue` | Their build scripts run `cbindgen`, which runs `cargo metadata` inside `vendor/` | Patch 0001: no `vendor/`, crates from crates.io at the locked versions |
| `failed to select a version ... encoding_rs (locked to 0.8.41)` | The same glue crates carry their own `Cargo.lock`; `vendor/` only has 0.8.35 | Patch 0001 |
| WebRender build script `SIGABRT`, `applying non-zero offset 1 to null pointer` | zig's UBSan in host C++ code at `-O0` | Host compiler wrapper with `-fno-sanitize=undefined` |
| `emulator: error while loading shared libraries: libX11.so.6` | Headless host without X11 libraries | Install them, or point `COMBRAY_EMULATOR_LIBS` at a directory that has them |
| `cmd: Failure calling service package: Broken pipe`, then `Lost network stack` | Under software emulation and heavy host load, Android's framework watchdogs restart `system_server` during a 140-160 MB install | Boot without concurrent host builds, give the AVD 6 cores and 6 GB, disable install verification, retry after `sys.boot_completed` settles |
| AGP 9: `You cannot add Provider instances to the Android SourceSet API` | AGP 9 source-set rules | Plain path in `jniLibs.directories` |
| `emulator: error while loading shared libraries: libX11.so.6` with `-no-window` | The `emulator` launcher and the headless qemu both link libX11 | `COMBRAY_EMULATOR_BIN=$ANDROID_SDK_ROOT/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless` plus `COMBRAY_EMULATOR_LIBS` pointing at a user-space libX11 |
| `unknown virtual device name` from the emulator although `avdmanager` lists the AVD | avdmanager keeps AVDs under `~/.config/.android` (XDG) when `~/.android` is absent; the emulator only reads `ANDROID_AVD_HOME` | `emulator.sh` resolves and exports `ANDROID_AVD_HOME` |
| `adb install`: `cmd: Can't find service: package` after boot | system_server restarted under load (software emulation) | `check-app.sh` pushes the APK first, waits until `system_server` has run for three minutes and the package service answers, and retries up to three times |
| Every page check fails with `screen shows 000000`; `screencap` of the launcher is black too | After hours of headless running, the emulator's compositor stops producing frames (seen once, after about 2.5 hours and several `system_server` restarts) | Restart the emulator. `check-app.sh` now checks a home-screen screenshot first and stops with this advice |
| A screen check sees Combray's home screen or an old page although the coordinator has moved on | Under a Compose test rule the UI only recomposes when the test drives the test clock; checks that talk to the coordinator directly never do | `Device` advances the Compose clock by one frame before each screenshot |
| `Injecting to another application requires INJECT_EVENTS` | A System UI ANR dialog has input focus | The tests close system dialogs before taps |
| A check ends with `keyDispatchingTimedOut` (`Waited 5000ms for FocusEvent`) when it opens a sheet or dialog in the browser | Under software emulation and host load, the main thread needs over 5 s to take a new window's focus (ANR traces showed it busy in Compose layout, not blocked). A bottom sheet on a plain Compose activity timed out the same way (run `a11y1`) | `AboutTest` hosts the notices dialog on a plain Compose activity, and `AccessibilityTest` checks the More sheet's rows without the sheet; retry when the host is less loaded. Device checks still do not open the browser's bottom sheets |
| `Failed to inject touch input. Reason: Expected exactly '1' node ...` | Not an input problem: Compose prefixes a failed node lookup with this text. App data persists across the checks of one run, so a page an earlier check saved has "Saved to Karakeep" instead of a "Save" button | Each check that taps Save uses a page no earlier check saved |
| A check fails now and then with `Method removeObserver must be called on the main thread` (or `addObserver`), thrown in `TestMonotonicFrameClock.performFrame` on a `DefaultDispatcher` worker | A composable collected a flow without a context. Under a Compose test rule the collector resumes on the publishing thread, wakes the recomposer there, and in a narrow race that thread runs the frame; a `BackHandler`'s `LifecycleStartEffect` then fails off the main thread. The app recomposes on the main thread, so only checks see it | Collect UI flows with `collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)`, and write UI state computed on another dispatcher from the main thread; `MainThreadStateTest` fails on the snapshot changes that precede the race |
| Lint `NewApi`: `URLEncoder.encode(String, Charset)` | That overload is API 33; minSdk is 30 (it would crash on Android 11) | `URLEncoder.encode(value, "UTF-8")` |

## Cleaning up

Everything large is ignored by Git and local to the checkout: `servo-checkout/` (source, downloads, build directory, results), `native/target/` (host tests), `engine/jniLibs/`, Gradle `build/` directories (including `build/device-checks/`) and `spike/android/app/build/`. Remove them to reclaim space. The emulator image and AVD live in the Android SDK and `ANDROID_AVD_HOME`; delete the AVD with `avdmanager delete avd -n combray-m0-api30-x86_64`.
