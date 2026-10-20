#!/usr/bin/env bash
# Smoke-test Servo's own Android shell (scripts/m0/build-upstream.sh) on a booted device.
#
#   scripts/m0/smoke-upstream.sh [adb-serial]
#
# Same procedure as mach's Android `smoketest` (python/servo/testing_commands.py): open a data:
# URL whose script logs a marker, then wait for the marker or a crash in logcat. mach allows
# 30 seconds; a software-emulated AVD needs minutes, so the wait is $COMBRAY_SMOKE_TIMEOUT.
# Evidence lands in servo-checkout/results/upstream-smoke-<UTC timestamp>/ (or $COMBRAY_RESULTS).
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SERIAL="${1:-${ANDROID_SERIAL:-emulator-5560}}"
ADB=("$ANDROID_SDK_ROOT/platform-tools/adb" -s "$SERIAL")
PACKAGE="org.servo.servoshell"
APK="$CARGO_TARGET_DIR/$COMBRAY_RUST_TARGET/release/servoapp.apk"
MARKER="SERVO_ANDROID_SMOKETEST_OK"
CRASH_MARKERS="Panic in Rust code|FATAL EXCEPTION|Fatal signal"
TIMEOUT_SECONDS="${COMBRAY_SMOKE_TIMEOUT:-900}"
RESULTS="${COMBRAY_RESULTS:-$COMBRAY_WORK/results/upstream-smoke-$(date -u +%Y%m%dT%H%M%SZ)}"
LOGCAT="$RESULTS/logcat.txt"
mkdir -p "$RESULTS"

echo "==> installing $(basename "$APK") ($(combray_sha256 "$APK" | cut -d' ' -f1))"
combray_sha256 "$APK" > "$RESULTS/apk.sha256"
"${ADB[@]}" install -r "$APK"
"${ADB[@]}" shell am force-stop "$PACKAGE"
"${ADB[@]}" logcat -c

# Like mach: servoshell's own log plus error and fatal lines that explain a crash.
"${ADB[@]}" logcat --format=raw servoshell:D AndroidRuntime:E libc:F DEBUG:F '*:S' > "$LOGCAT" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "==> opening the marker page"
"${ADB[@]}" shell "am start -a android.intent.action.VIEW -d 'data:text/html,<script>console.log(\"$MARKER\")</script>' $PACKAGE/$PACKAGE.MainActivity"

started=$(date +%s)
until grep -qE "$MARKER|$CRASH_MARKERS" "$LOGCAT"; do
    if [ $(( $(date +%s) - started )) -ge "$TIMEOUT_SECONDS" ]; then
        echo "!! no marker within $TIMEOUT_SECONDS s; evidence: $RESULTS" >&2
        exit 1
    fi
    sleep 3
done
echo "==> logcat matched after $(( $(date +%s) - started )) s"
"${ADB[@]}" exec-out screencap -p > "$RESULTS/screen.png"

if grep -qE "$CRASH_MARKERS" "$LOGCAT"; then
    echo "!! servoshell crashed; evidence: $RESULTS" >&2
    exit 1
fi
echo "passed: $MARKER; evidence: $RESULTS"
