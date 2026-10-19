#!/usr/bin/env bash
# Download, verify, extract and patch the pinned Servo release into servo-checkout/.
#
#   scripts/m0/fetch-servo.sh            # all patches in native/servo/patches (harness)
#   scripts/m0/fetch-servo.sh upstream   # build fix only (baseline servoshell build)
#
# The tree is recreated from the verified tarball on every run, so its state always matches
# the requested patch set. Patches live in native/servo/patches/.
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

MODE="${1:-harness}"
case "$MODE" in
    harness) PATCHES=("$COMBRAY_ROOT"/native/servo/patches/*.patch) ;;
    upstream) PATCHES=("$COMBRAY_ROOT"/native/servo/patches/0001-*.patch) ;;
    *) echo "usage: $0 [harness|upstream]" >&2; exit 2 ;;
esac

TARBALL_NAME="servo-v$COMBRAY_SERVO_VERSION-src-vendored.tar.gz"
TARBALL_URL="https://github.com/servo/servo/releases/download/v$COMBRAY_SERVO_VERSION/$TARBALL_NAME"
DOWNLOADS="$COMBRAY_WORK/downloads/v$COMBRAY_SERVO_VERSION"
TARBALL="$DOWNLOADS/$TARBALL_NAME"

mkdir -p "$DOWNLOADS"
if [ ! -f "$TARBALL" ]; then
    echo "==> downloading $TARBALL_URL"
    curl -fL --retry 3 -o "$TARBALL.partial" "$TARBALL_URL"
    mv "$TARBALL.partial" "$TARBALL"
fi

echo "==> verifying $TARBALL_NAME"
echo "$COMBRAY_SERVO_TARBALL_SHA256  $TARBALL" | sha256sum -c -

# Keep the build cache (target/) across re-extractions; it is large and still valid.
if [ -d "$COMBRAY_SERVO_SRC/target" ]; then
    mv "$COMBRAY_SERVO_SRC/target" "$COMBRAY_WORK/target.keep"
fi
rm -rf "$COMBRAY_SERVO_SRC"
mkdir -p "$COMBRAY_SERVO_SRC"
echo "==> extracting into $COMBRAY_SERVO_SRC"
tar -xzf "$TARBALL" -C "$COMBRAY_SERVO_SRC"
if [ -d "$COMBRAY_WORK/target.keep" ]; then
    mv "$COMBRAY_WORK/target.keep" "$COMBRAY_SERVO_SRC/target"
fi

revision="$(cat "$COMBRAY_SERVO_SRC/GIT_REVISION")"
if [ "$revision" != "$COMBRAY_SERVO_GIT_REVISION" ]; then
    echo "!! tarball revision $revision, expected $COMBRAY_SERVO_GIT_REVISION" >&2
    exit 1
fi

# Patch 0001 replaces vendor/ with crates.io downloads (see its message); drop 1.5 GB.
rm -rf "$COMBRAY_SERVO_SRC/vendor"

cd "$COMBRAY_SERVO_SRC"
for patch in "${PATCHES[@]}"; do
    echo "==> applying $(basename "$patch")"
    git apply "$patch"
done

echo "==> preparing mach's Python environment"
./mach --help > /dev/null

echo "Servo v$COMBRAY_SERVO_VERSION ($revision) ready at $COMBRAY_SERVO_SRC ($MODE patches)"
