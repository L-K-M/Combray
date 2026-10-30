#!/usr/bin/env bash
#
# Build Combray and install it on a connected Android device, then launch it.
#
# Handy when working from IntelliJ IDEA (which lacks Android Studio's Run
# button): connect the phone with USB debugging enabled (or pair over Wi-Fi
# with `adb connect <ip>:<port>`) and run this from the IDE terminal.
#
# Usage:
#   ./scripts/install.sh             build + install + launch the debug build
#   ./scripts/install.sh --release   build + install the release build
#   ./scripts/install.sh --no-launch  skip launching the app after install
#   ./scripts/install.sh --device-check  only check that adb can use a device
#   ./scripts/install.sh --help       show this help
#
# Before building, it asks adb for a usable device and, if there is none,
# names the likely cause (USB debugging off, the phone's prompt not accepted,
# two adb versions) instead of Gradle's bare "No connected devices!".
#
set -euo pipefail

# Absolute self-path first: usage() re-opens this script, which a relative $0
# would no longer find after the cd below (e.g. when invoked from scripts/).
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

# Run from the repository root regardless of where the script is invoked.
cd "$(dirname "$SELF")/.."

# Print the header comment block as the help text, minus the shebang. Stopping
# at the first non-# line means the help can never drift out of step with the
# header the way a hardcoded line range does.
usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

VARIANT="debug"
LAUNCH=1
CHECK_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --release) VARIANT="release" ;;
        --debug) VARIANT="debug" ;;
        --no-launch) LAUNCH=0 ;;
        --device-check) CHECK_ONLY=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

# Same SDK fallback as scripts/build.sh: AGP and adb both need it.
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk"; do
        if [ -d "$sdk/platforms" ]; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
fi

# The adb Gradle's install task uses: the SDK's own (local.properties, then the
# environment), with PATH as the last resort.
ADB=""
SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tail -n 1 || true)"
for sdk in "$SDK_DIR" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then
        ADB="$sdk/platform-tools/adb"
        break
    fi
done
[ -n "$ADB" ] || ADB="$(command -v adb || true)"

# Exits 1 with the likely cause unless adb lists a device it can use.
check_device() {
    if [ -z "$ADB" ]; then
        echo "-- adb not found; Gradle will look for the device itself"
        return 0
    fi

    local listing states
    if ! listing="$("$ADB" devices)"; then
        echo "!! '$ADB devices' failed; see its message above." >&2
        exit 1
    fi
    # "<serial><TAB><state>"; the header line has no tab.
    states="$(printf '%s\n' "$listing" | awk -F '\t' 'NF >= 2 { print $2 }')"
    if printf '%s\n' "$states" | grep -qx device; then
        return 0
    fi

    if printf '%s\n' "$states" | grep -qx unauthorized; then
        cat >&2 <<EOF
!! The phone has not allowed USB debugging from this computer.
   Unlock it and accept "Allow USB debugging?" (tick "Always allow").
   No prompt? Developer options > Revoke USB debugging authorizations,
   then run '$ADB kill-server' and plug the phone in again.
EOF
    elif printf '%s\n' "$states" | grep -qx offline; then
        cat >&2 <<EOF
!! adb sees the phone, but offline. Unplug it, run '$ADB kill-server',
   and plug it in again.
EOF
    elif printf '%s\n' "$states" | grep -q '^no permissions'; then
        cat >&2 <<EOF
!! adb has no permission to use the phone. On Linux, add a udev rule for
   it (https://developer.android.com/studio/run/device) and replug.
EOF
    elif [ -n "$states" ]; then
        # authorizing, connecting, recovery, sideload and the like.
        cat >&2 <<EOF
!! adb sees a device it cannot install to yet ($(printf '%s\n' "$states" | sort -u | paste -sd, -)).
   Wait for "authorizing" or "connecting" to finish, or restart the phone
   into Android from recovery or sideload, then try again.
EOF
    else
        cat >&2 <<EOF
!! adb sees no phone or emulator ('$ADB devices' lists none).
   - Turn on USB debugging: Settings > About phone, tap "Build number"
     7 times, then Developer options > USB debugging.
   - Unlock the phone. If it asks what the USB connection is for, choose
     File transfer.
   - Try another cable (some only charge) and a port on the computer itself.
   - Over Wi-Fi: '$ADB pair <ip>:<port>', then '$ADB connect <ip>:<port>'.
EOF
    fi

    # Two adb versions take turns restarting the one adb server, which can
    # hide the phone from either of them.
    local other
    other="$(command -v adb || true)"
    if [ -n "$other" ] && [ "$other" != "$ADB" ] \
        && [ "$("$other" version 2>/dev/null | sed -n 2p)" != "$("$ADB" version 2>/dev/null | sed -n 2p)" ]; then
        cat >&2 <<EOF
!! Another adb is first on PATH ($other), with a different version from
   $ADB. Run '$ADB kill-server' and use only one of them.
EOF
    fi
    exit 1
}

check_device
if [ "$CHECK_ONLY" -eq 1 ]; then
    # Without adb, check_device checked nothing; it said so already.
    [ -z "$ADB" ] || echo "==> adb sees a device"
    exit 0
fi

if [ ! -f app/build.gradle.kts ] || [ ! -x gradlew ]; then
    echo "!! no Android project yet (app/build.gradle.kts and gradlew are missing)." >&2
    exit 1
fi

# The debug build carries the ".debug" applicationIdSuffix so both variants
# can live on one device side by side.
case "$VARIANT" in
    debug)   TASK="installDebug";   APP_ID="ch.lkmc.combray.debug" ;;
    release) TASK="installRelease"; APP_ID="ch.lkmc.combray" ;;
esac

echo "==> ./gradlew $TASK  (builds and installs to the connected device)"
./gradlew "$TASK"

if [ "$LAUNCH" -eq 1 ]; then
    if [ -n "$ADB" ]; then
        echo "==> Launching $APP_ID"
        "$ADB" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    else
        echo "-- adb not found; skipping launch"
    fi
fi
