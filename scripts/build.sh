#!/usr/bin/env bash
# Build the Combray APK from the command line and stage it into dist/.
#
# Release build by default; --debug builds the debug-variant APK instead.
# The staged APK is named after the committed versionName and its verified native ABI.
# Release builds require the COMBRAY_KEYSTORE_* / COMBRAY_KEY_* environment variables
# documented in docs/BUILDING.md. An engine-less Kotlin CI APK is never staged.
#
#   scripts/build.sh                  # incremental release build -> dist/
#   scripts/build.sh --debug          # debug build -> dist/
#   scripts/build.sh --install        # build, then install + launch on the connected device
#   scripts/build.sh --clean          # wipe Gradle build output first
#   scripts/build.sh --check          # print resolved config; build nothing
#
# Usage: scripts/build.sh [--debug] [--install] [--clean] [--check]
# Requirements: Python 3.10+, the toolchain M0 pins (AGENTS.md); the Android SDK (local.properties, ANDROID_HOME, or a
#               standard SDK location, which is detected); adb for --install.
set -euo pipefail

# Absolute self-path first: usage() re-opens the script, which a relative $0
# would no longer find after the cd below.
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELF")/.."

usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

VARIANT="release"
CLEAN=0
CHECK=0
INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --debug) VARIANT="debug" ;;
        --install) INSTALL=1 ;;
        --clean) CLEAN=1 ;;
        --check) CHECK=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "!! unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

# The repo is set up before the app exists; say so instead of a Gradle error.
if [ ! -f app/build.gradle.kts ] || [ ! -x gradlew ]; then
    echo "!! no Android project yet (app/build.gradle.kts and gradlew are missing)." >&2
    echo "!! see AGENTS.md \"When the Gradle project lands\"." >&2
    exit 1
fi

# AGP needs the SDK location. Without local.properties or ANDROID_HOME, try
# the places Android Studio and the command-line tools install it.
if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk"; do
        if [ -d "$sdk/platforms" ]; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
fi

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*$/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$VERSION" ] || { echo "!! could not read versionName from app/build.gradle.kts" >&2; exit 1; }

if [ "$VARIANT" = "release" ]; then
    TASK="assembleRelease"
    APK="app/build/outputs/apk/release/app-release.apk"
    BASE="combray"
else
    TASK="assembleDebug"
    APK="app/build/outputs/apk/debug/app-debug.apk"
    BASE="combray-debug"
fi

if [ "$CHECK" -eq 1 ]; then
    echo "==> config"
    echo "-- variant:  $VARIANT"
    echo "-- version:  $VERSION"
    echo "-- task:     ./gradlew $TASK"
    echo "-- staged:   dist/${BASE}-v${VERSION}-android-<verified APK arch>.apk"
    echo "-- sdk:      $(if [ -f local.properties ]; then echo local.properties; else echo "${ANDROID_HOME:-not found}"; fi)"
    echo "-- install:  $([ "$INSTALL" -eq 1 ] && echo "yes (scripts/install.sh --$VARIANT)" || echo no)"
    exit 0
fi

if [ "$VARIANT" = "release" ]; then
    for name in COMBRAY_KEYSTORE_FILE COMBRAY_KEYSTORE_PASSWORD COMBRAY_KEY_ALIAS COMBRAY_KEY_PASSWORD; do
        if [ -z "${!name:-}" ]; then
            echo "!! release signing needs $name; see docs/BUILDING.md (Local release signing)." >&2
            exit 1
        fi
    done
fi

if [ "$CLEAN" -eq 1 ]; then
    echo "==> ./gradlew clean"
    ./gradlew clean
fi

echo "==> ./gradlew $TASK"
./gradlew "$TASK"

[ -f "$APK" ] || { echo "!! expected APK not found: $APK" >&2; exit 1; }

# Check the produced APK, not the staging directory: Gradle packaging can be stale.
ARCH="$(python3 -I scripts/verify-apk.py "$APK" --variant "$VARIANT" --version "$VERSION")"
OUT="dist/${BASE}-v${VERSION}-android-${ARCH}.apk"
mkdir -p dist
# Keep any previous complete artifact intact if copying or hashing fails.
PARTIAL_APK="$(mktemp "${OUT}.XXXXXX")"
trap 'rm -f "$PARTIAL_APK" "$PARTIAL_APK.sha256"' EXIT
cp "$APK" "$PARTIAL_APK"
python3 -I - "$PARTIAL_APK" "$OUT" <<'PY'
import hashlib
from pathlib import Path
import sys

artifact = Path(sys.argv[1])
destination = Path(sys.argv[2])
digest = hashlib.sha256()
with artifact.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
Path(str(artifact) + ".sha256").write_text(f"{digest.hexdigest()}  {destination.name}\n")
PY
mv "$PARTIAL_APK" "$OUT"
mv "$PARTIAL_APK.sha256" "$OUT.sha256"
echo "==> staged $OUT"

if [ "$INSTALL" -eq 1 ]; then
    # install.sh owns device install + launch; Gradle's up-to-date checks make
    # its install task cheap right after the build above.
    echo "==> scripts/install.sh --$VARIANT"
    "$(dirname "$SELF")/install.sh" "--$VARIANT"
elif command -v open >/dev/null 2>&1; then
    # Reveal in Finder when running on a Mac desktop; harmless elsewhere.
    open -R "$OUT" 2>/dev/null || true
fi
echo "==> Done."
