#!/usr/bin/env bash
# Run scenarios across both transports and append a durable summary after each one.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${BATCH_OUT:?set BATCH_OUT to a results directory}"
mkdir -p "$OUT/reports" "$OUT/logs"
SUMMARY="$OUT/summary.md"

if [[ ! -f "$SUMMARY" ]]; then
  {
    echo "# Transport parity runs — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "Stack: qip-ai-service 8094, certified Knowledge Package, evaluator 8100."
    echo "APIHub MCP is assumed down; scenarios avoid it."
    echo
    echo "| scenario | transport | terminal | chain id | element types | gate | evaluator |"
    echo "|---|---|---|---|---|---|---|"
  } >"$SUMMARY"
fi

run_one() {
  local scenario="$1" transport="$2" rep="$3"
  local tag="${scenario}.${transport}.r${rep}"
  local report="$OUT/reports/${tag}.json"
  local log="$OUT/logs/${tag}.log"

  echo "=== ${tag}"
  PRODUCT_PIPELINE_POLL_TIMEOUT_SEC=1200 \
    "$DIR/run-product-scenario.sh" \
      --scenario "$scenario" \
      --rep "$rep" \
      --base-url http://localhost:8094 \
      --evaluator-url http://localhost:8100 \
      --transport "$transport" \
      --report "$report" >"$log" 2>&1
  local rc=$?

  python3 - "$report" "$log" "$scenario" "$transport" "$rc" "$SUMMARY" <<'PY'
import json
import sys

report_path, log_path, scenario, transport, rc, summary_path = sys.argv[1:7]
try:
    report = json.load(open(report_path))
except Exception:
    report = {}
log = open(log_path, errors="replace").read()

terminal = report.get("terminalState") or "-"
chain = (report.get("materializedChainId") or "-")[:8]
types = ",".join(report.get("materializedElementTypes") or []) or "-"
gate = "PASS" if rc == "0" else "FAIL"
fail_line = next(
    (line for line in log.splitlines() if line.startswith("FAIL:")), ""
)
scores = ""
for line in log.splitlines():
    if line.startswith("{") and "intentFidelity" in line:
        s = json.loads(line)
        scores = " ".join(
            f"{k[:4]}={v}" for k, v in s.items() if isinstance(v, int)
        )
cell = scores or fail_line[:60] or "-"

with open(summary_path, "a") as out:
    out.write(
        f"| {scenario} | {transport} | {terminal} | {chain} | {types} | {gate} | {cell} |\n"
    )
print(f"    -> {terminal} gate={gate} {cell}")
PY
}

for scenario in "$@"; do
  for transport in chat a2a; do
    run_one "$scenario" "$transport" 1
  done
done

echo "summary: $SUMMARY"
