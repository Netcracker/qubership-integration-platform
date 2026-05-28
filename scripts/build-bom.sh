#!/usr/bin/env bash
#
# Generate a Bill of Materials (BOM) for the QIP monorepo, dumping the latest
# released version of each module derived from git tags. Output is JSON on
# stdout.
#
# Tag scheme: <module>-v<X.Y.Z>. Modules with no released tag yet appear as
# null. This makes the BOM safe to commit and to diff across releases.
#
# Usage:
#   scripts/build-bom.sh                  # write JSON to stdout
#   scripts/build-bom.sh > release-manifest.json
#
# Requires: git, awk. Run from any directory inside the repo.

set -euo pipefail

# Resolve repo root so the script works regardless of where it's invoked from.
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Modules tracked by the BOM. Order is preserved in the output.
MODULES=(
    "engine"
    "micro-engine"
    "runtime-catalog"
    "sessions-management"
    "checkstyle"
    "schemas"
    "ui"
    "vscode-extension"
)

# Strip the "<module>-v" prefix from the latest matching tag.
latest_version() {
    local module="$1"
    local tag
    tag=$(git tag --list "${module}-v*" --sort=-version:refname 2>/dev/null | head -1)
    if [ -n "$tag" ]; then
        printf '%s' "${tag#"${module}-v"}"
    fi
}

# Emit a JSON object with the modules in declaration order. We render by hand
# (no jq dependency) but keep the structure boring enough that any consumer
# can parse it.
printf '{\n'
printf '  "generated_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  "modules": {\n'

count=${#MODULES[@]}
i=0
for module in "${MODULES[@]}"; do
    i=$((i + 1))
    version=$(latest_version "$module")
    if [ -z "$version" ]; then
        printf '    "%s": null' "$module"
    else
        printf '    "%s": "%s"' "$module" "$version"
    fi
    if [ "$i" -lt "$count" ]; then
        printf ','
    fi
    printf '\n'
done

printf '  }\n'
printf '}\n'
