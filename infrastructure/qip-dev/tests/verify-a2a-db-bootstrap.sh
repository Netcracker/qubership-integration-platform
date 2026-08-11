#!/usr/bin/env bash
# Verifies fresh-volume init SQL and existing-volume CREATE-when-absent for ai_a2a.
# Uses disposable Postgres containers; does not mutate a shared cluster.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INIT_SQL="${ROOT}/init-db/init.sql"
CHART_INIT="${ROOT}/qip-dev/charts/postgres/templates/postgres-config.yaml"
FRESH_NAME="a2a-bootstrap-fresh-$$"
EXISTING_NAME="a2a-bootstrap-existing-$$"
IMAGE="${A2A_BOOTSTRAP_PG_IMAGE:-postgres:16-alpine}"

cleanup() {
  docker rm -f "$FRESH_NAME" "$EXISTING_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_ready() {
  local name="$1"
  for _ in $(seq 1 30); do
    if docker exec "$name" pg_isready -U postgres >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL: Postgres container $name did not become ready" >&2
  return 1
}

db_exists() {
  local name="$1"
  docker exec "$name" psql -U postgres -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = 'ai_a2a'" | tr -d '[:space:]'
}

# Runbook one-time existing-volume bootstrap: create only when absent.
create_ai_a2a_when_absent() {
  local name="$1"
  local exists
  exists="$(db_exists "$name")"
  if [[ "$exists" != "1" ]]; then
    docker exec "$name" psql -U postgres -d postgres -c "CREATE DATABASE ai_a2a;" >/dev/null
    docker exec "$name" psql -U postgres -d postgres -c \
      "GRANT ALL PRIVILEGES ON DATABASE ai_a2a TO postgres;" >/dev/null
  fi
}

if [[ ! -f "$INIT_SQL" ]]; then
  echo "FAIL: missing init SQL at $INIT_SQL" >&2
  exit 1
fi
if ! grep -Fq 'CREATE DATABASE ai_a2a;' "$INIT_SQL"; then
  echo "FAIL: init.sql missing CREATE DATABASE ai_a2a;" >&2
  exit 1
fi
if ! grep -Fq 'CREATE DATABASE ai_a2a;' "$CHART_INIT"; then
  echo "FAIL: postgres-config.yaml missing CREATE DATABASE ai_a2a;" >&2
  exit 1
fi
echo "OK: fresh-volume init SQL declares ai_a2a"

# ---------------------------------------------------------------------------
# Path 1: fresh volume — chart init SQL creates ai_a2a on first start.
# ---------------------------------------------------------------------------
docker run -d --name "$FRESH_NAME" \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_USER=postgres \
  "$IMAGE" >/dev/null
wait_ready "$FRESH_NAME"

docker exec -i "$FRESH_NAME" psql -U postgres -d postgres <"$INIT_SQL" >/dev/null
if [[ "$(db_exists "$FRESH_NAME")" != "1" ]]; then
  echo "FAIL: fresh-volume init SQL did not create ai_a2a" >&2
  exit 1
fi
echo "OK: fresh-volume path created ai_a2a"

# ---------------------------------------------------------------------------
# Path 2: existing volume without ai_a2a — runbook CREATE DATABASE when absent.
# Start Postgres with no init SQL so the logical database is missing.
# ---------------------------------------------------------------------------
docker run -d --name "$EXISTING_NAME" \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_USER=postgres \
  "$IMAGE" >/dev/null
wait_ready "$EXISTING_NAME"

if [[ "$(db_exists "$EXISTING_NAME")" == "1" ]]; then
  echo "FAIL: expected ai_a2a absent before existing-volume CREATE path" >&2
  exit 1
fi
echo "OK: existing-volume instance starts without ai_a2a"

# Preflight SELECT (runbook) → CREATE only when absent.
create_ai_a2a_when_absent "$EXISTING_NAME"
if [[ "$(db_exists "$EXISTING_NAME")" != "1" ]]; then
  echo "FAIL: existing-volume CREATE path did not create ai_a2a" >&2
  exit 1
fi
echo "OK: existing-volume CREATE path created ai_a2a (pre-deploy gate)"

# Idempotent: second runbook pass must skip CREATE and leave exactly one DB.
BEFORE="$(docker exec "$EXISTING_NAME" psql -U postgres -d postgres -tAc \
  "SELECT COUNT(*) FROM pg_database WHERE datname = 'ai_a2a'" | tr -d '[:space:]')"
create_ai_a2a_when_absent "$EXISTING_NAME"
AFTER="$(docker exec "$EXISTING_NAME" psql -U postgres -d postgres -tAc \
  "SELECT COUNT(*) FROM pg_database WHERE datname = 'ai_a2a'" | tr -d '[:space:]')"
if [[ "$BEFORE" != "1" || "$AFTER" != "1" ]]; then
  echo "FAIL: expected exactly one ai_a2a before and after idempotent create" >&2
  exit 1
fi
echo "OK: existing-volume bootstrap is idempotent when ai_a2a already exists"

echo "All A2A database bootstrap assertions passed."
