"""Adapt existing campaign reports without replacing their assertions."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

from .catalog import read_json
from .reporting import diagnosis
from .runs import write_json


def summarize(kind: str, root: Path, return_code: int) -> dict:
    episodes = []
    if kind == "generators":
        reports = list((root / "native").glob("*.json"))
        if len(reports) != 1:
            raise ValueError("The generator runner needs one native summary")
        rows = read_json(reports[0])["rows"]
        if not rows:
            raise ValueError("The generator runner executed no cases")
        passed = all(row["result"] == "PASS" for row in rows)
        for row in rows:
            if row["result"] != "PASS":
                episodes.append(
                    {
                        "caseId": row["skill"] + "/" + row["element"],
                        "category": "generator",
                        "error": {
                            "code": row["result"],
                            "message": row.get("comment") or row["result"],
                        },
                    }
                )
        boundary = "generator-golden-and-catalog-readback"
    elif kind == "recovery":
        results = read_json(root / "native/campaign.json")
        if not results:
            raise ValueError("The recovery campaign executed no cases")
        for result in results:
            case_id = result["caseId"]
            folder = root / "native" / case_id
            evidence = read_json(folder / "evidence.json")
            durable = (
                read_json(folder / "durable.json")
                if (folder / "durable.json").is_file()
                else {}
            )
            attempts = durable.get("attempts", [])
            events = evidence.get("events", [])
            rejected = [a for a in attempts if a.get("outcome") == "FAILED"]
            faults = [e for e in events if e.get("type") == "FAULT_APPLIED"]
            passed = result["outcome"] in {"CHECKPOINT_PASS", "COMPLETION_PASS"}
            if passed:
                proof = read_json(folder / "proof-verdict.json")
                if (
                    proof.get("caseId") != case_id
                    or proof.get("outcome") != result["outcome"]
                ):
                    raise ValueError(
                        "Recovery summary differs from its saved native proof"
                    )
            # The native verifier checks fault admission, feedback, budgets, and consumer continuation.
            observed = bool(rejected or faults)
            if not passed or observed:
                message = (
                    rejected[0].get("failureEvidence") if rejected else None
                ) or (
                    json.dumps(faults[0], ensure_ascii=False)
                    if faults
                    else "; ".join(result.get("reasons", []))
                )
                recovery = {
                    "status": "RECOVERED" if passed and observed else "FAILED",
                    "attempts": attempts or events,
                    "diagnosis": diagnosis(),
                    "evidence": [
                        str((folder / "evidence.json").relative_to(root)),
                        str((folder / "proof-verdict.json").relative_to(root)),
                    ],
                }
                if not passed:
                    recovery["diagnosis"]["summary"] = (
                        "; ".join(result.get("reasons", []))
                        or "Inspect native recovery evidence."
                    )
                episodes.append(
                    {
                        "caseId": case_id,
                        "category": "recovery",
                        "error": {
                            "message": message or result["outcome"],
                            "code": (
                                rejected[0].get("failureCode", "")
                                if rejected
                                else "NATIVE_PROOF"
                            ),
                        },
                        "recovery": recovery,
                    }
                )
        passed = all(
            r["outcome"] in {"CHECKPOINT_PASS", "COMPLETION_PASS"} for r in results
        )
        boundary = "native-recovery-proof"
    else:
        summary = read_json(root / "native/summary.json")
        if summary.get("totalRuns", 0) <= 0 or summary.get("verdict") not in {
            "PASS",
            "FAIL",
        }:
            raise ValueError("The native campaign has no complete verdict")
        passed = summary["verdict"] == "PASS"
        boundary = {
            "quality": "product-and-catalog-readback",
            "planner": "planner-contract",
            "executor": "executor-contract",
        }[kind]
        for result in summary.get("results", []) + summary.get(
            "reliabilityFailures", []
        ):
            if result.get("passed") is False or result in summary.get(
                "reliabilityFailures", []
            ):
                episodes.append(
                    {
                        "caseId": result.get("caseId", result.get("scenario", kind)),
                        "category": kind,
                        "error": {
                            "message": json.dumps(result, ensure_ascii=False),
                            "code": "NATIVE_ASSERTION",
                        },
                    }
                )
        for path in sorted((root / "native/runs").rglob("evidence.json")):
            document = read_json(path)
            for attempt in document.get("attempts", []):
                if attempt.get("outcome") == "FAILED":
                    episodes.append(
                        {
                            "caseId": str(
                                path.parent.relative_to(root / "native/runs")
                            ),
                            "category": attempt.get("stageId", kind),
                            "error": {
                                "message": attempt.get("failureEvidence")
                                or json.dumps(attempt),
                                "code": attempt.get("failureCode", "STAGE_FAILURE"),
                            },
                            "recovery": {
                                "status": "NOT_OBSERVED",
                                "attempts": document.get("attempts", []),
                                "diagnosis": diagnosis(),
                            },
                        }
                    )
        if kind == "quality":
            reports = list((root / "native/runs").rglob("report.json"))
            if len(reports) != summary.get("expectedRuns") or any(
                read_json(p).get("stub") for p in reports
            ):
                raise ValueError(
                    "Live acceptance needs every native report and cannot use stub results"
                )
    return {
        "schemaVersion": 1,
        "status": "PASS" if passed and not return_code else "FAIL",
        "verifiedBoundary": boundary,
        "failures": episodes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "kind", choices=("quality", "planner", "executor", "recovery", "generators")
    )
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--evaluator-url", default="http://localhost:8100")
    parser.add_argument("--image", default="unspecified")
    args = parser.parse_args()
    root = Path(os.environ["QIP_TEST_TASK_DIR"])
    service = Path(os.environ["QIP_SERVICE_ROOT"]) / "ai-service/e2e"
    if args.kind == "generators":
        os.environ.update(
            REG_AI_URL=args.base_url,
            REG_REPORT_DIR=str(root / "native"),
            REG_ARTIFACTS_DIR=str(root / "native/artifacts"),
        )
        command = [
            "bash",
            str(
                Path(os.environ["QIP_SKILLS_TESTING_ROOT"])
                / "generators/bin/run-suite.sh"
            ),
            "--all",
        ]
    elif args.kind == "recovery":
        if args.image == "unspecified":
            parser.error("Recovery needs an explicit existing service image")
        command = [
            "python3",
            str(service / "product-pipeline/recovery_campaign.py"),
            "pilot",
            "--out",
            str(root / "native"),
            "--image",
            args.image,
        ]
    elif args.kind == "quality":
        command = [
            "bash",
            str(service / "product-pipeline/run-quality-gate.sh"),
            "--runs",
            "1",
            "--report-dir",
            str(root / "native"),
            "--base-url",
            args.base_url,
            "--evaluator-url",
            args.evaluator_url,
            "--skip-deploy",
        ]
    else:
        command = [
            "bash",
            str(service / f"{args.kind}-eval/run-{args.kind}-eval.sh"),
            "--runs",
            "1",
            "--report-dir",
            str(root / "native"),
            "--base-url",
            args.base_url,
        ]
    code = subprocess.call(command)
    try:
        result = summarize(args.kind, root, code)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result = {
            "schemaVersion": 1,
            "status": "ERROR",
            "reason": str(exc),
            "failures": [],
        }
    write_json(root / "result.json", result)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
