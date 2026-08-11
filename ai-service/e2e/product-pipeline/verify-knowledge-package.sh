#!/usr/bin/env bash
# Verify a certified Knowledge Package through the sidecar contract.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${DIR}/../../.." && pwd)"
COMPOSE_FILE="${ROOT}/infrastructure/docker-compose.yml"
KNOWLEDGE_PACKAGE="${1:-}"
[[ -n "${KNOWLEDGE_PACKAGE}" && -d "${KNOWLEDGE_PACKAGE}" ]] || {
  echo "Usage: verify-knowledge-package.sh <knowledge-export-directory>" >&2
  exit 2
}

export QIP_KNOWLEDGE_HOST_PATH
QIP_KNOWLEDGE_HOST_PATH="$(cd "${KNOWLEDGE_PACKAGE}" && pwd)"
docker compose -f "${COMPOSE_FILE}" --profile ai up -d --build --force-recreate \
  qip-knowledge-sidecar

response=""
for _ in $(seq 1 60); do
  if response="$(
    docker compose -f "${COMPOSE_FILE}" exec -T qip-knowledge-sidecar \
      python -c \
      'import json, urllib.request; print(json.dumps(json.load(urllib.request.urlopen("http://127.0.0.1:8095/v1/package"))))' \
      2>/dev/null
  )"; then
    break
  fi
  sleep 1
done
[[ -n "${response}" ]] || {
  echo "FAIL: knowledge sidecar did not expose /v1/package" >&2
  exit 1
}

jq -e '
  .packageRef.certificationStatus == "CERTIFIED"
  and (.packageRef.packageChecksum | startswith("sha256:"))
  and (.packageRef | has("tier") | not)
' <<<"${response}" >/dev/null
jq '{
  packageKey: .packageRef.packageKey,
  packageChecksum: .packageRef.packageChecksum,
  certificationDigest: .packageRef.certificationDigest
}' <<<"${response}"
