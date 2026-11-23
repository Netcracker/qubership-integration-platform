#!/usr/bin/env bash
set -euo pipefail

[[ $# == 3 ]] || {
    echo 'Usage: hosted_prepare_revision.sh TRUSTED_CHECKOUT CANDIDATE_CHECKOUT HOSTED_DIR' >&2
    exit 2
}
script_dir=$(dirname -- "${BASH_SOURCE[0]}")
revision_home=$(mktemp -d "$3/revision-home.XXXXXX")
exec env -i HOME="$revision_home" PATH="$PATH" LANG=C.UTF-8 \
    JAVA_HOME="${JAVA_HOME:-}" GITHUB_RUN_ID="${GITHUB_RUN_ID:-}" GITHUB_RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-}" \
    QIP_AGENT_BROKERS="${QIP_AGENT_BROKERS:-1}" QIP_PUBLIC_MAVEN_REPOSITORY="${RUNNER_TEMP:?}/qip-revision-maven-repository" \
    bash "$script_dir/hosted_prepare.sh" "$@"
