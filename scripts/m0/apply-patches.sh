#!/usr/bin/env bash
# Apply patches to a source tree that is not a Git repository of its own.
#
#   scripts/m0/apply-patches.sh TREE PATCH...
#
# servo-checkout/ sits inside the Combray clone. There, `git apply` resolves a git-format patch's
# paths from the clone's root, skips every file it then finds outside the current directory
# ("Skipped patch ...") and still exits 0. Stopping Git from looking above TREE makes it a plain
# patch tool again; checking that each patch now reverses cleanly proves it landed.
set -euo pipefail

TREE="$(cd "$1" && pwd)"
shift
cd "$TREE"
GIT_CEILING_DIRECTORIES="$(dirname "$TREE")"
export GIT_CEILING_DIRECTORIES

for patch in "$@"; do
    echo "==> applying $(basename "$patch")"
    git apply "$patch"
    if ! git apply --check --reverse "$patch" 2> /dev/null; then
        echo "!! $(basename "$patch") did not apply to $TREE" >&2
        exit 1
    fi
done
