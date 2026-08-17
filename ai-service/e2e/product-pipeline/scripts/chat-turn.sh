#!/usr/bin/env bash
# POST one chat turn and capture the SSE stream.
# Usage: chat-turn.sh <base_url> <conversation_id|-> <message> <out_sse> [scenario_hint] [decision_json]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

BASE_URL="${1:?base url}"
CONV_ID="${2:?conversation id or -}"
MESSAGE="${3:?message}"
OUT_SSE="${4:?output sse file}"
SCENARIO_HINT="${5:-}"
DECISION_JSON="${6:-}"

e2e_require_cmds curl python3

payload_file="$(mktemp)"
trap 'rm -f "$payload_file"' EXIT

python3 - "$CONV_ID" "$MESSAGE" "$SCENARIO_HINT" "$DECISION_JSON" >"$payload_file" <<'PY'
import json
import sys

conv_id, message, hint, decision_raw = sys.argv[1:5]
body = {"message": message}
if conv_id and conv_id != "-":
    body["conversationId"] = conv_id
if hint:
    body["scenarioHint"] = hint
if decision_raw:
    body["decision"] = json.loads(decision_raw)
print(json.dumps(body))
PY

e2e_info "chat POST ${BASE_URL}/api/v1/chat"

curl -N -sS --max-time "${E2E_TURN_TIMEOUT_SEC}" \
  -X POST "${BASE_URL}/api/v1/chat" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d @"$payload_file" \
  | tee "$OUT_SSE" >/dev/null

if [[ ! -s "$OUT_SSE" ]]; then
  e2e_fail "empty SSE response from chat endpoint"
fi

# Require a terminal "event: done". Do not fall back to a prior conversationId —
# that masks truncated / error SSE streams and lets the durable poll burn minutes.
NEW_CONV_ID="$(e2e_extract_done_conversation_id "$OUT_SSE")"
if [[ -z "$NEW_CONV_ID" ]]; then
  e2e_fail "SSE ended without event: done (refusing prior conversationId fallback)"
fi

printf '%s' "$NEW_CONV_ID"
