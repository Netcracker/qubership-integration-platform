#!/usr/bin/env bash
#
# Cut the single GitHub Release for a platform drop (one per release-all wave).
# Module releases only publish + tag; this is the only place a Release is made.
# Tag = bare vX.Y.Z; body = module BOM + GitHub-native notes since the previous
# drop.
#
# VERSION is the version the wave released — the same number every backend
# service was published under. The caller (release-all) computes it once; this
# script writes it into the platform POMs (root <revision>, parent, and the
# children that pin the parent), commits, tags that commit, and cuts the release.
#
# The tag rides the bump commit, matching every module tag: `git checkout v1.3.0`
# then reproduces the tree whose root <revision> is 1.3.0.
#
# Env: VERSION, REPO, GH_TOKEN (required); BRANCH (push target for the bump);
#      TARGET_SHA (default HEAD); PRERELEASE=true|false; DRY_RUN=true|1.
#
#   VERSION=1.3.0 REPO=Netcracker/qubership-integration-platform DRY_RUN=1 \
#   GH_TOKEN=$(gh auth token) bash scripts/build-drop-release.sh

set -euo pipefail

: "${REPO:?REPO env var required}" "${GH_TOKEN:?GH_TOKEN env var required}" "${VERSION:?VERSION env var required}"
PRERELEASE="${PRERELEASE:-false}"
DRY_RUN="${DRY_RUN:-false}"
TARGET_SHA="${TARGET_SHA:-$(git rev-parse HEAD)}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

version="$VERSION"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::VERSION must be X.Y.Z (got '$version')"
    exit 1
}
tag="v$version"

# Previous drop = latest bare vX.Y.Z release on this prerelease track (module and
# legacy per-module releases excluded; empty => first drop). API failure aborts.
prev="$(gh api "repos/$REPO/releases" |
    jq -r --arg cur "$tag" --arg pre "$PRERELEASE" '
        [ .[]
          | select(.tag_name | test("^v[0-9]+[.][0-9]+[.][0-9]+$"))
          | select((.prerelease | tostring) == $pre)
          | .tag_name ]
        | map(select(. != $cur)) | .[0] // empty')"

echo "Drop: $tag  (previous: ${prev:-<none, first drop>}, target $TARGET_SHA)"

notes="$(mktemp)"
trap 'rm -f "$notes"' EXIT
{
    echo "## 🚀 Platform drop \`$tag\`"
    echo
    echo "### Module versions"
    echo
    echo "| Module | Version |"
    echo "| --- | --- |"
    bash scripts/build-bom.sh |
        jq -r '.modules | to_entries[] | "| \(.key) | \(.value // "—") |"'
    echo
} > "$notes"

# GitHub builds the categorised "What's Changed" via .github/release.yml; a
# failure is warned, not swallowed, and doesn't abort the drop.
if generated="$(gh api "repos/$REPO/releases/generate-notes" \
    -f tag_name="$tag" \
    -f target_commitish="$TARGET_SHA" \
    ${prev:+-f previous_tag_name="$prev"} \
    --jq '.body')"; then
    printf '%s\n' "$generated" >> "$notes"
else
    echo "::warning::generate-notes failed for $tag (prev=${prev:-none}); release will omit the changelog"
    echo "_Changelog generation failed; see the commit history for $tag._" >> "$notes"
fi

if [ "$DRY_RUN" = "true" ] || [ "$DRY_RUN" = "1" ]; then
    echo "===== DRY RUN: drop $tag ====="
    cat "$notes"
    exit 0
fi

# Write + push the version BEFORE cutting the release: a blocked push fails cleanly
# and retries next wave instead of wedging an already-created release. Command
# substitution (not a process substitution) so a failed write aborts here.
touched="$(bash scripts/set-platform-version.sh "$version")"
# TAG rides the bump commit and is pushed before the branch (see the script).
# shellcheck disable=SC2086
TAG="$tag" bash scripts/commit-and-push.sh \
    "chore: platform version $version released [skip ci]" \
    $touched

if gh release view "$tag" --repo "$REPO" > /dev/null 2>&1; then
    gh release edit "$tag" --repo "$REPO" --title "$tag" \
        --notes-file "$notes" --prerelease="$PRERELEASE"
    echo "Updated existing drop release $tag"
else
    # No --target: commit-and-push.sh already pushed the tag at the bump commit.
    gh release create "$tag" --repo "$REPO" \
        --title "$tag" --notes-file "$notes" --prerelease="$PRERELEASE"
    echo "Created drop release $tag"
fi
