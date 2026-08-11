#!/usr/bin/env python3
"""Send requirement facts and plan payload to an independent semantic evaluator."""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

DIMENSIONS = (
    "intentFidelity",
    "completeness",
    "executability",
    "unnecessaryComplexity",
)


def validate_score(payload: dict[str, Any]) -> dict[str, Any]:
    for key in DIMENSIONS:
        if key not in payload:
            raise ValueError(f"missing dimension {key}")
        value = payload[key]
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValueError(f"{key} must be an int")
        if value < 0 or value > 5:
            raise ValueError(f"{key} out of range: {value}")
    evidence = payload.get("evidence")
    if not isinstance(evidence, list) or not all(isinstance(item, str) and item for item in evidence):
        raise ValueError("evidence must be a nonempty list of nonempty strings")
    return {key: payload[key] for key in DIMENSIONS} | {"evidence": list(evidence)}


def stub_score(report: dict[str, Any]) -> dict[str, Any]:
    facts = report.get("requiredFacts") or ["CREATE"]
    return {
        "intentFidelity": 4,
        "completeness": 4,
        "executability": 4,
        "unnecessaryComplexity": 3,
        "evidence": [
            str(facts[0]),
            f"package={report.get('knowledgePackage', {}).get('packageKey')}",
            "stub-evaluator",
        ],
    }


def normalize_evaluator_url(url: str) -> str:
    """Accept base host or full path; POST always targets .../evaluate."""
    trimmed = (url or "").strip().rstrip("/")
    if not trimmed:
        raise ValueError("evaluator URL is blank")
    if trimmed.endswith("/evaluate"):
        return trimmed
    return trimmed + "/evaluate"


def call_evaluator(url: str, body: dict[str, Any]) -> dict[str, Any]:
    request = urllib.request.Request(
        normalize_evaluator_url(url),
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=135) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("evaluator response must be a JSON object")
    return validate_score(payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluator-url", required=True)
    parser.add_argument("--report", required=True, help="Path to report.json")
    parser.add_argument("--out", required=True, help="Path to write score JSON")
    args = parser.parse_args()

    report = json.loads(open(args.report, encoding="utf-8").read())
    plan = report.get("decodedPlan")
    if plan is None:
        plan = report.get("plan")
    requirement_facts = report.get("requirementFacts")
    if requirement_facts is None:
        requirement_facts = report.get("requiredFacts")

    if not isinstance(plan, dict) or not plan:
        print("FAIL: missing decoded ImplementationPlan payload", file=sys.stderr)
        return 1
    if not isinstance(requirement_facts, list) or not requirement_facts:
        print("FAIL: missing requirement facts payload", file=sys.stderr)
        return 1

    body = {
        "scenarioId": report.get("scenarioId"),
        "requiredFacts": report.get("requiredFacts") or [],
        "forbiddenFacts": report.get("forbiddenFacts") or [],
        "requirementFacts": requirement_facts,
        # Evaluator scores the approved plan; create-chain terminal is CHAIN_MATERIALIZED.
        "terminalState": (
            "PLAN_APPROVED"
            if report.get("terminalState") == "CHAIN_MATERIALIZED"
            else report.get("terminalState")
        ),
        "plan": plan,
    }

    if os.environ.get("PRODUCT_PIPELINE_STUB_MODE") == "1":
        score = stub_score(report)
    else:
        try:
            score = call_evaluator(args.evaluator_url, body)
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            print(f"FAIL: evaluator error: {exc}", file=sys.stderr)
            return 1

    score = validate_score(score)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(score, handle, indent=2)
        handle.write("\n")
    print(json.dumps(score))
    return 0


if __name__ == "__main__":
    sys.exit(main())
