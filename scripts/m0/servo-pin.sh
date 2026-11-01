# shellcheck shell=bash
# Source identity only: fetching Servo and running host tests need no Android SDK.
COMBRAY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export COMBRAY_ROOT

# Update these with docs/ENGINE_SPIKE.md and docs/BUILDING.md after native validation.
export COMBRAY_SERVO_REPOSITORY="https://github.com/L-K-M/servo.git"
export COMBRAY_SERVO_GIT_REVISION="494d7f4b13b78d64815fa191bc61705bee500347"
export COMBRAY_SERVO_UPSTREAM_REVISION="aac43a3f31a259f04a574f5ec4e959c943ad7cc7"

export COMBRAY_WORK="$COMBRAY_ROOT/servo-checkout"
export COMBRAY_SERVO_SRC="$COMBRAY_WORK/servo"
