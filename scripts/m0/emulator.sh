#!/usr/bin/env bash
# Create (once) and boot the M0 reference emulator: Android 11 (API 30), x86_64, SwiftShader.
#
#   scripts/m0/emulator.sh
#
# Uses KVM when /dev/kvm is writable, otherwise pure software emulation (-accel off), which
# takes 15-20 minutes to boot and is easily starved by concurrent host builds.
# Optional:
#   COMBRAY_AVD                  AVD name (default: combray-m0-api30-x86_64)
#   COMBRAY_EMULATOR_BIN         emulator binary (default: $ANDROID_SDK_ROOT/emulator/emulator;
#                                on hosts without X11 use emulator/qemu/linux-x86_64/qemu-system-x86_64-headless)
#   COMBRAY_EMULATOR_LIBS        extra LD_LIBRARY_PATH entries (e.g. a user-space libX11)
#   ANDROID_AVD_HOME             AVD directory (default: where avdmanager keeps it)
#   COMBRAY_EMULATOR_PORT        console port (default: 5560; adb serial emulator-<port>)
#   COMBRAY_BOOT_TIMEOUT         seconds to wait for boot (default: 3600; a loaded host needs more)
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

AVD="${COMBRAY_AVD:-combray-m0-api30-x86_64}"
IMAGE="system-images;android-30;default;x86_64"
PORT="${COMBRAY_EMULATOR_PORT:-5560}"
SERIAL="emulator-$PORT"
TOOLS="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
BOOT_TIMEOUT_SECONDS="${COMBRAY_BOOT_TIMEOUT:-3600}"

# avdmanager follows XDG (~/.config/.android) when ~/.android is absent; the emulator binaries
# only look where ANDROID_AVD_HOME says, so resolve it once for both.
if [ -z "${ANDROID_AVD_HOME:-}" ]; then
    ANDROID_AVD_HOME="$HOME/.android/avd"
    [ -f "$HOME/.config/.android/avd/$AVD.ini" ] && ANDROID_AVD_HOME="$HOME/.config/.android/avd"
fi
export ANDROID_AVD_HOME

if ! "$TOOLS/avdmanager" list avd -c 2>/dev/null | grep -qx "$AVD"; then
    echo "==> installing $IMAGE and creating $AVD"
    # Not `yes | sdkmanager`: under pipefail, `yes` dying of SIGPIPE would fail the script.
    "$TOOLS/sdkmanager" "$IMAGE" "emulator" "platform-tools" < <(yes) > /dev/null
    echo no | "$TOOLS/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_5 --force
    config="$ANDROID_AVD_HOME/$AVD.avd/config.ini"
    # A small screen keeps software rendering affordable; RAM and cores reduce framework
    # timeouts under software emulation.
    sed -i -e 's/^hw.ramSize=.*/hw.ramSize=6144/' \
        -e 's/^hw.lcd.width=.*/hw.lcd.width=720/' \
        -e 's/^hw.lcd.height=.*/hw.lcd.height=1440/' \
        -e 's/^hw.lcd.density=.*/hw.lcd.density=320/' \
        -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' \
        -e 's/^hw.keyboard=.*/hw.keyboard=yes/' "$config"
    printf 'hw.cpu.ncore=6\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\n' >> "$config"
fi

accel="auto"
[ -w /dev/kvm ] || accel="off"

EMULATOR="${COMBRAY_EMULATOR_BIN:-$ANDROID_SDK_ROOT/emulator/emulator}"
export LD_LIBRARY_PATH="$ANDROID_SDK_ROOT/emulator/lib64:$ANDROID_SDK_ROOT/emulator/lib64/qt/lib${COMBRAY_EMULATOR_LIBS:+:$COMBRAY_EMULATOR_LIBS}"
LOG="$COMBRAY_WORK/logs/emulator.log"
mkdir -p "$(dirname "$LOG")"

echo "==> booting $AVD on port $PORT (accel=$accel); log: $LOG"
nohup "$EMULATOR" "@$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
    -gpu swiftshader_indirect -camera-back none -camera-front none -accel "$accel" \
    > "$LOG" 2>&1 &
emulator_pid=$!

started=$(date +%s)
until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "!! the emulator exited during boot; last log lines:" >&2
        tail -5 "$LOG" >&2
        exit 1
    fi
    if [ $(( $(date +%s) - started )) -gt "$BOOT_TIMEOUT_SECONDS" ]; then
        echo "!! $SERIAL did not boot within ${BOOT_TIMEOUT_SECONDS}s; stopping it" >&2
        # A wedged emulator would keep the AVD lock and port from the next attempt.
        kill "$emulator_pid" 2>/dev/null || true
        exit 1
    fi
    sleep 15
done
echo "booted $SERIAL in $(( $(date +%s) - started ))s"

# Fewer moving parts for slow emulators: no animations, no install verification.
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$SERIAL" shell settings put global "$setting" 0
done
"$ADB" -s "$SERIAL" shell settings put global verifier_verify_adb_installs 0
"$ADB" -s "$SERIAL" shell settings put global package_verifier_enable 0
# System UI often misses ANR deadlines under software emulation; its dialogs would cover the app.
"$ADB" -s "$SERIAL" shell settings put global hide_error_dialogs 1
