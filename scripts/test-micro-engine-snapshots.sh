#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot_bundle_directory="${SNAPSHOT_BUNDLE_DIRECTORY:-$repo_root/runtime-catalog/target/snapshotbundle}"
cd "$repo_root"

maven_command=("$repo_root/mvnw" --batch-mode -Dgpg.skip=true)

"${maven_command[@]}" "$@" -pl integration-build-pipeline -am clean install -DskipTests
"${maven_command[@]}" "$@" -pl runtime-catalog -PsnapshotTests \
    -Dsnapshot.bundle.directory="$snapshot_bundle_directory" clean test
"${maven_command[@]}" "$@" -pl micro-engine -PsnapshotTests \
    -Dsnapshot.bundle.directory="$snapshot_bundle_directory" clean test
