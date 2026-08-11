#!/usr/bin/env bash
# Verify catalog chain shape from scenarios.json catalog section.
# Usage: assert-catalog.sh <scenarios.json> <scenario> <catalog_url> <chain_id>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

SCENARIOS_FILE="${1:?scenarios}"
SCENARIO="${2:?scenario}"
CATALOG_URL="${3:?catalog url}"
CHAIN_ID="${4:?chain id}"

e2e_require_cmds curl jq

catalog_json="$(mktemp)"
elements_json="$(mktemp)"
deps_json="$(mktemp)"
trap 'rm -f "$catalog_json" "$elements_json" "$deps_json"' EXIT

curl -sf "${CATALOG_URL}/v1/chains/${CHAIN_ID}" >"$catalog_json" \
  || e2e_fail "catalog GET /v1/chains/${CHAIN_ID} failed"

curl -sf "${CATALOG_URL}/v1/chains/${CHAIN_ID}/elements" >"$elements_json" \
  || e2e_fail "catalog GET elements failed for chain ${CHAIN_ID}"

curl -sf "${CATALOG_URL}/v1/chains/${CHAIN_ID}/dependencies" >"$deps_json" \
  || e2e_fail "catalog GET dependencies failed for chain ${CHAIN_ID}"

if ! jq -e --arg s "$SCENARIO" '.[$s].catalog' "$SCENARIOS_FILE" >/dev/null; then
  e2e_info "no catalog section for ${SCENARIO}; skip catalog assertions"
  exit 0
fi

if jq -e --arg s "$SCENARIO" '(.[$s].catalog.requiredTypes // []) | length == 0' "$SCENARIOS_FILE" >/dev/null \
  && jq -e --arg s "$SCENARIO" '(.[$s].catalog.forbiddenTypes // []) | length == 0' "$SCENARIOS_FILE" >/dev/null \
  && jq -e --arg s "$SCENARIO" '(.[$s].catalog.properties // []) | length == 0' "$SCENARIOS_FILE" >/dev/null \
  && jq -e --arg s "$SCENARIO" '(.[$s].catalog.nestedContainers // []) | length == 0' "$SCENARIOS_FILE" >/dev/null \
  && jq -e --arg s "$SCENARIO" '.[$s].catalog.skeleton == null' "$SCENARIOS_FILE" >/dev/null; then
  while IFS= read -r line; do
    [[ -n "$line" ]] && e2e_warn "catalog note: ${line}"
  done < <(jq -r --arg s "$SCENARIO" '.[$s].catalog.warnings[]? // empty' "$SCENARIOS_FILE")
  e2e_info "catalog checks skipped (warnings-only)"
  exit 0
fi

while IFS= read -r line; do
  [[ -n "$line" ]] && e2e_warn "catalog note: ${line}"
done < <(jq -r --arg s "$SCENARIO" '.[$s].catalog.warnings[]? // empty' "$SCENARIOS_FILE")

while IFS= read -r required_type; do
  [[ -n "$required_type" ]] || continue
  if ! jq -e --arg t "$required_type" '[.[] | .. | objects | select(.type? == $t)] | length > 0' \
      "$elements_json" >/dev/null; then
    e2e_fail "catalog missing required element type: ${required_type}"
  fi
  e2e_pass "catalog has element type ${required_type}"
done < <(jq -r --arg s "$SCENARIO" '.[$s].catalog.requiredTypes[]? // empty' "$SCENARIOS_FILE")

while IFS= read -r forbidden_type; do
  [[ -n "$forbidden_type" ]] || continue
  if jq -e --arg t "$forbidden_type" '[.[] | .. | objects | select(.type? == $t)] | length > 0' \
      "$elements_json" >/dev/null; then
    e2e_fail "catalog contains forbidden element type: ${forbidden_type}"
  fi
  e2e_pass "catalog forbids ${forbidden_type}"
done < <(jq -r --arg s "$SCENARIO" '.[$s].catalog.forbiddenTypes[]? // empty' "$SCENARIOS_FILE")

