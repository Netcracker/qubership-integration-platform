#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build_args=(-f "$repo_root/ai-service/pom.xml" package -Dgpg.skip=true)
customer_packages="$repo_root/integration-platform-skills"
private_packages="$repo_root/.private"

if [[ -e "$customer_packages/rocky-skills" || -L "$customer_packages/rocky-skills" ||
    -e "$customer_packages/rocky-knowledge" || -L "$customer_packages/rocky-knowledge" ]]; then
    build_args+=(
        -Pwith-rocky-skills
        "-Dqip.ai.qipknowledge.pack-root=$customer_packages/rocky-skills"
        "-Dqip.knowledge.export=$customer_packages/rocky-knowledge"
    )
elif [[ -e "$private_packages/rocky-skills" || -L "$private_packages/rocky-skills" ||
    -e "$private_packages/rocky-knowledge" || -L "$private_packages/rocky-knowledge" ]]; then
    build_args+=(-Pwith-rocky-skills)
elif [[ -e "$customer_packages" || -L "$customer_packages" ]]; then
    printf 'No Rocky runtime packages found under %s or %s.\n' \
        "$customer_packages" "$private_packages" >&2
    exit 1
fi

exec mvn "${build_args[@]}" "$@"
