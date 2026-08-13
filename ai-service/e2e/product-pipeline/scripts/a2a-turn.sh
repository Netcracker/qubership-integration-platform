#!/usr/bin/env bash
# Send one A2A turn and capture the response.
# Usage: a2a-turn.sh <base_url> <task_id|-> <message> <out_json> [structured_json]
#
# The A2A counterpart of chat-turn.sh. It prints the taskId, which create-chain@2 keys by
# conversationId, so both transports poll the same durable evidence endpoint afterwards.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

BASE_URL="${1:?base url}"
TASK_ID="${2:?task id or -}"
# Empty when the turn carries only structured data, which is how approval travels.
MESSAGE="${3-}"
OUT_JSON="${4:?output json file}"
STRUCTURED="${5:-}"

e2e_require_cmds curl python3

# SendMessage blocks until the Task pauses or finishes, where a chat turn returns as soon as the
# SSE stream closes. A whole stage can run inside one A2A turn, so it needs a longer budget.
: "${E2E_A2A_TURN_TIMEOUT_SEC:=900}"

payload_file="$(mktemp)"
trap 'rm -f "$payload_file"' EXIT

python3 - "$TASK_ID" "$MESSAGE" "$STRUCTURED" >"$payload_file" <<'PY'
import json
import sys
import uuid

task_id, message, structured = sys.argv[1:4]
parts = []
if message:
    parts.append({"text": message})
if structured:
    parts.append({"data": json.loads(structured)})
if not parts:
    raise SystemExit("a2a-turn: neither text nor structured data given")

message_id = str(uuid.uuid4())
body = {
    "jsonrpc": "2.0",
    "id": message_id,
    "method": "SendMessage",
    "params": {
        "message": {
            "messageId": message_id,
            "role": "ROLE_USER",
            "parts": parts,
            "metadata": {"skillId": "create-chain@2"},
        }
    },
}
if task_id and task_id != "-":
    body["params"]["message"]["taskId"] = task_id
print(json.dumps(body))
PY

e2e_info "a2a JSON-RPC SendMessage ${BASE_URL}/rpc"

http_code="$(
  curl -sS --max-time "${E2E_A2A_TURN_TIMEOUT_SEC}" \
    -o "$OUT_JSON" -w '%{http_code}' \
    -X POST "${BASE_URL}/rpc" \
    -H 'Content-Type: application/json' \
    -H 'A2A-Version: 1.0' \
    -d @"$payload_file"
)"

if [[ "$http_code" != "200" ]]; then
  e2e_fail "a2a turn returned HTTP ${http_code}: $(head -c 400 "$OUT_JSON")"
fi

if jq -e '.error != null' "$OUT_JSON" >/dev/null; then
  e2e_fail "a2a SendMessage returned JSON-RPC error: $(head -c 400 "$OUT_JSON")"
fi

NEW_TASK_ID="$(python3 -c '
import json, sys
result = json.load(open(sys.argv[1])).get("result") or {}
task = result.get("task") or result
print(task.get("id") or "")
' "$OUT_JSON")"

if [[ -z "$NEW_TASK_ID" ]]; then
  e2e_fail "a2a response carried no task id: $(head -c 400 "$OUT_JSON")"
fi

printf '%s' "$NEW_TASK_ID"
