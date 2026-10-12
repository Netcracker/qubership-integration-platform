#!/usr/bin/env bash
#
# Fail when POM versions that must agree have drifted apart. Several files carry
# the platform version, the in-repo library pins, and the BOM entries, and the
# release scripts write them, so a missed rewrite is silent until a release breaks.
#
# Invariants. Each one is written atomically by a single script in a single
# commit, so it holds at every point a human can observe:
#   1. the root pom <revision> is X.Y.Z: the platform version P
#   2. every POM in QIP_PARENT_POMS has the <version> P-SNAPSHOT
#   3. every POM in QIP_PARENT_CHILDREN pins its parent at P-SNAPSHOT
#   4. every library consumer's pin == integration-build-pipeline's <revision>,
#      so a reactor build resolves the library from source (the dependency
#      appends ${changelist}, so the pin names the release and the build the
#      development coordinate)
#   5. every qip-checkstyle pin == checkstyle's <revision>. No changelist there
#      on purpose: checkstyle is a plugin dependency, never in a module's
#      reactor, so its pin always names a published release.
#   6. every BOM entry is its module's project version: the module's <revision>
#      followed by ${changelist}, or the bare <revision> when the module's
#      <changelist> is empty (checkstyle). The BOM lists exactly the modules in
#      QIP_BOM_ENTRIES.
#   7. every property both parents/base and parents/spring declare has one value.
#      The Spring parent extends spring-boot-starter-parent, so it copies these
#      properties instead of inheriting them.
#
# Deliberately NOT checked: that the modules share one major.minor. That is a
# postcondition of a full release-all wave, not a property at rest — a partial
# minor or major wave (`modules: engine,ui`) leaves them apart on purpose, and
# release-all asserts it for itself once the wave is done.
#
# Usage: scripts/check-version-invariants.sh

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

failures=0

fail() {
    echo "::error::$1"
    failures=$((failures + 1))
}

platform="$(pom_prop pom.xml revision)"
[[ "$platform" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    fail "root pom <revision> is not X.Y.Z (got '$platform')"
    exit 1
}
expected_parent="${platform}-SNAPSHOT"

for pom in "${QIP_PARENT_POMS[@]}"; do
    own="$(pom_own_version "$pom")"
    [ "$own" = "$expected_parent" ] ||
        fail "$pom <version> is '$own', expected '$expected_parent' (root <revision> $platform)"
done

for child in "${QIP_PARENT_CHILDREN[@]}"; do
    pinned="$(pom_parent_version "$child")"
    [ "$pinned" = "$expected_parent" ] ||
        fail "$child pins parent '$pinned', expected '$expected_parent'"
done

library_version="$(pom_prop integration-build-pipeline/pom.xml revision)"
for consumer in "${QIP_LIBRARY_CONSUMERS[@]}"; do
    pin="$(pom_prop "$consumer" "$QIP_LIBRARY_PROPERTY")"
    [ "$pin" = "$library_version" ] ||
        fail "$consumer pins the library at '$pin', but integration-build-pipeline is on '$library_version' — a reactor build would then resolve the released jar instead of the working tree"
done

checkstyle_version="$(pom_prop checkstyle/pom.xml revision)"
for consumer in "${QIP_CHECKSTYLE_CONSUMERS[@]}"; do
    checkstyle_pin="$(pom_prop "$consumer" "$QIP_CHECKSTYLE_PROPERTY")"
    [ "$checkstyle_pin" = "$checkstyle_version" ] ||
        fail "$consumer pins qip-checkstyle at '$checkstyle_pin', but checkstyle is on '$checkstyle_version'"
done

known_artifacts=" "
for module in "${QIP_BOM_ENTRIES[@]}"; do
    artifact="$(pom_artifact "$module/pom.xml")"
    known_artifacts+="$artifact "
    revision="$(pom_prop "$module/pom.xml" revision)"
    expected="$revision"
    if [ -n "$(pom_prop "$module/pom.xml" changelist)" ]; then
        # The BOM resolves its own ${changelist}; the entry carries it unexpanded.
        # shellcheck disable=SC2016
        expected+='${changelist}'
    fi
    entry="$(bom_entry "$artifact")"
    if [ -z "$entry" ]; then
        fail "bom/pom.xml has no entry for $artifact ($module)"
    elif [ "$entry" != "$expected" ]; then
        fail "bom/pom.xml lists $artifact at '$entry', expected '$expected' ($module <revision> $revision)"
    fi
done
for artifact in $(bom_artifacts); do
    [[ "$known_artifacts" == *" $artifact "* ]] ||
        fail "bom/pom.xml lists $artifact, which is no module in QIP_BOM_ENTRIES"
done

while IFS='=' read -r name base_value spring_value; do
    [ "$base_value" = "$spring_value" ] ||
        fail "$name is '$base_value' in parents/base/pom.xml but '$spring_value' in parents/spring/pom.xml"
done < <(LC_ALL=C join -t '=' \
    <(pom_props parents/base/pom.xml | LC_ALL=C sort) \
    <(pom_props parents/spring/pom.xml | LC_ALL=C sort))

if [ "$failures" -gt 0 ]; then
    echo "$failures version invariant(s) broken."
    exit 1
fi

echo "Version invariants hold: platform $platform, library $library_version, checkstyle $checkstyle_version."
