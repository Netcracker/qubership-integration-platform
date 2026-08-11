#!/usr/bin/env python3
"""Independent semantic scorer for product CREATE plan artifacts."""
from __future__ import annotations

import argparse
import json
import sys
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
    if not isinstance(evidence, list) or not all(isinstance(item, str) for item in evidence):
        raise ValueError("evidence must be a list of strings")
    return {key: payload[key] for key in DIMENSIONS} | {"evidence": list(evidence)}


def median(values: list[int]) -> float:
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return float(ordered[mid])
    return (ordered[mid - 1] + ordered[mid]) / 2.0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scores-file", required=True, help="JSON array of score objects")
    parser.add_argument("--min-median", type=float, default=3.0)
    parser.add_argument("--min-score", type=int, default=2)
    args = parser.parse_args()
    scores = json.loads(open(args.scores_file, encoding="utf-8").read())
    if not isinstance(scores, list) or not scores:
        raise SystemExit("scores-file must be a non-empty JSON array")
    validated = [validate_score(item) for item in scores]
    # Reliability is recorded by the quality-gate orchestrator, never synthesized here.
    report: dict[str, Any] = {
        "dimensions": {},
        "sampleCount": len(validated),
        "reliabilityFailures": [],
    }
    for dim in DIMENSIONS:
        values = [item[dim] for item in validated]
        if any(value < args.min_score for value in values):
            raise SystemExit(f"FAIL: {dim} has score below {args.min_score}")
        med = median(values)
        report["dimensions"][dim] = {"median": med, "values": values}
        if med < args.min_median:
            raise SystemExit(f"FAIL: {dim} median {med} < {args.min_median}")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
