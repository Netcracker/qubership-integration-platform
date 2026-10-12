#!/usr/bin/env bash
#
# Point a module's BOM entry at its newly released version, and print
# bom/pom.xml so a caller can commit it.
#
# A BOM entry is the module's project version: its <revision> followed by the
# literal ${changelist}, which the BOM resolves to -SNAPSHOT in the working tree
# and to nothing in a BOM release. checkstyle has an empty changelist, so its
# entry is the bare <revision>. This script replaces the X.Y.Z and keeps the
# rest, so a second run with the same version changes nothing.
#
# Usage: scripts/sync-bom-entry.sh <module> <version>
#
#   scripts/sync-bom-entry.sh engine 1.5.2

set -euo pipefail

MODULE="${1:?module directory required}"
VERSION="${2:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::version must be X.Y.Z (got '$VERSION')" >&2
    exit 1
}

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/pom.sh"

[ -f "$MODULE/pom.xml" ] || {
    echo "::error::No pom.xml at $MODULE/" >&2
    exit 1
}
artifact="$(pom_artifact "$MODULE/pom.xml")"

bom_set_entry "$artifact" "$VERSION" || {
    echo "::error::bom/pom.xml has no entry for $artifact ($MODULE)" >&2
    exit 1
}

# shellcheck disable=SC2016
case "$(bom_entry "$artifact")" in
    "$VERSION" | "$VERSION"'${changelist}') ;;
    *)
        echo "::error::Failed to write $artifact $VERSION into bom/pom.xml" >&2
        exit 1
        ;;
esac

echo bom/pom.xml
