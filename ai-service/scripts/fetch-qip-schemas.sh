#!/usr/bin/env bash
# Fetches QIP YAML schemas into a staging directory for Maven overlay into target/classes/qip-schemas.
#
# The published npm package @netcracker/qip-schemas only ships dereferenced element/*.yaml under assets/;
# this service needs the full qip-model tree with $ref (same as UI/catalog source of truth).
#
# Source of truth is the monorepo module schemas/ (schemas/src/main/resources/qip-model).
# Prefer a local checkout; optionally download a Git archive of qubership-integration-platform.
#
# Environment:
#   QIP_SCHEMAS_STAGING_DIR  (required) absolute path ending in .../qip-schemas (directory is replaced)
#   QIP_SCHEMAS_LOCAL_DIR     optional; if set, copy this directory (must be .../qip-model contents)
#                             instead of downloading. Maven default: ../schemas/src/main/resources/qip-model
#   QIP_SCHEMAS_DOWNLOAD_URL  tarball URL (.tar.gz), e.g. GitHub archive of qubership-integration-platform

set -euo pipefail

STAGING="${QIP_SCHEMAS_STAGING_DIR:?QIP_SCHEMAS_STAGING_DIR is required}"
LOCAL_DIR="${QIP_SCHEMAS_LOCAL_DIR:-}"
URL="${QIP_SCHEMAS_DOWNLOAD_URL:-}"

# Sibling monorepo path when the script lives under ai-service/scripts/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_LOCAL_DIR="${SCRIPT_DIR}/../../schemas/src/main/resources/qip-model"

generate_element_index() {
  local element_dir="$STAGING/element"
  local out="$STAGING/element-index.json"
  if [[ ! -d "$element_dir" ]]; then
    echo "fetch-qip-schemas: element schema directory not found: $element_dir" >&2
    exit 1
  fi

  printf '{\n  "elements": [\n' > "$out"
  local first=1
  local file type title lower deprecated escaped
  for file in "$element_dir"/*.schema.yaml; do
    type="$(basename "$file" .schema.yaml)"
    if [[ "$type" == "element" ]]; then
      continue
    fi
    title="$(awk -F':' '/^title:[[:space:]]*/ { sub(/^[[:space:]]+/, "", $2); sub(/^"/, "", $2); sub(/"[[:space:]]*$/, "", $2); print $2; exit }' "$file")"
    if [[ -z "$title" ]]; then
      title="$type"
    fi
    lower="$(printf '%s' "$title" | tr '[:upper:]' '[:lower:]')"
    deprecated=false
    if [[ "$lower" == *"(deprecated)"* ]]; then
      deprecated=true
    fi
    escaped="${title//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    if [[ "$first" -eq 0 ]]; then
      printf ',\n' >> "$out"
    fi
    first=0
    printf '    { "type": "%s", "title": "%s", "deprecated": %s }' "$type" "$escaped" "$deprecated" >> "$out"
  done
  printf '\n  ]\n}\n' >> "$out"
  echo "fetch-qip-schemas: generated $out"
}

stage_from_dir() {
  local src="$1"
  local label="$2"
  if [[ ! -d "$src" ]]; then
    echo "fetch-qip-schemas: qip-model directory not found: $src" >&2
    exit 1
  fi
  rm -rf "$STAGING"
  mkdir -p "$(dirname "$STAGING")"
  cp -R "$src" "$STAGING"
  generate_element_index
  echo "fetch-qip-schemas: $label $src -> $STAGING"
}

resolve_qip_model_in_extract() {
  local extract_root="$1"
  local archive_root="$2"
  local candidate

  # Monorepo layout (qubership-integration-platform/schemas/...)
  for candidate in \
    "$extract_root/$archive_root/schemas/src/main/resources/qip-model" \
    "$extract_root/schemas/src/main/resources/qip-model"; do
    if [[ -d "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done

  # Legacy standalone qubership-integration-schemas layout
  for candidate in \
    "$extract_root/$archive_root/src/main/resources/qip-model" \
    "$extract_root/src/main/resources/qip-model"; do
    if [[ -d "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done

  return 1
}

if [[ -n "$LOCAL_DIR" ]]; then
  stage_from_dir "$LOCAL_DIR" "copied local qip-model from"
  exit 0
fi

# Prefer the monorepo schemas module next to ai-service when env is unset.
if [[ -d "$DEFAULT_LOCAL_DIR" ]]; then
  stage_from_dir "$DEFAULT_LOCAL_DIR" "copied monorepo schemas qip-model from"
  exit 0
fi

if [[ -z "$URL" ]]; then
  echo "fetch-qip-schemas: set QIP_SCHEMAS_LOCAL_DIR or QIP_SCHEMAS_DOWNLOAD_URL (or checkout schemas/ next to ai-service)" >&2
  exit 1
fi

TMP_ROOT="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

ARCHIVE="$TMP_ROOT/schemas.tar.gz"
curl -fsSL "$URL" -o "$ARCHIVE"

EXTRACT="$TMP_ROOT/extract"
mkdir -p "$EXTRACT"
tar -xzf "$ARCHIVE" -C "$EXTRACT"

ROOT="$(ls -1 "$EXTRACT" | head -1)"
SRC="$(resolve_qip_model_in_extract "$EXTRACT" "$ROOT" || true)"
if [[ -z "$SRC" || ! -d "$SRC" ]]; then
  echo "fetch-qip-schemas: expected qip-model not found after extract (archive root: $ROOT)" >&2
  echo "fetch-qip-schemas: looked for schemas/src/main/resources/qip-model (monorepo) or src/main/resources/qip-model (legacy)" >&2
  exit 1
fi

stage_from_dir "$SRC" "downloaded $URL ->"
