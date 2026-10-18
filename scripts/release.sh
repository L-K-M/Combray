#!/usr/bin/env bash
# Cuts a release: bumps versionName in app/build.gradle.kts (versionCode is
# auto-incremented — Android requires a new code per release), updates the
# README version marker, commits, tags "v<version>", and with --push pushes
# branch + tag — which triggers .github/workflows/release.yml to test, build
# the release APK, and publish the GitHub Release. Release signing must exist
# before the first tag (AGENTS.md).
#
#   scripts/release.sh 0.2.0          # bump, commit, tag v0.2.0
#   scripts/release.sh 0.2.0 --push   # …also push the commit + tag (CI then publishes)
#   scripts/release.sh                # tag the current committed version as-is
#
# Usage: scripts/release.sh [X.Y.Z] [--push]
# Shared engine: https://github.com/L-K-M/release-tool (this stub only sets config).
set -euo pipefail

export RELEASE_APP_NAME="Combray"
export RELEASE_KIND="gradle-android"
export RELEASE_CI_NOTE="CI (release.yml) will now test, build the release APK, and publish the GitHub Release for <tag>."
export RELEASE_INVOKED_AS="scripts/release.sh"

BIN="${LKM_RELEASE_BIN:-lkm-release}"
command -v "$BIN" >/dev/null 2>&1 || {
    echo "error: lkm-release not found — clone https://github.com/L-K-M/release-tool and run ./install.sh" >&2
    exit 1
}
exec "$BIN" "$@"
