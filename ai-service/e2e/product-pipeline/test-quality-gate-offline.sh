#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${DIR}/../testing-paths.sh"
exec bash "${QIP_SKILLS_TESTING_ROOT}/product-pipeline/test-quality-gate-offline.sh" "$@"
