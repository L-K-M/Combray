#!/usr/bin/env bash
# Build the Combray APK from the command line and stage it into dist/.
#
# Release build by default; --debug builds the debug-variant APK instead.
# Both variants are signed with the checked-in debug keystore (deliberate,
# see docs/decisions/0001), so the staged APK is installable as-is. It is
# named after the committed versionName.
#
#   scripts/build.sh                  # incremental release build -> dist/
#   scripts/build.sh --debug          # debug build -> dist/
#   scripts/build.sh --install        # build, then install + launch on the connected device
#   scripts/build.sh --clean          # wipe Gradle build output first
#   scripts/build.sh --check          # print resolved config; build nothing
#
# Usage: scripts/build.sh [--debug] [--install] [--clean] [--check]
# Requirements: JDK 17+; the Android SDK (local.properties, ANDROID_HOME, or a
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

# Asset names say what they contain: <base>-<vX.Y.Z>-<os>-<arch>. The APK
# holds no native code, so it runs on every ABI.
if [ "$VARIANT" = "release" ]; then
  TASK="assembleRelease"
  APK="app/build/outputs/apk/release/app-release.apk"
  OUT="dist/combray-v${VERSION}-android-universal.apk"
else
  TASK="assembleDebug"
  APK="app/build/outputs/apk/debug/app-debug.apk"
  OUT="dist/combray-debug-v${VERSION}-android-universal.apk"
fi

if [ "$CHECK" -eq 1 ]; then
  echo "==> config"
  echo "-- variant:  $VARIANT"
  echo "-- version:  $VERSION"
  echo "-- task:     ./gradlew $TASK"
  echo "-- staged:   $OUT"
  echo "-- sdk:      $(if [ -f local.properties ]; then echo local.properties; else echo "${ANDROID_HOME:-not found}"; fi)"
  echo "-- install:  $([ "$INSTALL" -eq 1 ] && echo "yes (scripts/install.sh --$VARIANT)" || echo no)"
  exit 0
fi

if [ "$CLEAN" -eq 1 ]; then
  echo "==> ./gradlew clean"
  ./gradlew clean
fi

echo "==> ./gradlew $TASK"
./gradlew "$TASK"

[ -f "$APK" ] || { echo "!! expected APK not found: $APK" >&2; exit 1; }

mkdir -p dist
cp "$APK" "$OUT"
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
