#!/usr/bin/env bash
# Asserts qip-dev Helm wiring for A2A shared PostgreSQL, feature flag, and topology.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHART="${ROOT}/qip-dev"
RENDERED="$(mktemp)"
trap 'rm -f "$RENDERED"' EXIT

# Default-off render must succeed with an empty public URL (localhost fallback at runtime).
helm template qip-dev "$CHART" >"$RENDERED"

# Enabling A2A without a public Agent Card URL must fail closed.
if helm template qip-dev "$CHART" --set global.qip.ai.a2aEnabled=true >/dev/null 2>&1; then
  echo "FAIL: expected helm template to fail when a2aEnabled=true and a2aPublicBaseUrl is empty" >&2
  exit 1
fi
echo "OK: enable without public URL fails"

# Enable path with a configured public URL must render the URL into the ConfigMap.
ENABLE_RENDERED="$(mktemp)"
trap 'rm -f "$RENDERED" "$ENABLE_RENDERED"' EXIT
helm template qip-dev "$CHART" \
  --set global.qip.ai.a2aEnabled=true \
  --set global.qip.ai.a2aPublicBaseUrl=https://ai.example.com \
  >"$ENABLE_RENDERED"
if ! grep -Fq 'QIP_AI_A2A_PUBLIC_BASE_URL: "https://ai.example.com"' "$ENABLE_RENDERED"; then
  echo "FAIL: missing configured public Agent Card URL in enable render" >&2
  exit 1
fi
if ! grep -Fq 'QIP_AI_A2A_ENABLED: "true"' "$ENABLE_RENDERED"; then
  echo "FAIL: missing A2A enabled flag in enable render" >&2
  exit 1
fi
echo "OK: enable with public URL renders Agent Card base URL"

assert_contains() {
  local needle="$1"
  local label="$2"
  if ! grep -Fq "$needle" "$RENDERED"; then
    echo "FAIL: missing ${label}: ${needle}" >&2
    exit 1
  fi
  echo "OK: ${label}"
}

assert_absent() {
  local needle="$1"
  local label="$2"
  if grep -Fq "$needle" "$RENDERED"; then
    echo "FAIL: unexpected ${label}: ${needle}" >&2
    exit 1
  fi
  echo "OK: absent ${label}"
}

assert_contains 'CREATE DATABASE ai_a2a;' 'fresh-volume init SQL for ai_a2a'
assert_contains 'jdbc:postgresql://qip-dev-postgres:5432/ai_a2a' 'ConfigMap JDBC URL'
assert_contains 'QUARKUS_DATASOURCE_JDBC_URL' 'datasource JDBC env'
assert_contains 'QUARKUS_DATASOURCE_USERNAME' 'datasource username env'
assert_contains 'QUARKUS_DATASOURCE_PASSWORD' 'datasource password env'
assert_contains 'name: qip-dev-postgres-auth' 'existing postgres Secret reference'
assert_contains 'QIP_AI_A2A_ENABLED' 'A2A feature flag env'
assert_contains 'a2a-db-preflight' 'database-exists preflight initContainer'
assert_contains 'SELECT 1 FROM pg_database WHERE datname = '\''ai_a2a'\''' 'preflight existence query'

# One replica for ai-service (first match under ai-service deployment).
AI_REPLICAS="$(awk '/name: qip-dev-ai-service$/,/^---$/ { if ($1=="replicas:") { print $2; exit } }' "$RENDERED")"
if [[ "$AI_REPLICAS" != "1" ]]; then
  echo "FAIL: expected ai-service replicas=1, got '${AI_REPLICAS}'" >&2
  exit 1
fi
echo "OK: ai-service replicas=1"

PG_COUNT="$(grep -c 'name: qip-dev-postgres$' "$RENDERED" || true)"
if [[ "$PG_COUNT" -lt 1 ]]; then
  echo "FAIL: shared postgres deployment missing" >&2
  exit 1
fi
echo "OK: shared postgres present"

# Negative: no recurring A2A database bootstrap Job in the release.
if awk '
  /kind:[[:space:]]*Job/ { in_job=1; buf=$0; next }
  in_job && /^---$/ { in_job=0; buf=""; next }
  in_job { buf=buf ORS $0 }
  in_job && /a2a|ai_a2a|bootstrap/ { print buf; found=1 }
  END { exit found ? 0 : 1 }
' "$RENDERED"; then
  echo "FAIL: found recurring A2A database bootstrap Job" >&2
  exit 1
fi
echo "OK: no recurring A2A database bootstrap Job"

echo "All A2A Helm wiring assertions passed."
