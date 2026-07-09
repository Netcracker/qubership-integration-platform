#!/usr/bin/env bash
#
# Commit the given paths and push to $BRANCH, rebasing onto the remote with
# retries so parallel releases (release-all runs modules concurrently) don't
# lose the push to a non-fast-forward. No-op if there is nothing to commit.
#
# Usage: BRANCH=main [TAG=engine-v1.2.3] scripts/commit-and-push.sh "<msg>" <path>...
#
# When TAG is set, the release tag is created on the bump commit and pushed
# BEFORE the branch push. Two reasons: the tag then marks the released version
# (not the pre-bump HEAD), and it lands even when the protected-branch bump push
# is rejected (tags are not branch-protected) — this is the sentinel the release
# recovery keys on. After the branch push lands, a tag created this run is moved
# onto the landed commit so it stays reachable from $BRANCH; an existing tag
# (recovery re-run) is left untouched.
#
# Commit author is the org bot, matching
# qubership-workflow-hub/actions/maven-release. The actual push uses whatever
# token is already on the remote (the workflow's App token or GITHUB_TOKEN).

set -euo pipefail

msg="${1:?commit message required}"
shift
branch="${BRANCH:?BRANCH env var required}"
tag="${TAG:-}"

git config --global user.name "qubership-actions[bot]"
git config --global user.email "qubership-actions[bot]@users.noreply.github.com"

git add -- "$@"
if git diff --cached --quiet; then
    echo "Nothing to commit."
else
    git commit -m "$msg"
fi

# Tag the bump commit and push the tag ahead of the branch (see header). The tag
# push survives a rejected branch push, so recovery can detect that the release
# already happened. Idempotent: a prior run may already hold the tag.
tag_created=false
if [ -n "$tag" ]; then
    if git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1; then
        echo "Tag $tag already exists — leaving it in place."
    else
        git tag "$tag"
        # Retry the tag push: it is the recovery sentinel after a non-idempotent
        # Central deploy, so a transient failure must not abort before it lands
        # (a lost sentinel re-deploys next run and wedges the module).
        for attempt in $(seq 1 5); do
            if git push origin "$tag"; then
                tag_created=true
                break
            fi
            echo "tag push attempt $attempt failed; retrying in 5s..." >&2
            sleep 5
        done
        $tag_created || { echo "::error::Could not push tag $tag after 5 attempts" >&2; exit 1; }
    fi
fi

pushed=false
for attempt in $(seq 1 5); do
    # fetch is inside the condition so a transient fetch failure retries instead
    # of aborting the script under `set -e`.
    if git fetch --quiet origin "$branch" && git rebase FETCH_HEAD && git push origin "HEAD:$branch"; then
        pushed=true
        break
    fi
    git rebase --abort 2> /dev/null || true
    echo "push attempt $attempt failed; retrying in 5s..." >&2
    sleep 5
done

if ! $pushed; then
    echo "::error::Could not push to $branch after 5 attempts" >&2
    exit 1
fi

# A sibling release pushing first makes the loop rebase our commit onto it,
# moving it off the SHA the tag was pushed at. Re-point a tag we created this run
# onto the landed commit so it stays reachable from $branch. Best-effort: the tag
# already carries the correct revision, so a failed re-point is a warning, not a
# release failure. A pre-existing tag (recovery) is never force-moved.
if [ -n "$tag" ] && $tag_created; then
    landed="$(git rev-parse HEAD)"
    if [ "$landed" != "$(git rev-parse "refs/tags/$tag")" ]; then
        if git tag -f "$tag" "$landed" && git push --force origin "$tag"; then
            echo "Re-pointed $tag onto the landed commit $landed."
        else
            echo "::warning::Could not re-point $tag onto $landed; it keeps the correct revision but stays off $branch." >&2
        fi
    fi
fi
