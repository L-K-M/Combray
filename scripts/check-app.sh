#!/usr/bin/env bash
# Run Combray's device checks on a booted device or emulator, against the real engine and the
# fixture server (testing/fixtures/server.py): every *Test.kt class in app/src/androidTest
# (browsing, Karakeep saves, Hister, reader view and more), then process death across a killed
# process.
#
#   scripts/check-app.sh [adb-serial [test-class...]]
#   scripts/check-app.sh --list    # the classes a default run covers, in order; no device needed
#
# Build first: scripts/build-native.sh, then ./gradlew assembleDebug assembleDebugAndroidTest.
# Results: build/device-checks/<UTC time>/ (or $COMBRAY_RESULTS): raw instrumentation output per
# run, logcat and summary.md. Exits 1 when any check failed, a run produced no results or a run
# stopped early.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
SERIAL="${1:-${ANDROID_SERIAL:-emulator-5560}}"
ADB=("$SDK/platform-tools/adb" -s "$SERIAL")
PACKAGE="ch.lkmc.combray.debug"
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
FIXTURE_PORT=8766
TEST_PACKAGE="ch.lkmc.combray"
# Its two runs around a killed process come last.
PROCESS_DEATH_CLASS="$TEST_PACKAGE.ProcessDeathTest"

# Every device-check class except ProcessDeathTest, found by file name: a new class runs by
# default without editing this script.
device_check_classes() {
    local file class
    for file in "$ROOT/app/src/androidTest/kotlin/ch/lkmc/combray/"*Test.kt; do
        [ -f "$file" ] || continue
        class="$TEST_PACKAGE.$(basename "$file" .kt)"
        [ "$class" = "$PROCESS_DEATH_CLASS" ] || echo "$class"
    done
}

if [ "${1:-}" = "--list" ]; then
    device_check_classes
    echo "$PROCESS_DEATH_CLASS"
    exit 0
fi

RESULTS="${COMBRAY_RESULTS:-$ROOT/build/device-checks/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$RESULTS"

if [ ! -f "$APK" ] || [ ! -f "$TEST_APK" ]; then
    echo "!! build the APKs first (see the header)" >&2
    exit 1
fi

