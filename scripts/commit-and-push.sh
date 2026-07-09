#!/usr/bin/env bash
#
# Commit the given paths and push to $BRANCH, rebasing with retries so parallel
# releases don't lose the push to a non-fast-forward. No-op if nothing changed.
#
# Usage: BRANCH=main [TAG=engine-v1.2.3] scripts/commit-and-push.sh "<msg>" <path>...
#
# TAG (optional): tag the bump commit and push the tag before the branch. The tag
# then marks the released version and survives a rejected protected-branch push
# (tags aren't protected) — the sentinel recovery keys on. After the branch lands,
# a tag we created is re-pointed onto the landed commit; an existing tag (recovery
# re-run) is left as is.
#
# Commits as the org bot; pushes with the remote's existing token.

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

# Push the tag before the branch, with retries: it is the recovery sentinel after
# a non-idempotent Central deploy, so losing it would re-deploy and wedge the
# module next run. Idempotent — a prior run may already hold it.
tag_created=false
if [ -n "$tag" ]; then
    if git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1; then
        echo "Tag $tag already exists — leaving it in place."
    else
        git tag "$tag"
        for attempt in $(seq 1 5); do
            if git push origin "$tag"; then
                tag_created=true
                break
            fi
            echo "tag push attempt $attempt failed; retrying in 5s..." >&2
            sleep 5
        done
        $tag_created || {
            echo "::error::Could not push tag $tag after 5 attempts" >&2
            exit 1
        }
    fi
fi

pushed=false
for attempt in $(seq 1 5); do
    # fetch in the condition: a transient failure retries, not aborts under set -e.
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

# The rebase above may move our commit off the SHA the tag was pushed at. Re-point
# a tag we created onto the landed commit (best-effort — it already has the right
# revision). A pre-existing tag (recovery) is never moved.
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
