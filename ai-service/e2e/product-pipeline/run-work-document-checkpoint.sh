#!/usr/bin/env bash
# Opt-in work-document checkpoint. Does not start the public scenario runner.
# Live provider and catalog calls require WORK_CHECKPOINT_LIVE=1.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_ROOT="$(cd "${DIR}/../.." && pwd)"
FIXTURES="${DIR}/fixtures/work-checkpoints"

usage() {
  echo "Usage: bash ai-service/e2e/product-pipeline/run-work-document-checkpoint.sh --checkpoint <logical|binding|mapping|recovery> --case <case-id> --report <local-report-path>" >&2
  echo "Set WORK_CHECKPOINT_LIVE=1 to call the configured provider. This script does not change the model." >&2
}

CHECKPOINT=""
CASE_ID=""
REPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --checkpoint)
      CHECKPOINT="${2:-}"
      shift 2
      ;;
    --case)
      CASE_ID="${2:-}"
      shift 2
      ;;
    --report)
      REPORT="${2:-}"
      shift 2
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${CHECKPOINT}" || -z "${CASE_ID}" || -z "${REPORT}" ]]; then
  usage
  exit 2
fi

case "${CHECKPOINT}" in
  logical|binding|mapping|recovery) ;;
  *)
    echo "Unknown checkpoint: ${CHECKPOINT}" >&2
    exit 2
    ;;
esac

mkdir -p "$(dirname "${REPORT}")"

write_report() {
  local outcome="$1"
  local code="$2"
  local message="$3"
  python3 - "${REPORT}" "${CHECKPOINT}" "${CASE_ID}" "${outcome}" "${code}" "${message}" <<'PY'
import json
import pathlib
import sys

path, checkpoint, case_id, outcome, code, message = sys.argv[1:7]
body = {
    "checkpoint": checkpoint,
    "caseId": case_id,
    "outcome": outcome,
    "failureCode": code,
    "message": message,
    "materialized": False,
    "providerSwitched": False,
    "gateModel": "gpt-6-luna",
}
pathlib.Path(path).write_text(json.dumps(body, indent=2) + "\n")
PY
}

if [[ "${CHECKPOINT}" == "mapping" || "${CHECKPOINT}" == "recovery" ]]; then
  write_report "FAILED" "MISSING_CAPABILITY" \
    "The ${CHECKPOINT} capability is not implemented. The harness does not report success."
  echo "MISSING_CAPABILITY: ${CHECKPOINT}" >&2
  exit 1
fi

if [[ "${WORK_CHECKPOINT_LIVE:-}" != "1" ]]; then
  write_report "REFUSED" "LIVE_NOT_ENABLED" \
    "Set WORK_CHECKPOINT_LIVE=1 to call the configured provider. This script does not change the model."
  echo "LIVE_NOT_ENABLED" >&2
  exit 2
fi

cd "${SERVICE_ROOT}"
exec ./mvnw -q \
  -DincludeScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=org.qubership.integration.platform.ai.plan.workdocument.checkpoint.WorkCheckpointHarness \
  -Dexec.args="--checkpoint ${CHECKPOINT} --case ${CASE_ID} --report ${REPORT} --fixtures ${FIXTURES}"
