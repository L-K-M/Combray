#!/usr/bin/env bash
# Build the native engine (native/: patched Servo + JNI bridge) for the app and stage it where
# the engine module packages it. Run scripts/m0/fetch-servo.sh once first.
#
#   scripts/build-native.sh                 # x86_64 (emulator)
#   COMBRAY_ABI=arm64 scripts/build-native.sh
#
# Output: engine/jniLibs/<abi>/{libcombray_engine.so,libc++_shared.so}. Then build the APK with
# scripts/build.sh (or ./gradlew assembleDebug); without this step the app reports a missing engine.
set -euo pipefail

# shellcheck source=m0/env.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/m0/env.sh"

echo "==> cargo build ($COMBRAY_RUST_TARGET, release)"
cd "$COMBRAY_ROOT/native"
cargo build --locked --release --target "$COMBRAY_RUST_TARGET"

LIBRARY="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/libcombray_engine.so"
JNI_ROOT="$COMBRAY_ROOT/engine/jniLibs"
JNI_DIR="$JNI_ROOT/$COMBRAY_ANDROID_ABI"
LLVM="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

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