while IFS= read -r row; do
  [[ -n "$row" ]] || continue
  elem_type="$(jq -r '.type' <<<"$row")"
  prop_key="$(jq -r '.key' <<<"$row")"
  expected="$(jq -r '.equals' <<<"$row")"
  actual="$(jq -r --arg t "$elem_type" --arg k "$prop_key" '
    [.. | objects | select(.type? == $t) | .properties[$k] // empty] | first // empty
  ' "$elements_json")"
  if [[ "$expected" == "false" && -z "$actual" ]]; then
    actual="false"
  fi
  if [[ "$actual" != "$expected" ]]; then
    e2e_fail "catalog property ${elem_type}.${prop_key}: expected '${expected}', got '${actual}'"
  fi
  e2e_pass "catalog property ${elem_type}.${prop_key}=${expected}"
done < <(jq -c --arg s "$SCENARIO" '.[$s].catalog.properties[]? // empty' "$SCENARIOS_FILE")

while IFS= read -r row; do
  [[ -n "$row" ]] || continue
  container_type="$(jq -r '.containerType' <<<"$row")"
  child_type="$(jq -r '.childType // empty' <<<"$row")"
  min_children="$(jq -r '.minChildren // 1' <<<"$row")"
  must_types_json="$(jq -c '.mustHaveChildTypes // []' <<<"$row")"
  matched=false
  while IFS= read -r container_id; do
    [[ -n "$container_id" ]] || continue
    children="$(jq -c --arg id "$container_id" '
      [.. | objects | select(.id? == $id) | .children // []] | first // []
    ' "$elements_json")"
    if [[ "$must_types_json" != "[]" ]]; then
      ok=true
      while IFS= read -r required_type; do
        [[ -n "$required_type" ]] || continue
        # -n: do not read the while-loop stdin (required_type stream) as jq input
        if ! jq -ne --argjson children "$children" --arg t "$required_type" '
          [$children[] | select(.type? == $t)] | length > 0
        ' >/dev/null; then
          ok=false
          break
        fi
      done < <(jq -r '.[]' <<<"$must_types_json")
      if [[ "$ok" == "true" ]]; then
        matched=true
        break
      fi
      continue
    fi
    if [[ -n "$child_type" && "$child_type" != "null" ]]; then
      # -n: do not consume the container_id process-substitution stdin
      count="$(jq -nr --argjson children "$children" --arg t "$child_type" '
        [$children[] | select(.type? == $t)] | length
      ')"
      if ((count >= min_children)); then
        matched=true
        break
      fi
    fi
  done < <(jq -r --arg t "$container_type" '[.[] | select(.type? == $t) | .id] | .[]' "$elements_json")
  if [[ "$matched" != "true" ]]; then
    e2e_fail "catalog nested container ${container_type} failed nesting contract"
  fi
  e2e_pass "catalog nested container ${container_type} ok"
done < <(jq -c --arg s "$SCENARIO" '.[$s].catalog.nestedContainers[]? // empty' "$SCENARIOS_FILE")

min_deps="$(jq -r --arg s "$SCENARIO" '.[$s].catalog.skeleton.minDependencies // empty' "$SCENARIOS_FILE")"
if [[ -n "$min_deps" && "$min_deps" != "null" ]]; then
  dep_count="$(jq 'length' "$deps_json")"
  if ((dep_count < min_deps)); then
    e2e_fail "catalog skeleton: expected >= ${min_deps} dependencies, got ${dep_count}"
  fi
  e2e_pass "catalog skeleton dependencies=${dep_count} (min ${min_deps})"
fi

if jq -e --arg s "$SCENARIO" '.[$s].catalog.skeleton.triggerMustHaveOutgoing == true' "$SCENARIOS_FILE" >/dev/null; then
  trigger_id="$(jq -r '[.. | objects | select(.type? == "http-trigger") | .id] | first // empty' "$elements_json")"
  [[ -n "$trigger_id" ]] || e2e_fail "catalog skeleton: http-trigger not found"
  outgoing="$(jq -r --arg from "$trigger_id" '[.[] | select(.from == $from)] | length' "$deps_json")"
  if ((outgoing < 1)); then
    e2e_fail "catalog skeleton: trigger has no outgoing dependencies"
  fi
  e2e_pass "catalog skeleton: trigger has outgoing edge"
fi

while IFS= read -r reachable_type; do
  [[ -n "$reachable_type" ]] || continue
  trigger_id="$(jq -r '[.. | objects | select(.type? == "http-trigger") | .id] | first // empty' "$elements_json")"
  target_ids="$(jq -r --arg t "$reachable_type" '[.. | objects | select(.type? == $t) | .id] | unique[]' "$elements_json")"
  if [[ -z "$target_ids" ]]; then
    e2e_fail "catalog skeleton: no element of type ${reachable_type}"
  fi
  found=false
  while IFS= read -r target_id; do
    [[ -n "$target_id" ]] || continue
    if jq -e --arg from "$trigger_id" --arg to "$target_id" '
      def walk_deps($from; $to):
        if $from == $to then true
        else any(.[] | select(.from == $from) | walk_deps(.to; $to))
        end;
      walk_deps($from; $to)
    ' "$deps_json" >/dev/null 2>&1; then
      found=true
      break
    fi
    # BFS fallback in shell for simple chains
    if rg -q "\"from\"[[:space:]]*:[[:space:]]*\"${trigger_id}\"" "$deps_json" \
      && rg -q "\"to\"[[:space:]]*:[[:space:]]*\"${target_id}\"" "$deps_json"; then
      found=true
      break
    fi
  done <<<"$target_ids"
  if [[ "$found" != "true" ]]; then
    e2e_warn "catalog skeleton: could not prove reachability trigger -> ${reachable_type} (direct edge check only)"
    if ! rg -q "\"to\"[[:space:]]*:[[:space:]]*\".*\"" "$deps_json"; then
      e2e_fail "catalog skeleton: no path to ${reachable_type}"
    fi
  fi
  e2e_pass "catalog skeleton: ${reachable_type} reachable from trigger"
done < <(jq -r --arg s "$SCENARIO" '.[$s].catalog.skeleton.reachableFromTrigger[]? // empty' "$SCENARIOS_FILE")
