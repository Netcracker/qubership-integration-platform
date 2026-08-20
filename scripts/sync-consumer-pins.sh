#!/usr/bin/env bash
#
# Point every consumer POM at a newly released in-repo artifact, and print the
# files it changed (one per line) so a caller can commit exactly those.
#
# In-repo libraries (qip-integration-build-pipeline, qip-checkstyle) are pinned
# by property rather than by the reactor version, so a release has to move the
# pins as part of its own bump commit — a later job in the same wave checks out
# that tree and builds against the release.
#
# Usage: scripts/sync-consumer-pins.sh <property> <version> <pom>...
#
#   scripts/sync-consumer-pins.sh qip-integration-build-pipeline.version 1.3.0 runtime-catalog/pom.xml

set -euo pipefail

PROPERTY="${1:?property name required}"
VERSION="${2:?version required}"
shift 2
[ "$#" -gt 0 ] || {
    echo "::error::at least one consumer POM required" >&2
    exit 1
}

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

for pom in "$@"; do
    [ -f "$pom" ] || {
        echo "::error::No POM at $pom"
        exit 1
    }
    sed -i -E "s#<${PROPERTY}>[^<]+</${PROPERTY}>#<${PROPERTY}>${VERSION}</${PROPERTY}>#" "$pom"
    grep -q "<${PROPERTY}>${VERSION}</${PROPERTY}>" "$pom" || {
        echo "::error::Failed to write <${PROPERTY}> in $pom"
        exit 1
    }
    echo "$pom"
done
