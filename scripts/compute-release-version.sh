#!/usr/bin/env bash
#
# Compute the release version, next dev version, tag and recovery flag for a
# module release, and append them to $GITHUB_OUTPUT. Shared by the maven and npm
# reusable release workflows.
#
# Usage: ECOSYSTEM=maven MODULE=engine RELEASE_TYPE=patch VERSION_OVERRIDE= \
#            scripts/compute-release-version.sh
#
# Env: ECOSYSTEM (maven|npm), MODULE, RELEASE_TYPE (patch|minor|major),
#      VERSION_OVERRIDE (explicit X.Y.Z, or empty to derive from the file).
#
# Model (bump-before): the file holds the LAST RELEASED version (maven pom
# <revision>, npm package.json); a release bumps it per release-type and
# publishes that. Deciding the version at release time (not pre-writing the next
# one into the file) keeps forked release lines from colliding on a shared
# pending version. maven also emits next-dev (= the released version) for the
# workflow to write back into <revision>; npm's release already updated the file.

set -euo pipefail

: "${ECOSYSTEM:?ECOSYSTEM env var required}" "${MODULE:?MODULE env var required}" "${GITHUB_OUTPUT:?GITHUB_OUTPUT env var required}"
RELEASE_TYPE="${RELEASE_TYPE:-patch}"
VERSION_OVERRIDE="${VERSION_OVERRIDE:-}"

is_semver() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

bump() {  # $1=X.Y.Z $2=patch|minor|major -> next on stdout (10# avoids octal)
    local ma mi pa
    IFS=. read -r ma mi pa <<<"$1"
    case "$2" in
        patch) echo "$ma.$mi.$((10#$pa + 1))" ;;
        minor) echo "$ma.$((10#$mi + 1)).0" ;;
        major) echo "$((10#$ma + 1)).0.0" ;;
        *) echo "::error::release-type must be patch|minor|major (got '$2')" >&2; exit 1 ;;
    esac
}

# Current version: maven from the pom <revision>, npm from package.json.
case "$ECOSYSTEM" in
    maven)
        POM="$MODULE/pom.xml"
        [ -f "$POM" ] || { echo "::error::No pom.xml at $MODULE/"; exit 1; }
        current=$(grep -oP '(?<=<revision>)[^<]+' "$POM" | head -1 || true)
        ;;
    npm)
        PKG="$MODULE/package.json"
        [ -f "$PKG" ] || { echo "::error::No package.json at $MODULE/"; exit 1; }
        current=$(node -p "require('./$PKG').version")
        ;;
    *) echo "::error::ECOSYSTEM must be maven|npm (got '$ECOSYSTEM')"; exit 1 ;;
esac

# Release version: an explicit override wins; otherwise bump the current version
# per release-type (both ecosystems hold the last released version in the file).
if [ -n "$VERSION_OVERRIDE" ]; then
    release="$VERSION_OVERRIDE"
else
    is_semver "$current" || { echo "::error::Current version must be X.Y.Z (got '$current')"; exit 1; }
    release=$(bump "$current" "$RELEASE_TYPE")
fi
is_semver "$release" || { echo "::error::Release version must be X.Y.Z (got '$release')"; exit 1; }

# next-dev applies to maven only: the just-released version, written back into
# <revision> after release (npm's release command already updated package.json).
next=""
if [ "$ECOSYSTEM" = maven ]; then
    next="$release"
fi

# Tag already there => a prior run published+tagged but its bump never landed:
# recover (re-apply the bump only) instead of wedging the module.
tag="$MODULE-v$release"
recover=false
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    recover=true
    echo "::warning::Tag $tag already exists — recovery mode: skipping build/publish, re-applying the version bump only."
fi

{
    echo "release-version=$release"
    echo "next-dev=$next"
    echo "release-tag=$tag"
    echo "recover=$recover"
} >> "$GITHUB_OUTPUT"
echo "Releasing $MODULE $release (current $current, type $RELEASE_TYPE, recover $recover)${next:+; <revision> set to $next}"
