#!/usr/bin/env bash
#
# Compute the docker image tags for a module release and append them to
# $GITHUB_OUTPUT as a comma-separated `list`. Shared by the maven and
# testing-service release workflows.
#
# Usage: GITHUB_OUTPUT=out REF_NAME=release/0.5 VERSION=0.5.7 \
#          scripts/compute-image-tags.sh
#
# Env: GITHUB_OUTPUT (the file the tags are appended to), REF_NAME (the branch
#      the release runs on), VERSION (the released version; unused on the
#      default branch).
#
# We own every tag here (a custom `tags:` bypasses metadata-action's auto
# tagging). The default branch publishes only the moving :latest; release
# branches publish the immutable released version plus a moving branch tag
# (e.g. :0.5.7, :release-0.5), never :latest. Strip '/' (invalid in a docker
# tag) from the branch ref.

set -euo pipefail

: "${REF_NAME:?REF_NAME env var required}" "${GITHUB_OUTPUT:?GITHUB_OUTPUT env var required}"

if [ "$REF_NAME" = "main" ]; then
    tags="latest"
else
    : "${VERSION:?VERSION env var required}"
    tags="$VERSION,$(printf '%s' "$REF_NAME" | tr '/' '-')"
fi

echo "list=$tags" >> "$GITHUB_OUTPUT"
echo "Image tags for $REF_NAME: $tags"
