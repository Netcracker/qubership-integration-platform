#!/usr/bin/env bash
#
# Publish the parent POMs at a platform version, signed, to GitHub Packages, and
# skip every parent already published there.
#
# Between drops the parents carry a literal <version>X.Y.Z-SNAPSHOT</version>: a
# parent cannot take its version from ${revision}, because Maven 3 then resolves
# it from the repository instead of the relativePath. So this script rewrites the
# version to X.Y.Z in the working tree, deploys, and restores parents/ on exit,
# whatever the outcome. set-platform-version.sh writes the version the branch
# keeps afterwards.
#
# GitHub Packages refuses to overwrite a release, so each parent is looked up
# first, and a rerun after a partial failure deploys only what is still missing.
# qip-parents is an aggregator that no POM inherits from, and is never deployed.
#
# Env: GH_TOKEN (reads GitHub Packages); MAVEN_GITHUB_USERNAME,
#      MAVEN_GITHUB_PASSWORD, and MAVEN_GPG_PASSPHRASE, which
#      .github/maven-settings.xml reads for the deploy.
#
# Usage: scripts/publish-parents.sh 1.6.0

set -euo pipefail

VERSION="${1:?version (X.Y.Z) required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "::error::version must be X.Y.Z (got '$VERSION')"
    exit 1
}
: "${GH_TOKEN:?GH_TOKEN env var required}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
. "$ROOT/scripts/modules.sh"
. "$ROOT/scripts/pom.sh"

REGISTRY="https://maven.pkg.github.com/Netcracker/qubership-integration-platform"
GROUP_PATH="org/qubership/integration/platform"

missing=()
for module in base spring quarkus; do
    artifact="qip-parent-$module"
    if curl --silent --fail --output /dev/null --user "x-access-token:$GH_TOKEN" \
        "$REGISTRY/$GROUP_PATH/$artifact/$VERSION/$artifact-$VERSION.pom"; then
        echo "$artifact $VERSION is already published."
    else
        missing+=("$module")
    fi
done

if [ "${#missing[@]}" -eq 0 ]; then
    echo "Every parent is published at $VERSION."
    exit 0
fi

trap 'git checkout --quiet -- parents' EXIT
for pom in "${QIP_PARENT_POMS[@]}"; do
    pom_set_own_version "$pom" "$VERSION"
done
pom_set_parent_version parents/quarkus/pom.xml "$VERSION"

modules="$(
    IFS=,
    echo "${missing[*]}"
)"
echo "Deploying the parents at $VERSION: $modules"
mvn --batch-mode -s .github/maven-settings.xml -f parents/pom.xml deploy -pl "$modules"
