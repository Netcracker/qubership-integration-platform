#!/usr/bin/env bash
#
# Commit the given paths and push to $BRANCH, rebasing onto the remote with
# retries so parallel releases (release-all runs modules concurrently) don't
# lose the push to a non-fast-forward. No-op if there is nothing to commit.
#
# Usage: BRANCH=main scripts/commit-and-push.sh "<commit message>" <path>...
#
# Commit author is the org bot, matching
# qubership-workflow-hub/actions/maven-release. The actual push uses whatever
# token is already on the remote (the workflow's GITHUB_TOKEN).

set -euo pipefail

msg="${1:?commit message required}"
shift
branch="${BRANCH:?BRANCH env var required}"

git config --global user.name "qubership-actions[bot]"
git config --global user.email "qubership-actions[bot]@users.noreply.github.com"

git add -- "$@"
if git diff --cached --quiet; then
    echo "Nothing to commit."
    exit 0
fi
git commit -m "$msg"

for attempt in $(seq 1 5); do
    git fetch --quiet origin "$branch"
    if git rebase FETCH_HEAD && git push origin "HEAD:$branch"; then
        exit 0
    fi
    git rebase --abort 2>/dev/null || true
    echo "push attempt $attempt failed; retrying in 5s..." >&2
    sleep 5
done

echo "::error::Could not push to $branch after 5 attempts" >&2
exit 1