PIDS=()
# The device's own "stay awake while charging" setting, restored on exit.
STAY_ON_ORIGINAL=""
# Called by the EXIT trap, which shellcheck does not follow.
# shellcheck disable=SC2317,SC2329
cleanup() {
    for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
    "${ADB[@]}" reverse --remove "tcp:$FIXTURE_PORT" 2>/dev/null || true
    if [ -n "$STAY_ON_ORIGINAL" ]; then
        "${ADB[@]}" shell settings put global stay_on_while_plugged_in "$STAY_ON_ORIGINAL" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Every check against a port another run already holds serves no page at all, and the run then
# reports timeouts with no cause. Readiness is our own server saying so in its own log: probing
# the port would accept whatever answers there, including another run's server, and a dead one is
# a zombie until the shell reaps it, which kill -0 calls alive.
echo "==> fixture server on 127.0.0.1:$FIXTURE_PORT"
python3 -I -u "$ROOT/testing/fixtures/server.py" --port "$FIXTURE_PORT" > "$RESULTS/fixtures.log" 2>&1 &
FIXTURE_PID=$!
PIDS+=("$FIXTURE_PID")
"${ADB[@]}" reverse "tcp:$FIXTURE_PORT" "tcp:$FIXTURE_PORT" > /dev/null

FIXTURE_READY="serving Combray fixtures on"
fixture_alive() {
    kill -0 "$FIXTURE_PID" 2>/dev/null && [ "$(ps -p "$FIXTURE_PID" -o state= 2>/dev/null | tr -d ' ')" != "Z" ]
}
for _ in $(seq 1 20); do
    if grep -q "$FIXTURE_READY" "$RESULTS/fixtures.log" 2>/dev/null; then
        break
    fi
    if ! fixture_alive; then
        echo "!! the fixture server could not start on port $FIXTURE_PORT" >&2
        sed 's/^/    /' "$RESULTS/fixtures.log" >&2
        exit 1
    fi
    sleep 1
done
if ! grep -q "$FIXTURE_READY" "$RESULTS/fixtures.log" 2>/dev/null; then
    echo "!! the fixture server never answered on port $FIXTURE_PORT" >&2
    sed 's/^/    /' "$RESULTS/fixtures.log" >&2
    exit 1
fi

# Under software emulation and host load, Android's system_server restarts (often "Lost network
# stack") and installs then fail. Wait until it has run for a while and the package manager works.
STABLE_SECONDS=180
# Seconds since the youngest system_server started (a dying one can linger next to it), or 0.
system_server_uptime() {
    "${ADB[@]}" shell ps -A -o ELAPSED,NAME 2>/dev/null | tr -d '\r' |
        awk '$2 == "system_server" { n = split($1, part, /[-:]/); s = 0; for (i = 1; i <= n; i++) s = s * 60 + part[i]; if (min == "" || s < min) min = s }
             END { print (min == "" ? 0 : min) }'
}

wait_for_package_manager() {
    for _ in $(seq 1 240); do
        if [ "$(system_server_uptime)" -ge "$STABLE_SECONDS" ] &&
            "${ADB[@]}" shell pm list packages android 2>/dev/null | grep -q "package:android"; then
            return 0
        fi
        sleep 5
    done
    echo "!! the package manager did not settle" >&2
    exit 1
}

# Push first, then install from the device's own storage: a streamed install keeps system_server
# busy for minutes on a slow emulator, and its watchdog then restarts it mid-install.
install() {
    local remote
    remote="/data/local/tmp/$(basename "$1")"
    "${ADB[@]}" push "$1" "$remote" > /dev/null
    for attempt in 1 2 3; do
        wait_for_package_manager
        "${ADB[@]}" shell pm install -r -t "$remote" 2>&1 | grep -q "^Success" && break
        [ "$attempt" -lt 3 ] || { echo "!! installing $(basename "$1") failed" >&2; exit 1; }
    done
    "${ADB[@]}" shell rm -f "$remote"
}

# The checks read real screen pixels. After hours of headless running the emulator's compositor
# can return all-black screenshots (even of the launcher); every page check would then fail for
# a reason unrelated to Combray. Stop early with the remedy instead.
display_works() {
    [ -n "$STAY_ON_ORIGINAL" ] || STAY_ON_ORIGINAL="$("${ADB[@]}" shell settings get global stay_on_while_plugged_in | tr -d '\r')"
    "${ADB[@]}" shell svc power stayon true
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
    "${ADB[@]}" shell input keyevent KEYCODE_HOME
    sleep 5
    # Raw frame: width, height, format, dataspace (little-endian), then RGBA. Anything else (a
    # warning printed before it) fails. A non-black pixel in a sample means frames are composed.
    # stderr stays on the device: exec-out would mix a warning (several displays) into the frame.
    "${ADB[@]}" exec-out 'screencap 2>/dev/null' | python3 -I -c '
import struct, sys
data = sys.stdin.buffer.read()
width, height = struct.unpack("<II", data[:8]) if len(data) >= 16 else (0, 0)
if width == 0 or len(data) != 16 + width * height * 4:
    sys.exit(1)
pixels = data[16:]
sys.exit(0 if any(pixels[i] or pixels[i + 1] or pixels[i + 2] for i in range(0, len(pixels), 4 * 997)) else 1)'
}

wait_for_package_manager
if ! display_works; then
    echo "!! screenshots of $SERIAL are all black (the home screen too): restart the emulator" >&2
    exit 1
fi

# sha256sum, or shasum on macOS: the same output.
sha256() { if command -v sha256sum > /dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
echo "==> installing $(basename "$APK") ($(sha256 "$APK" | cut -d' ' -f1)) and the test APK"
sha256 "$APK" "$TEST_APK" > "$RESULTS/apk.sha256"
install "$APK"
install "$TEST_APK"
# Fresh app data: empty database, credentials and Servo profile.
"${ADB[@]}" shell pm clear "$PACKAGE" > /dev/null
{
    for property in ro.product.model ro.build.version.release ro.build.version.sdk ro.product.cpu.abi; do
        echo "$property=$("${ADB[@]}" shell getprop "$property" | tr -d '\r')"
    done
} > "$RESULTS/device.txt"

"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v threadtime > "$RESULTS/logcat.txt" &
PIDS+=("$!")

# On a fresh emulator the global configuration once changed (screen height 648dp to 599dp) just as
# the first class started, and AboutTest's dialog then showed nothing for its whole wait. Start
# the classes only once the configuration has held for a few seconds; device.txt records it.
CONFIG_STABLE_SECONDS=5
device_configuration() {
    "${ADB[@]}" shell am get-config 2>/dev/null | tr -d '\r' | grep "^config:" || true
}

wait_for_stable_configuration() {
    local previous="" current held=0
    for _ in $(seq 1 120); do
        current="$(device_configuration)"
        if [ -n "$current" ] && [ "$current" = "$previous" ]; then
            held=$((held + 1))
            [ "$held" -lt "$CONFIG_STABLE_SECONDS" ] || return 0
        else
            held=0
        fi
        previous="$current"
        sleep 1
    done
    echo "!! the display configuration did not settle" >&2
    exit 1
}

wait_for_stable_configuration
device_configuration | sed 's/^config: /config=/' >> "$RESULTS/device.txt"

# A check that fails can leave a screenshot and window list in the app's files/failure-evidence
# (AboutTest does). Move them next to the run's output, outside the *.txt files the summary reads.
FAILURE_EVIDENCE="files/failure-evidence"
collect_failure_evidence() {
    local name="$1" file
    for file in $("${ADB[@]}" shell run-as "$PACKAGE" ls "$FAILURE_EVIDENCE" 2>/dev/null | tr -d '\r'); do
        mkdir -p "$RESULTS/$name-failure"
        "${ADB[@]}" exec-out run-as "$PACKAGE" cat "$FAILURE_EVIDENCE/$file" > "$RESULTS/$name-failure/$file"
    done
    "${ADB[@]}" shell run-as "$PACKAGE" rm -rf "$FAILURE_EVIDENCE" 2>/dev/null || true
}

# run NAME ARGS...: one instrumentation run; raw output kept for the summary.
run() {
    local name="$1"
    shift
    echo "==> $name"
    # A System UI "isn't responding" dialog (software emulation) would cover the app; the tests
    # also close it whenever a screen check or a tap needs the app in front.
    "${ADB[@]}" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null || true
    "${ADB[@]}" shell am instrument -w -r "$@" "$RUNNER" > "$RESULTS/$name.txt" 2>&1 || true
    grep -E "^(OK|FAILURES|Tests run)" "$RESULTS/$name.txt" | tail -2 || true
    grep -q "^OK (" "$RESULTS/$name.txt" || collect_failure_evidence "$name"
}

if [ "$#" -gt 1 ]; then
    for class in "${@:2}"; do run "${class##*.}" -e class "$class"; done
else
    for class in $(device_check_classes); do run "${class##*.}" -e class "$class"; done
    value="kill$RANDOM"
    run process-death-prepare -e class "$PROCESS_DEATH_CLASS#prepare" -e value "$value"
    # The run's end killed the process without a clean shutdown; make sure nothing survives.
    "${ADB[@]}" shell am force-stop "$PACKAGE"
    run process-death-verify -e class "$PROCESS_DEATH_CLASS#verify" -e value "$value"
fi

python3 -I "$ROOT/scripts/summarize-instrumentation.py" "$RESULTS" > "$RESULTS/summary.md" && status=0 || status=$?
cat "$RESULTS/summary.md"
echo "evidence: $RESULTS"
exit "$status"
