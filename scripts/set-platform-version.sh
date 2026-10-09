#!/usr/bin/env bash
#
# Write the platform version into every POM that carries it, and print the files
# it changed (one per line) so a caller can commit exactly those.
#
# The platform version lives in three places that must agree:
#   * the root aggregator pom <revision>, the source of truth
#   * the own <version> of every POM in QIP_PARENT_POMS, as X.Y.Z-SNAPSHOT
#   * <parent><version> in every POM in QIP_PARENT_CHILDREN, as X.Y.Z-SNAPSHOT
#
# The parents keep a literal <version> on purpose. Maven 3 compares a child's
# literal parent version with the parent's raw ${revision}${changelist}, finds no
# match, and resolves the parent from the repository instead of the relativePath.
# That is why the bump is this coordinated rewrite rather than one shared
# property.
#
# Every rewrite is verified — a sed that matches nothing is silent otherwise,
# and the caller commits and releases on whatever this script leaves behind.
#
# Usage: scripts/set-platform-version.sh 1.3.0

set -euo pipefail

VERSION="${1:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::platform version must be X.Y.Z (got '$VERSION')" >&2
    exit 1
}

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

PARENT_VERSION="${VERSION}-SNAPSHOT"

sed -i -E "s#<revision>[0-9]+\.[0-9]+\.[0-9]+</revision>#<revision>${VERSION}</revision>#" pom.xml
[ "$(pom_prop pom.xml revision)" = "$VERSION" ] || {
    echo "::error::Failed to write <revision>${VERSION}</revision> in pom.xml"
    exit 1
}

for pom in "${QIP_PARENT_POMS[@]}"; do
    pom_set_own_version "$pom" "$PARENT_VERSION"
    [ "$(pom_own_version "$pom")" = "$PARENT_VERSION" ] || {
        echo "::error::Failed to write <version>${PARENT_VERSION}</version> in $pom"
        exit 1
    }
done

for child in "${QIP_PARENT_CHILDREN[@]}"; do
    pom_set_parent_version "$child" "$PARENT_VERSION"
    [ "$(pom_parent_version "$child")" = "$PARENT_VERSION" ] || {
        echo "::error::Failed to write the parent version ${PARENT_VERSION} in $child"
        exit 1
    }
done

printf '%s\n' pom.xml "${QIP_PARENT_POMS[@]}" "${QIP_PARENT_CHILDREN[@]}" | sort -u
