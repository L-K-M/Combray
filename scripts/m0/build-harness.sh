#!/usr/bin/env bash
# Build the M0 engine harness: native/ (patched Servo + JNI bridge) and spike/android (APK).
# Run `scripts/m0/fetch-servo.sh` first.
#
# Output: spike/android/app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

echo "==> cargo build ($COMBRAY_RUST_TARGET, release)"
cd "$COMBRAY_ROOT/native"
cargo build --locked --release --target "$COMBRAY_RUST_TARGET"

LIBRARY="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/libcombray_engine.so"
JNI_DIR="$COMBRAY_ROOT/spike/android/app/build/combray-jni/$COMBRAY_ANDROID_ABI"
LLVM="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

echo "==> staging native libraries in $JNI_DIR"
rm -rf "$COMBRAY_ROOT/spike/android/app/build/combray-jni"
mkdir -p "$JNI_DIR"
# Release builds carry no debug info; dropping the static symbol table saves ~45 MB.
"$LLVM/bin/llvm-strip" --strip-unneeded -o "$JNI_DIR/libcombray_engine.so" "$LIBRARY"
cp "$LLVM/sysroot/usr/lib/$COMBRAY_RUST_TARGET/libc++_shared.so" "$JNI_DIR/"

APK="$COMBRAY_ROOT/spike/android/app/build/outputs/apk/debug/app-debug.apk"
# Incremental packaging rewrites into the previous APK and leaves the replaced native
# library as dead space (an APK of twice the size); package from scratch instead.
rm -f "$APK"

echo "==> gradle assembleDebug"
cd "$COMBRAY_ROOT/spike/android"
./gradlew --no-daemon assembleDebug

ls -l "$APK"
sha256sum "$APK"
