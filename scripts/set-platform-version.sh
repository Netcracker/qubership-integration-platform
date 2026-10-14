#!/usr/bin/env bash
#
# Write the platform version into every POM that carries it, and print the files
# it changed (one per line) so a caller can commit exactly those.
#
# The platform version lives in three places that must agree:
#   * the root aggregator pom <revision>  — the source of truth
#   * parent/pom.xml <version> + <revision>
#   * <parent><version> in every child that pins qip-monorepo-parent
#
# parent/pom.xml keeps a literal <version> on purpose: ${revision} is redefined
# by every child for its own module version, so a parent reference written as
# ${revision} resolves to the wrong number (and to nothing at all before the
# child POM is read). That is why the bump is this coordinated rewrite rather
# than one shared property.
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

PARENT_VERSION="${VERSION}-SNAPSHOT"

verify() { # <file> <expected substring>
    grep -qF "$2" "$1" || {
        echo "::error::Failed to write '$2' in $1"
        exit 1
    }
}

sed -i -E "s#<revision>[0-9]+\.[0-9]+\.[0-9]+</revision>#<revision>${VERSION}</revision>#" pom.xml
verify pom.xml "<revision>${VERSION}</revision>"

# The <version> range is anchored: parent/pom.xml opens with spring-boot-starter-parent's
# own <version>, and only the second one is ours.
sed -i -E \
    -e "0,/<version>[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT<\/version>/s##<version>${PARENT_VERSION}</version>#" \
    -e "s#<revision>[0-9]+\.[0-9]+\.[0-9]+</revision>#<revision>${VERSION}</revision>#" \
    parent/pom.xml
verify parent/pom.xml "<version>${PARENT_VERSION}</version>"
verify parent/pom.xml "<revision>${VERSION}</revision>"

# Children pin the parent literally; rewrite only inside the <parent> block.
for child in "${QIP_PARENT_CHILDREN[@]}"; do
    sed -i -E "/<parent>/,/<\/parent>/s#<version>[^<]+</version>#<version>${PARENT_VERSION}</version>#" \
        "$child/pom.xml"
    verify "$child/pom.xml" "<version>${PARENT_VERSION}</version>"
done

echo pom.xml
echo parent/pom.xml
for child in "${QIP_PARENT_CHILDREN[@]}"; do
    echo "$child/pom.xml"
done
