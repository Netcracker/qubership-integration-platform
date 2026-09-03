#!/usr/bin/env bash
#
# Fail when POM versions that must agree have drifted apart. Several files carry
# the platform version and the in-repo library pins, and three release scripts
# write them, so a missed rewrite is silent until a release breaks.
#
# Invariants — each one is written atomically by a single script in a single
# commit, so it holds at every point a human can observe:
#   1. parent/pom.xml <version> == "<root pom <revision>>-SNAPSHOT"
#   2. parent/pom.xml <revision> == root pom <revision>
#   3. every child's <parent><version> == parent/pom.xml <version>
#   4. runtime-catalog's library pin == integration-build-pipeline's <revision>,
#      so a reactor build resolves the library from source
#   5. every qip-checkstyle pin == checkstyle's <revision>
#
# Deliberately NOT checked: that the backend services share one <revision>. That
# is a postcondition of a full release-all wave, not a property at rest — a
# partial wave (`modules: engine,ui`) and a single-module release both leave them
# apart on purpose, and release-all asserts it for itself once the wave is done.
#
# Usage: scripts/check-version-invariants.sh

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"

failures=0

fail() {
    echo "::error::$1"
    failures=$((failures + 1))
}

prop() { # <file> <tag> -> first value on stdout
    grep -oP "(?<=<$2>)[^<]+" "$1" | head -1
}

parent_version() { # <file> -> the <version> inside the <parent> block
    sed -n '/<parent>/,/<\/parent>/p' "$1" | grep -oP '(?<=<version>)[^<]+' | head -1
}

platform="$(prop pom.xml revision)"
[[ "$platform" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    fail "root pom <revision> is not X.Y.Z (got '$platform')"
    exit 1
}
expected_parent="${platform}-SNAPSHOT"

# parent/pom.xml's own version is the only -SNAPSHOT one in the file; the first
# plain <version> belongs to spring-boot-starter-parent.
actual_parent="$(grep -oP '(?<=<version>)[0-9.]+-SNAPSHOT' parent/pom.xml | head -1)"
[ "$actual_parent" = "$expected_parent" ] ||
    fail "parent/pom.xml <version> is '$actual_parent', expected '$expected_parent' (root <revision> $platform)"

parent_revision="$(prop parent/pom.xml revision)"
[ "$parent_revision" = "$platform" ] ||
    fail "parent/pom.xml <revision> is '$parent_revision', expected '$platform'"

for child in "${QIP_PARENT_CHILDREN[@]}"; do
    pinned="$(parent_version "$child/pom.xml")"
    [ "$pinned" = "$expected_parent" ] ||
        fail "$child/pom.xml pins parent '$pinned', expected '$expected_parent'"
done

library_version="$(prop integration-build-pipeline/pom.xml revision)"
pin="$(prop runtime-catalog/pom.xml qip-integration-build-pipeline.version)"
[ "$pin" = "$library_version" ] ||
    fail "runtime-catalog pins the library at '$pin', but integration-build-pipeline is on '$library_version' — a reactor build would then resolve the released jar instead of the working tree"

checkstyle_version="$(prop checkstyle/pom.xml revision)"
for consumer in "${QIP_CHECKSTYLE_CONSUMERS[@]}"; do
    checkstyle_pin="$(prop "$consumer" qip-checkstyle-revision)"
    [ "$checkstyle_pin" = "$checkstyle_version" ] ||
        fail "$consumer pins qip-checkstyle at '$checkstyle_pin', but checkstyle is on '$checkstyle_version'"
done

if [ "$failures" -gt 0 ]; then
    echo "$failures version invariant(s) broken."
    exit 1
fi

echo "Version invariants hold: platform $platform, library $library_version, checkstyle $checkstyle_version."
