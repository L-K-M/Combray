#!/usr/bin/env bash
# Run the M0 engine checks on a booted device or emulator and collect evidence.
#
#   scripts/m0/run-checks.sh [adb-serial [scenario...]]
#
# Without scenario names it runs everything: the in-app `core` scenario, the host-driven
# lifecycle phases, then the stale-frame reproduction with screen checks after Home and
# rotation. With names (e.g. `diag-paint`) it runs only those in-app scenarios, each in a fresh
# app process. Exits non-zero if any check failed, a phase produced no report, or a report holds
# no results.
# Needs the harness APK (scripts/m0/build-harness.sh). Uses only synthetic fixture pages
# served from this machine (spike/fixtures/server.py) through `adb reverse`.
# Evidence lands in servo-checkout/results/<UTC timestamp>/ (or $COMBRAY_RESULTS):
# per-phase JSON reports, logcat, screenshots and summary.md.
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

SERIAL="${1:-${ANDROID_SERIAL:-emulator-5560}}"
ADB=("$ANDROID_SDK_ROOT/platform-tools/adb" -s "$SERIAL")
PACKAGE="ch.lkmc.combray.enginespike"
ACTIVITY="$PACKAGE/ch.lkmc.combray.spike.HarnessActivity"
APK="$COMBRAY_ROOT/spike/android/app/build/outputs/apk/debug/app-debug.apk"
FIXTURE_PORT=8765
PHASE_TIMEOUT_SECONDS=5400
RESULTS="${COMBRAY_RESULTS:-$COMBRAY_WORK/results/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$RESULTS"
LOGCAT="$RESULTS/logcat.txt"

