#!/usr/bin/env bash
# Build Servo's own Android shell (servoshell) from the prepared tree with mach.
# Prepares the pinned upstream baseline from our downstream repository.
#
# Output: servo-checkout/servo/target/<triple>/release/servoapp.apk
set -euo pipefail

"$(dirname "${BASH_SOURCE[0]}")/fetch-servo.sh" upstream
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

cd "$COMBRAY_SERVO_SRC"
./mach build --locked --target "$COMBRAY_RUST_TARGET" --release

APK="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/servoapp.apk"
combray_sha256 "$APK"
