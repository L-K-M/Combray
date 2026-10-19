#!/usr/bin/env bash
# Build Servo's own Android shell (servoshell) from the prepared tree with mach.
# Run `scripts/m0/fetch-servo.sh upstream` first for an unmodified baseline.
#
# Output: servo-checkout/servo-v<version>/target/<triple>/release/servoapp.apk
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

cd "$COMBRAY_SERVO_SRC"
./mach build --locked --target "$COMBRAY_RUST_TARGET" --release

APK="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/servoapp.apk"
sha256sum "$APK"
