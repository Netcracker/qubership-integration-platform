#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
build_args=(-f "$repo_root/ai-service/pom.xml" package -Dgpg.skip=true)

for package in "$repo_root/.private/rocky-skills" "$repo_root/.private/rocky-knowledge"; do
    if [[ -e "$package" || -L "$package" ]]; then
        build_args+=(-Pwith-rocky-skills)
        break
    fi
done

exec mvn "${build_args[@]}" "$@"
