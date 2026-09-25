#!/usr/bin/env bash
# Opt-in work-document checkpoint. Does not start the public scenario runner.
# Live provider and catalog calls require WORK_CHECKPOINT_LIVE=1.
# The filling checkpoint uses the replayable fake model when that variable is unset.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_ROOT="$(cd "${DIR}/../.." && pwd)"
FIXTURES="${DIR}/fixtures/work-checkpoints"

usage() {
  echo "Usage: bash ai-service/e2e/product-pipeline/run-work-document-checkpoint.sh --checkpoint <logical|binding|mapping|recovery|filling> --case <case-id> --report <report-path>"
  echo "Filling also accepts --run-id <id>, --resume, --input-file <absolute-json>, and --max-model-calls <n>."
  echo "--help prints this usage and does not call a provider or a catalog."
  echo "Set WORK_CHECKPOINT_LIVE=1 to call the configured provider. This script does not change the model."
}

CHECKPOINT=""
CASE_ID=""
REPORT=""
RUN_ID=""
INPUT_FILE=""
MAX_MODEL_CALLS=""
RESUME=0
HELP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help)
      HELP=1
      shift
      ;;
    --resume)
      RESUME=1
      shift
      ;;
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
    --run-id)
      RUN_ID="${2:-}"
      shift 2
      ;;
    --input-file)
      INPUT_FILE="${2:-}"
      shift 2
      ;;
    --max-model-calls)
      MAX_MODEL_CALLS="${2:-}"
      shift 2
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "${HELP}" == "1" ]]; then
  usage
  exit 0
fi

if [[ -z "${CHECKPOINT}" || -z "${CASE_ID}" || -z "${REPORT}" ]]; then
  usage >&2
  exit 2
fi

case "${CHECKPOINT}" in
  logical|binding|mapping|recovery|filling) ;;
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

if [[ "${CHECKPOINT}" == "filling" ]]; then
  if [[ -z "${RUN_ID}" ]]; then
    write_report "FAILED" "INVALID_ARGUMENT" "Run id is required for the filling checkpoint."
    exit 2
  fi
  if [[ -n "${INPUT_FILE}" && "${RESUME}" != "1" ]]; then
    write_report "FAILED" "INVALID_ARGUMENT" "An input file requires --resume."
    exit 2
  fi
fi

if [[ "${CHECKPOINT}" != "filling" && "${WORK_CHECKPOINT_LIVE:-}" != "1" ]]; then
  write_report "REFUSED" "LIVE_NOT_ENABLED" \
    "Set WORK_CHECKPOINT_LIVE=1 to call the configured provider. This script does not change the model."
  echo "LIVE_NOT_ENABLED" >&2
  exit 2
fi

EXTRA=""
if [[ -n "${RUN_ID}" ]]; then
  EXTRA="${EXTRA} --run-id ${RUN_ID}"
fi
if [[ "${RESUME}" == "1" ]]; then
  EXTRA="${EXTRA} --resume"
fi
if [[ -n "${INPUT_FILE}" ]]; then
  EXTRA="${EXTRA} --input-file ${INPUT_FILE}"
fi
if [[ -n "${MAX_MODEL_CALLS}" ]]; then
  EXTRA="${EXTRA} --max-model-calls ${MAX_MODEL_CALLS}"
fi

cd "${SERVICE_ROOT}"
exec ./mvnw -q \
  -DincludeScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=org.qubership.integration.platform.ai.plan.workdocument.checkpoint.WorkCheckpointHarness \
  -Dexec.args="--checkpoint ${CHECKPOINT} --case ${CASE_ID} --report ${REPORT} --fixtures ${FIXTURES}${EXTRA}"
