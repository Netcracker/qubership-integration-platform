#!/usr/bin/env bash
# Fetches QIP YAML schemas into a staging directory for Maven overlay into target/classes/qip-schemas.
#
# The published npm package @netcracker/qip-schemas only ships dereferenced element/*.yaml under assets/;
# this service needs the full qip-model tree with $ref (same as UI/catalog source of truth). Use a Git
# archive of https://github.com/Netcracker/qubership-integration-schemas or a local checkout path.
#
# Environment:
#   QIP_SCHEMAS_STAGING_DIR  (required) absolute path ending in .../qip-schemas (directory is replaced)
#   QIP_SCHEMAS_DOWNLOAD_URL  tarball URL (.tar.gz), e.g. GitHub archive of qubership-integration-schemas
#   QIP_SCHEMAS_LOCAL_DIR     optional; if set, copy this directory (must be .../qip-model contents)
#                             instead of downloading

set -euo pipefail

STAGING="${QIP_SCHEMAS_STAGING_DIR:?QIP_SCHEMAS_STAGING_DIR is required}"
LOCAL_DIR="${QIP_SCHEMAS_LOCAL_DIR:-}"
URL="${QIP_SCHEMAS_DOWNLOAD_URL:-}"

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

if [[ -n "$LOCAL_DIR" ]]; then
  if [[ ! -d "$LOCAL_DIR" ]]; then
    echo "fetch-qip-schemas: QIP_SCHEMAS_LOCAL_DIR is not a directory: $LOCAL_DIR" >&2
    exit 1
  fi
  rm -rf "$STAGING"
  mkdir -p "$(dirname "$STAGING")"
  cp -R "$LOCAL_DIR" "$STAGING"
  generate_element_index
  echo "fetch-qip-schemas: copied local qip-model from $LOCAL_DIR -> $STAGING"
  exit 0
fi

if [[ -z "$URL" ]]; then
  echo "fetch-qip-schemas: set QIP_SCHEMAS_DOWNLOAD_URL or QIP_SCHEMAS_LOCAL_DIR" >&2
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
SRC="$EXTRACT/$ROOT/src/main/resources/qip-model"
if [[ ! -d "$SRC" ]]; then
  echo "fetch-qip-schemas: expected directory not found after extract: $SRC (archive root: $ROOT)" >&2
  exit 1
fi

rm -rf "$STAGING"
mkdir -p "$(dirname "$STAGING")"
cp -R "$SRC" "$STAGING"
generate_element_index
echo "fetch-qip-schemas: downloaded $URL -> $STAGING"
