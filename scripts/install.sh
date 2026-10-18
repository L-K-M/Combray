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
#   ./scripts/install.sh --help       show this help
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
for arg in "$@"; do
  case "$arg" in
    --release) VARIANT="release" ;;
    --debug) VARIANT="debug" ;;
    --no-launch) LAUNCH=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

if [ ! -f app/build.gradle.kts ] || [ ! -x gradlew ]; then
  echo "!! no Android project yet (app/build.gradle.kts and gradlew are missing)." >&2
  exit 1
fi

# Same SDK fallback as scripts/build.sh: AGP and adb both need it.
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ]; then
  for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk"; do
    if [ -d "$sdk/platforms" ]; then
      export ANDROID_HOME="$sdk"
      break
    fi
  done
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
  # Resolve adb from PATH, then from the Android SDK location.
  ADB="$(command -v adb || true)"
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$ADB" ] && break
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then ADB="$sdk/platform-tools/adb"; fi
  done

  if [ -n "$ADB" ]; then
    echo "==> Launching $APP_ID"
    "$ADB" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  else
    echo "-- adb not found; skipping launch"
  fi
fi
