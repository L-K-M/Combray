#!/usr/bin/env bash
# Build the native engine (native/: patched Servo + JNI bridge) for the app and stage it where
# the engine module packages it. Prepares what it can on the way: the pinned NDK (through
# sdkmanager), Rust toolchain and target, and the pinned Servo tree (scripts/m0/fetch-servo.sh).
# The first run downloads several gigabytes and compiles
# Servo, which takes an hour or more.
#
#   scripts/build-native.sh                 # x86_64 (emulator)
#   COMBRAY_ABI=arm64 scripts/build-native.sh
#
# Output: engine/jniLibs/<abi>/{libcombray_engine.so,libc++_shared.so}. scripts/build.sh runs this
# before Gradle; a plain ./gradlew assembleDebug without it builds an app that reports a missing engine.
set -euo pipefail

# shellcheck source=m0/env.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/m0/env.sh"

combray_require python3 "install Python 3.11+ (macOS: xcode-select --install)"
combray_require rustup "install it from https://rustup.rs"
combray_require cmake "install CMake (macOS: brew install cmake)"
combray_require make "install make (macOS: xcode-select --install)"
combray_require "$HOST_CC" "install clang (macOS: xcode-select --install) or set COMBRAY_HOST_CC"
combray_require "$HOST_CXX" "install clang++ (macOS: xcode-select --install) or set COMBRAY_HOST_CXX"

# The pinned toolchain (native/rust-toolchain.toml) with this ABI's target; checked offline first.
if ! rustup target list --toolchain "$COMBRAY_RUST_TOOLCHAIN" --installed 2> /dev/null | grep -qx "$COMBRAY_RUST_TARGET"; then
    echo "==> installing Rust $COMBRAY_RUST_TOOLCHAIN for $COMBRAY_RUST_TARGET"
    rustup toolchain install "$COMBRAY_RUST_TOOLCHAIN" --profile minimal --target "$COMBRAY_RUST_TARGET"
fi

# Restore the pinned source on every build, even when preparation is cached.
"$COMBRAY_ROOT/scripts/m0/fetch-servo.sh"
# Again, for the Python environment fetch-servo.sh may have created.
# shellcheck source=m0/env.sh disable=SC1091
. "$COMBRAY_ROOT/scripts/m0/env.sh"

echo "==> cargo build ($COMBRAY_RUST_TARGET, release)"
cd "$COMBRAY_ROOT/native"
cargo build --locked --release --target "$COMBRAY_RUST_TARGET"

LIBRARY="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/libcombray_engine.so"
JNI_ROOT="$COMBRAY_ROOT/engine/jniLibs"
JNI_DIR="$JNI_ROOT/$COMBRAY_ANDROID_ABI"
LLVM="$COMBRAY_NDK_LLVM"

echo "==> staging native libraries in $JNI_DIR"
# One ABI at a time: a stale library of the other ABI would ship untested code.
rm -rf "$JNI_ROOT"
mkdir -p "$JNI_DIR"
# Release builds carry no debug info; dropping the static symbol table saves ~45 MB.
"$LLVM/bin/llvm-strip" --strip-unneeded -o "$JNI_DIR/libcombray_engine.so" "$LIBRARY"
cp "$LLVM/sysroot/usr/lib/$COMBRAY_RUST_TARGET/libc++_shared.so" "$JNI_DIR/"

# Incremental packaging would keep the replaced library as dead space in the next APK.
rm -rf "$COMBRAY_ROOT/app/build/outputs/apk"
ls -l "$JNI_DIR"
