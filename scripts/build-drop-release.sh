#!/usr/bin/env bash
#
# Cut the single GitHub Release for a platform drop (one per release-all wave).
# Module releases only publish + tag; this is the only place a Release is made.
# Tag = bare vX.Y.Z from the root pom <revision>; body = module BOM +
# GitHub-native notes since the previous drop.
#
# Env: REPO, GH_TOKEN (required); BRANCH (push target for the bump);
#      RELEASE_TYPE=patch|minor|major; TARGET_SHA (default HEAD);
#      PRERELEASE=true|false; DRY_RUN=true|1 (print only).
#
#   REPO=Netcracker/qubership-integration-platform DRY_RUN=1 \
#   GH_TOKEN=$(gh auth token) bash scripts/build-drop-release.sh

set -euo pipefail

: "${REPO:?REPO env var required}" "${GH_TOKEN:?GH_TOKEN env var required}"
RELEASE_TYPE="${RELEASE_TYPE:-patch}"
PRERELEASE="${PRERELEASE:-false}"
DRY_RUN="${DRY_RUN:-false}"
TARGET_SHA="${TARGET_SHA:-$(git rev-parse HEAD)}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

bump() { # X.Y.Z patch|minor|major -> next (10# avoids octal)
    local ma mi pa
    IFS=. read -r ma mi pa <<< "$1"
    case "$2" in
        patch) echo "$ma.$mi.$((10#$pa + 1))" ;;
        minor) echo "$ma.$((10#$mi + 1)).0" ;;
        major) echo "$((10#$ma + 1)).0.0" ;;
        *)
            echo "::error::release-type must be patch|minor|major (got '$2')" >&2
            exit 1
            ;;
    esac
}

version="$(grep -oP '(?<=<revision>)[^<]+' pom.xml | head -1)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::root pom <revision> is not X.Y.Z (got '$version')"
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

next="$(bump "$version" "$RELEASE_TYPE")"

if [ "$DRY_RUN" = "true" ] || [ "$DRY_RUN" = "1" ]; then
    echo "===== DRY RUN: drop $tag (next dev $next) ====="
    cat "$notes"
    exit 0
fi

# Bump + push the version BEFORE cutting the release: a blocked push fails cleanly
# and retries next wave instead of wedging an already-created release.
sed -i "s|<revision>${version}</revision>|<revision>${next}</revision>|" pom.xml
bash scripts/commit-and-push.sh \
    "chore: bump platform version to $next [skip ci]" \
    pom.xml

if gh release view "$tag" --repo "$REPO" > /dev/null 2>&1; then
    gh release edit "$tag" --repo "$REPO" --title "$tag" \
        --notes-file "$notes" --prerelease="$PRERELEASE"
    echo "Updated existing drop release $tag"
else
    gh release create "$tag" --repo "$REPO" --target "$TARGET_SHA" \
        --title "$tag" --notes-file "$notes" --prerelease="$PRERELEASE"
    echo "Created drop release $tag"
fi
