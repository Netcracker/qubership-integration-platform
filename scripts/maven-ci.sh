#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$repo_root/mvnw" --batch-mode --no-transfer-progress \
    --settings "$repo_root/.github/maven-settings.xml" "$@"
