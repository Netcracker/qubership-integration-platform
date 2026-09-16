#!/usr/bin/env python3
"""Fail when a supported CIP element has no declared live test scenario."""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path


TOP_LEVEL_SCALAR = re.compile(r"^([A-Za-z][A-Za-z0-9]*):\s*([^#\n]+?)\s*$", re.MULTILINE)


def load_supported_elements(matrix_path: Path) -> set[str]:
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    return {
        row["elementType"]
        for row in matrix.get("elements", [])
        if row.get("status") == "SUPPORTED" and row.get("elementType")
    }


def load_regression_coverage(suites_root: Path) -> dict[str, list[str]]:
    coverage: dict[str, list[str]] = defaultdict(list)
    if not suites_root.is_dir():
        return coverage

    for case_path in sorted(suites_root.glob("*/*.yaml")):
        fields = dict(TOP_LEVEL_SCALAR.findall(case_path.read_text(encoding="utf-8")))
        element_type = fields.get("elementType", "").strip("'\"")
        case_id = fields.get("id", case_path.stem).strip("'\"")
        skill_id = fields.get("skillId", case_path.parent.name).strip("'\"")
        if element_type:
            coverage[element_type].append(f"regression:{skill_id}/{case_id}")
    return coverage


def load_product_coverage(scenarios_path: Path) -> dict[str, list[str]]:
    scenarios = json.loads(scenarios_path.read_text(encoding="utf-8"))
    coverage: dict[str, list[str]] = defaultdict(list)
    for scenario_id, scenario in scenarios.items():
        if scenario.get("status") != "active":
            continue
        suffix = " [known-failure]" if scenario.get("liveTest", {}).get("knownFailure") else ""
        for element_type in scenario.get("catalog", {}).get("requiredTypes", []):
            coverage[element_type].append(f"product:{scenario_id}{suffix}")
    return coverage


def merge_coverage(*sources: dict[str, list[str]]) -> dict[str, list[str]]:
    merged: dict[str, list[str]] = defaultdict(list)
    for source in sources:
        for element_type, evidence in source.items():
            merged[element_type].extend(evidence)
    return {key: sorted(set(value)) for key, value in merged.items()}


def render_coverage(supported: set[str], coverage: dict[str, list[str]]) -> str:
    lines = [
        "| element | declared live test |",
        "| --- | --- |",
    ]
    for element_type in sorted(supported):
        evidence = "<br>".join(coverage.get(element_type, [])) or "MISSING"
        lines.append(f"| {element_type} | {evidence} |")
    covered = sum(1 for element_type in supported if coverage.get(element_type))
    lines.append("")
    lines.append(f"Coverage: {covered}/{len(supported)} supported element types.")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parents[2]
    parser = argparse.ArgumentParser(
        description="Check that every supported element has a declared live test scenario."
    )
    parser.add_argument(
        "--matrix",
        type=Path,
        default=repo_root
        / "ai-service/target/classes/qipknowledge/integration-platform-skills/element-support-matrix.json",
    )
    parser.add_argument(
        "--scenarios",
        type=Path,
        default=script_dir / "scenarios.json",
    )
    parser.add_argument(
        "--regression-suites",
        type=Path,
        default=repo_root / "integration-platform-skills/regression/suites",
    )
    parser.add_argument(
        "--fail-on-known-failure",
        action="store_true",
        help="Also fail when an element is covered only by a scenario marked as a known failure.",
    )
    args = parser.parse_args(argv)

    supported = load_supported_elements(args.matrix)
    coverage = merge_coverage(
        load_regression_coverage(args.regression_suites),
        load_product_coverage(args.scenarios),
    )
    print(render_coverage(supported, coverage))

    missing = sorted(supported - coverage.keys())
    if missing:
        print(f"Missing declared live tests: {', '.join(missing)}")
        return 1
    known_failures = sorted(
        element_type
        for element_type in supported
        if coverage.get(element_type)
        and all("[known-failure]" in item for item in coverage[element_type])
    )
    if known_failures:
        print(f"Supported elements covered only by known-failing tests: {', '.join(known_failures)}")
        if args.fail_on_known_failure:
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
