# shellcheck shell=bash
# Source identity only: fetching Servo and running host tests need no Android SDK.
COMBRAY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export COMBRAY_ROOT

# Update these with docs/ENGINE_SPIKE.md and docs/BUILDING.md after native validation.
export COMBRAY_SERVO_REPOSITORY="https://github.com/L-K-M/servo.git"
export COMBRAY_SERVO_GIT_REVISION="97be6cb1fb50b6fe82f677dd30c901102a3fa94a"
export COMBRAY_SERVO_UPSTREAM_REVISION="c78d2c206f80a1c8b67eefa97f773bba513205d3"

export COMBRAY_WORK="$COMBRAY_ROOT/servo-checkout"
export COMBRAY_SERVO_SRC="$COMBRAY_WORK/servo"
