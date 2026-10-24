#!/usr/bin/env bash
# Prepare an exact downstream Servo commit in a dedicated Git checkout.
#
#   scripts/m0/fetch-servo.sh            # Combray's pinned downstream commit
#   scripts/m0/fetch-servo.sh upstream   # pinned upstream baseline, from our repository
set -euo pipefail

# shellcheck source=servo-pin.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/servo-pin.sh"

MODE="${1:-harness}"
case "$MODE" in
    harness) REVISION="$COMBRAY_SERVO_GIT_REVISION" ;;
    upstream) REVISION="$COMBRAY_SERVO_UPSTREAM_REVISION" ;;
    *) echo "usage: $0 [harness|upstream]" >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ] || [[ ! "$REVISION" =~ ^[0-9a-f]{40}$ ]]; then
    echo "!! expected one mode and a full pinned Git commit SHA" >&2
    exit 2
fi

# Never let an enclosing/inherited repository turn a checkout into edits to Combray.
local_environment="$(git rev-parse --local-env-vars)"
while IFS= read -r variable; do
    unset "$variable"
done <<< "$local_environment"
unset GIT_NAMESPACE
if [ ! -e "$COMBRAY_SERVO_SRC" ]; then
    mkdir -p "$COMBRAY_WORK"
    git init --quiet "$COMBRAY_SERVO_SRC"
fi
if [ ! -d "$COMBRAY_SERVO_SRC/.git" ]; then
    echo "!! $COMBRAY_SERVO_SRC is not a dedicated Git checkout; move it aside before retrying" >&2
    exit 1
fi
changes="$(git -C "$COMBRAY_SERVO_SRC" status --porcelain --untracked-files=normal)"
if [ -n "$changes" ]; then
    echo "!! Servo has local changes; commit or move them aside before building the pinned source" >&2
    exit 1
fi

# A local detached commit needs a branch before moving HEAD away from it.
STAMP="$COMBRAY_SERVO_SRC/.git/combray-prepared"
prepared_revision=""
if [ -f "$STAMP" ]; then
    IFS= read -r prepared_revision < "$STAMP" || true
fi
current="$(git -C "$COMBRAY_SERVO_SRC" rev-parse --verify HEAD 2> /dev/null || true)"
case "$current" in
    ""|"$COMBRAY_SERVO_GIT_REVISION"|"$COMBRAY_SERVO_UPSTREAM_REVISION"|"$prepared_revision") ;;
    *)
        branches="$(git -C "$COMBRAY_SERVO_SRC" for-each-ref --contains "$current" --format='%(refname)' refs/heads/)"
        if [ -z "$branches" ]; then
            echo "!! Save Servo's detached commit $current on a branch before switching pins" >&2
            exit 1
        fi
        ;;
esac

# Fetch only a missing commit. A prepared checkout also works offline.
if ! git -C "$COMBRAY_SERVO_SRC" cat-file -e "$REVISION^{commit}" 2> /dev/null; then
    git -C "$COMBRAY_SERVO_SRC" fetch --no-tags --depth=1 "$COMBRAY_SERVO_REPOSITORY" "$REVISION"
fi
if [ "$current" != "$REVISION" ]; then
    git -C "$COMBRAY_SERVO_SRC" checkout --detach "$REVISION"
fi
if [ "$(git -C "$COMBRAY_SERVO_SRC" rev-parse HEAD)" != "$REVISION" ]; then
    echo "!! Servo checkout does not match the pinned commit $REVISION" >&2
    exit 1
fi

# Keep the stamp outside the source tree; only successful Python preparation is reusable.
expected="$(python3 -I - "$REVISION" "$COMBRAY_ROOT/scripts/m0/servo-pin.sh" "$0" <<'PY'
import hashlib
from pathlib import Path
import sys

print(sys.argv[1])
for path in sys.argv[2:]:
    print(hashlib.sha256(Path(path).read_bytes()).hexdigest())
PY
)"
if [ "$(cat "$STAMP" 2> /dev/null)" != "$expected" ] || [ ! -x "$COMBRAY_SERVO_SRC/.venv/bin/python" ]; then
    echo "==> preparing mach's Python environment"
    (cd "$COMBRAY_SERVO_SRC" && ./mach --help > /dev/null)
    printf '%s\n' "$expected" > "$STAMP.partial"
    mv "$STAMP.partial" "$STAMP"
fi

echo "Servo $REVISION ready at $COMBRAY_SERVO_SRC ($MODE)"