PIDS=()
ROTATION_SETTING="$("${ADB[@]}" shell settings get system accelerometer_rotation | tr -d '\r')"
cleanup() {
    "${ADB[@]}" shell settings put system user_rotation 0 2>/dev/null || true
    if [ "$ROTATION_SETTING" = "0" ] || [ "$ROTATION_SETTING" = "1" ]; then
        "${ADB[@]}" shell settings put system accelerometer_rotation "$ROTATION_SETTING" 2>/dev/null || true
    fi
    # Background subshells (the screenshot watcher pipeline) need their children killed too.
    for pid in "${PIDS[@]}"; do
        pkill -P "$pid" 2>/dev/null || true
        kill "$pid" 2>/dev/null || true
    done
    "${ADB[@]}" reverse --remove "tcp:$FIXTURE_PORT" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> fixture server on 127.0.0.1:$FIXTURE_PORT"
python3 -I "$COMBRAY_ROOT/spike/fixtures/server.py" --port "$FIXTURE_PORT" > "$RESULTS/fixtures.log" 2>&1 &
PIDS+=("$!")
"${ADB[@]}" reverse "tcp:$FIXTURE_PORT" "tcp:$FIXTURE_PORT"

echo "==> device"
{
    for property in ro.product.model ro.build.version.release ro.build.version.sdk \
        ro.product.cpu.abilist ro.hardware.egl ro.kernel.qemu; do
        echo "$property=$("${ADB[@]}" shell getprop "$property" | tr -d '\r')"
    done
    "${ADB[@]}" shell dumpsys SurfaceFlinger | grep -E '^GLES:' || true
} | tee "$RESULTS/device.txt"

echo "==> installing $(basename "$APK") ($(combray_sha256 "$APK" | cut -d' ' -f1))"
combray_sha256 "$APK" > "$RESULTS/apk.sha256"
"${ADB[@]}" push "$APK" /data/local/tmp/combray-m0.apk > /dev/null
"${ADB[@]}" shell pm install -r -t /data/local/tmp/combray-m0.apk
"${ADB[@]}" shell rm /data/local/tmp/combray-m0.apk
# Fresh app data: empty Servo profile and session for every run.
"${ADB[@]}" shell pm clear "$PACKAGE" > /dev/null

"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v threadtime > "$LOGCAT" &
PIDS+=("$!")

# The app logs "M0SCREENSHOT <name>" and pauses while the host captures the screen.
(
    tail -n +1 -F "$LOGCAT" 2>/dev/null | grep --line-buffered -o 'M0SCREENSHOT [A-Za-z0-9_-]*' |
        while read -r _ name; do
            "${ADB[@]}" exec-out screencap -p > "$RESULTS/$name.png" || true
        done
) &
PIDS+=("$!")

done_count() { grep -c "M0DONE $1\$" "$LOGCAT" 2>/dev/null || true; }

# A failed or timed-out phase is recorded and the run continues, so later evidence is kept.
FAILED_PHASES=()

# run_request NAME [ARG] [REPORT-SUFFIX]: start a scenario or phase and wait for it.
run_request() {
    local name="$1" argument="${2:-}" suffix="${3:-}"
    local before
    before="$(done_count "$name")"
    echo "==> $name${argument:+ ($argument)}"
    local extras=(--es m0 "$name")
    [ -n "$argument" ] && extras+=(--es arg "$argument")
    "${ADB[@]}" shell am start -n "$ACTIVITY" "${extras[@]}" > /dev/null
    local started
    started=$(date +%s)
    until [ "$(done_count "$name")" -gt "$before" ]; do
        if [ $(( $(date +%s) - started )) -gt "$PHASE_TIMEOUT_SECONDS" ]; then
            echo "!! $name did not finish within ${PHASE_TIMEOUT_SECONDS}s; stopping the app" >&2
            FAILED_PHASES+=("$name$suffix")
            # A wedged scenario would ignore every later request; start the next one fresh.
            "${ADB[@]}" shell am force-stop "$PACKAGE" || true
            return 0
        fi
        sleep 5
    done
    local report="$RESULTS/report-$name$suffix.json"
    if ! "${ADB[@]}" shell run-as "$PACKAGE" cat "files/m0-report-$name.json" > "$report.partial"; then
        echo "!! could not pull the $name report" >&2
        FAILED_PHASES+=("$name$suffix")
        rm -f "$report.partial"
        return 0
    fi
    mv "$report.partial" "$report"
    python3 -I -c 'import json, sys
for r in json.load(open(sys.argv[1]))["results"]: print("   ", r["status"], r["id"])' "$report" || true
}

if [ "$#" -gt 1 ]; then
    # Each entry is NAME or NAME:ARGUMENT.
    for scenario in "${@:2}"; do
        # Each scenario assumes a fresh engine (fixed tab IDs, empty session).
        "${ADB[@]}" shell am force-stop "$PACKAGE"
        "${ADB[@]}" shell run-as "$PACKAGE" rm -f files/m0-session.json
        run_request "${scenario%%:*}" "$( [[ "$scenario" == *:* ]] && echo "${scenario#*:}" )"
    done
    python3 -I "$COMBRAY_ROOT/scripts/m0/summarize.py" "$RESULTS" > "$RESULTS/summary.md" && status=0 || status=$?
    cat "$RESULTS/summary.md"
    if [ "${#FAILED_PHASES[@]}" -gt 0 ]; then
        echo "!! scenarios without a report: ${FAILED_PHASES[*]}" >&2
        exit 1
    fi
    exit "$status"
fi

run_request core
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$RESULTS/meminfo-after-core.txt" || true

echo "==> background and foreground (surface destroyed and recreated)"
"${ADB[@]}" shell input keyevent KEYCODE_HOME
sleep 30
run_request resume-check

echo "==> rotation (Activity and surface recreated)"
"${ADB[@]}" shell settings put system accelerometer_rotation 0
"${ADB[@]}" shell settings put system user_rotation 1
sleep 45
run_request rotation-check
"${ADB[@]}" shell settings put system user_rotation 0
sleep 45

echo "==> profile persistence across a clean engine shutdown"
clean_value="clean$RANDOM"
run_request persist-set "$clean_value" -clean
run_request shutdown
sleep 15
run_request persist-check clean-exit -clean-exit

echo "==> profile persistence across process death"
kill_value="kill$RANDOM"
run_request persist-set "$kill_value" -kill
"${ADB[@]}" shell am force-stop "$PACKAGE"
sleep 15
run_request persist-check after-kill -after-kill

echo "==> stale-frame variants (the failing one last), then the screen after Home and rotation"
run_request diag-tail2 "" -stale-frame-variants
"${ADB[@]}" shell input keyevent KEYCODE_HOME
sleep 30
run_request screen-check after-home -stale-frame-after-home
"${ADB[@]}" shell settings put system accelerometer_rotation 0
"${ADB[@]}" shell settings put system user_rotation 1
sleep 45
run_request screen-check after-rotation -stale-frame-after-rotation
"${ADB[@]}" shell settings put system user_rotation 0

python3 -I "$COMBRAY_ROOT/scripts/m0/summarize.py" "$RESULTS" > "$RESULTS/summary.md" && status=0 || status=$?
cat "$RESULTS/summary.md"
echo "evidence: $RESULTS"
if [ "${#FAILED_PHASES[@]}" -gt 0 ]; then
    echo "!! phases without a report: ${FAILED_PHASES[*]}" >&2
    exit 1
fi
exit "$status"
