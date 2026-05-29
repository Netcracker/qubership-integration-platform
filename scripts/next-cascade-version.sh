#!/usr/bin/env bash
#
# Compute the next patch version for a cascade release (schemas → ui →
# vscode-extension).
#
# The "current" version is the highest of:
#   (a) the latest <prefix>-v* git tag  — what was actually published, and
#   (b) the "version" field in the given package.json — the manually-maintained
#       baseline that the workspace ships with.
# Taking the max of both guarantees we never compute a version at or below
# something already on the registry (npm publish would reject it). This also
# fixes the first-ever cascade, when no <prefix>-v* tag exists yet but
# package.json is already well ahead of 0.0.1.
#
# Emits the patch-incremented X.Y.Z on stdout; all diagnostics go to stderr so
# the caller can capture the version with $(...).
#
# Usage: scripts/next-cascade-version.sh <tag-prefix> <package-json-path>
#   scripts/next-cascade-version.sh ui ui/package.json
#   scripts/next-cascade-version.sh vscode-extension vscode-extension/package.json
#
# Requires: git, node, awk, sort (coreutils). Run from anywhere inside the repo.

set -euo pipefail

PREFIX="${1:?usage: next-cascade-version.sh <tag-prefix> <package-json-path>}"
PKG_JSON="${2:?usage: next-cascade-version.sh <tag-prefix> <package-json-path>}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# (a) latest released tag for this module, prefix stripped.
tag_version=$(git tag --list "${PREFIX}-v*" --sort=-version:refname | head -1 | sed "s/^${PREFIX}-v//")

# (b) version declared in package.json (empty if the file is missing/unreadable).
pkg_version=$(node -p "require('${REPO_ROOT}/${PKG_JSON}').version" 2>/dev/null || true)

# Highest valid X.Y.Z across both sources becomes the baseline. `sort -V` is a
# version sort (so 0.0.56 > 0.0.9, unlike a lexical sort); `|| true` keeps the
# pipeline from tripping `set -o pipefail` when grep matches nothing.
current=$(printf '%s\n%s\n' "$tag_version" "$pkg_version" |
    grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' |
    sort -V |
    tail -1 || true)

if [ -z "$current" ]; then
    echo "::error::Cannot determine current ${PREFIX} version (git tag: '${tag_version:-<none>}', ${PKG_JSON}: '${pkg_version:-<none>}')" >&2
    exit 1
fi

next=$(awk -F. -v OFS=. '{$NF=$NF+1; print}' <<<"$current")
echo "Next ${PREFIX} version: ${next} (baseline ${current}; git tag '${tag_version:-<none>}', ${PKG_JSON} '${pkg_version:-<none>}')" >&2
printf '%s\n' "$next"
