#!/usr/bin/env bash
#
# Generate a Bill of Materials (BOM) for the QIP monorepo, dumping the latest
# released version of each module derived from git tags. Output is JSON on
# stdout.
#
# Tag scheme: <module>-v<X.Y.Z>, except testing-service, which is a Go module and
# tags as <module>/v<X.Y.Z> (see tag_prefix). Modules with no released tag yet
# appear as null. This makes the BOM safe to commit and to diff across releases.
#
# schemas is one row for two artifacts: the npm package and the Maven artifact
# carry the same version, released together (schemas-release.yaml). The row reads
# the npm tag line.
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

# Modules tracked by the BOM, in output order (scripts/modules.sh).
# checkstyle is build tooling, not a shipped module, so it stays out.
. "$REPO_ROOT/scripts/modules.sh"

# Tag prefix a module releases under. The go command resolves a module living in
# a repository subdirectory only from <subdir>/vX.Y.Z tags, so testing-service
# cannot use the repository's usual <module>-vX.Y.Z form.
tag_prefix() {
    case "$1" in
        testing-service) printf '%s/v' "$1" ;;
        *) printf '%s-v' "$1" ;;
    esac
}

# Strip the module's tag prefix from the latest matching tag.
latest_version() {
    local module="$1"
    local prefix tag
    prefix=$(tag_prefix "$module")
    tag=$(git tag --list "${prefix}*" --sort=-version:refname 2> /dev/null | head -1)
    if [ -n "$tag" ]; then
        printf '%s' "${tag#"$prefix"}"
    fi
}

# Emit a JSON object with the modules in declaration order. We render by hand
# (no jq dependency) but keep the structure boring enough that any consumer
# can parse it.
printf '{\n'
printf '  "generated_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  "modules": {\n'

count=${#QIP_BOM_MODULES[@]}
i=0
for module in "${QIP_BOM_MODULES[@]}"; do
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
