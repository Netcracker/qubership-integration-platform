#!/usr/bin/env bash
# POST one chat turn and capture the SSE stream.
# Usage: chat-turn.sh <base_url> <conversation_id|-> <message> <out_sse> [scenario_hint] [decision_json]
#
# Optional env (production chat fields the UI also sends):
#   E2E_CHAT_ATTACHMENT     open-chain context, e.g. "## Current Chain: Name (ID: uuid)"
#   E2E_CHAT_DECISION_JSON  typed answer to a decision card (apply-chain-patch, approve, ...)
# Message may be empty when a decision JSON value is set.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

BASE_URL="${1:?base url}"
CONV_ID="${2:?conversation id or -}"
MESSAGE="${3-}"
OUT_SSE="${4:?output sse file}"
SCENARIO_HINT="${5:-}"
ATTACHMENT="${E2E_CHAT_ATTACHMENT:-}"
DECISION_JSON="${6:-${E2E_CHAT_DECISION_JSON:-}}"

e2e_require_cmds curl python3

if [[ -z "${MESSAGE}" && -z "${DECISION_JSON}" ]]; then
  e2e_fail "chat turn needs a message or E2E_CHAT_DECISION_JSON"
fi

payload_file="$(mktemp)"
trap 'rm -f "$payload_file"' EXIT

python3 - "$CONV_ID" "$MESSAGE" "$SCENARIO_HINT" "$ATTACHMENT" "$DECISION_JSON" >"$payload_file" <<'PY'
import json
import sys

conv_id, message, hint, attachment, decision_json = sys.argv[1:6]
body = {"message": message}
if conv_id and conv_id != "-":
    body["conversationId"] = conv_id
if hint:
    body["scenarioHint"] = hint
if attachment:
    body["attachment"] = attachment
if decision_json:
    body["decision"] = json.loads(decision_json)
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
