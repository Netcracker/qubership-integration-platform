#!/usr/bin/env bash
# Seed a small catalog chain for COMPARE_AND_PATCH e2e from a scenario seed block.
# Usage: seed-catalog-chain.sh <scenarios.json> <scenario> <catalog_url> <chain_name>
# Prints the created chain id.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

SCENARIOS_FILE="${1:?scenarios}"
SCENARIO="${2:?scenario}"
CATALOG_URL="${3:?catalog url}"
CHAIN_NAME="${4:?chain name}"

e2e_require_cmds curl jq python3

jq -e --arg s "$SCENARIO" '.[$s].seed.elements | type == "array" and length > 0' \
  "$SCENARIOS_FILE" >/dev/null \
  || e2e_fail "scenario ${SCENARIO} is missing seed.elements"

seed_json="$(jq -c --arg s "$SCENARIO" '.[$s].seed' "$SCENARIOS_FILE")"
description="$(jq -r '.description // "COMPARE_AND_PATCH e2e seed"' <<<"$seed_json")"

create_body="$(jq -nc --arg n "$CHAIN_NAME" --arg d "$description" \
  '{name: $n, description: $d, labels: []}')"
chain_json="$(mktemp)"
trap 'rm -f "$chain_json"' EXIT

http_code="$(
  curl -sS -o "$chain_json" -w '%{http_code}' \
    -X POST "${CATALOG_URL}/v1/chains" \
    -H 'Content-Type: application/json' \
    -d "$create_body"
)"
[[ "$http_code" == "200" || "$http_code" == "201" ]] \
  || e2e_fail "POST /v1/chains returned HTTP ${http_code}: $(head -c 400 "$chain_json")"

chain_id="$(jq -r '.id // empty' "$chain_json")"
[[ -n "$chain_id" ]] || e2e_fail "catalog create chain returned no id: $(head -c 400 "$chain_json")"
e2e_info "seeded chain ${chain_id} name=${CHAIN_NAME}"

python3 - "$CATALOG_URL" "$chain_id" "$seed_json" <<'PY'
import json
import sys
import urllib.error
import urllib.request

catalog_url, chain_id, seed_raw = sys.argv[1:4]
seed = json.loads(seed_raw)


def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        catalog_url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as err:
        detail = err.read().decode(errors="replace")
        raise SystemExit(f"FAIL: {method} {path} HTTP {err.code}: {detail[:400]}") from err


def first_created_id(diff, expected_type=None):
    created = diff.get("createdElements") or []
    if expected_type:
        for item in created:
            if item.get("type") == expected_type and item.get("id"):
                return item["id"]
    if created and created[0].get("id"):
        return created[0]["id"]
    raise SystemExit(f"FAIL: createElement returned no id: {json.dumps(diff)[:400]}")


ids_by_index = []
ids_by_name = {}
for spec in seed.get("elements") or []:
    element_type = spec["type"]
    _, diff = request(
        "POST",
        f"/v1/chains/{chain_id}/elements",
        {"type": element_type, "parentElementId": None, "swimlaneId": None},
    )
    element_id = first_created_id(diff, element_type)
    _, current = request("GET", f"/v1/chains/{chain_id}/elements/{element_id}")
    properties = dict(current.get("properties") or {})
    properties.update(spec.get("properties") or {})
    patch = {
        "name": spec.get("name") or current.get("name") or element_type,
        "properties": properties,
    }
    if current.get("description") is not None:
        patch["description"] = current["description"]
    request("PATCH", f"/v1/chains/{chain_id}/elements/{element_id}", patch)
    ids_by_index.append(element_id)
    ids_by_name[spec.get("name") or element_type] = element_id
    print(f"seeded {element_type} {element_id}", file=sys.stderr)

for edge in seed.get("edges") or []:
    if "fromIndex" in edge and "toIndex" in edge:
        source = ids_by_index[int(edge["fromIndex"])]
        target = ids_by_index[int(edge["toIndex"])]
    else:
        source = ids_by_name[edge["fromName"]]
        target = ids_by_name[edge["toName"]]
    request("POST", f"/v1/chains/{chain_id}/dependencies", {"from": source, "to": target})
    print(f"seeded edge {source} -> {target}", file=sys.stderr)

print(chain_id)
PY
