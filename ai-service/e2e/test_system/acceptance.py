"""Run a pinned recovery acceptance launcher and retain its native verdicts."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from .catalog import digest, read_json
from .reporting import diagnosis
from .runs import write_json

MODELS = ("ordinary", "kimi")
CASES = ("parcel-credit", "parcel-credit-controlled")
COMPONENTS = ("requirement-protection", "read-loop")


def preparation(root: Path) -> tuple[Path, dict]:
    config = read_json(root / ".dev/recovery-acceptance.json")
    launcher = (root / config["launcher"]).resolve()
    if digest(launcher) != config["launcherSha256"]:
        raise ValueError(
            "The prepared acceptance launcher changed. Prepare a new candidate."
        )
    candidate_path = launcher.parent / "candidate.json"
    if digest(candidate_path) != config["candidateSha256"]:
        raise ValueError("The prepared acceptance candidate changed.")
    candidate = read_json(candidate_path)
    if candidate.get("limitsChanged") is not False:
        raise ValueError("Acceptance must preserve the product limits.")
    for prefix in (
        "ai-service/src",
        "schemas/src/main/resources/conf-model",
        ".private/rocky-skills",
        ".private/rocky-knowledge",
    ):
        expected_files = {
            name for name in candidate["files"] if name.startswith(prefix + "/")
        }
        current_files = {
            str(p.relative_to(root))
            for p in (root / prefix).rglob("*")
            if p.is_file() and "__pycache__" not in p.parts
        }
        if current_files != expected_files:
            raise ValueError(
                "The candidate file inventory differs from the workspace: " + prefix
            )
    for name, expected in candidate["files"].items():
        if name.startswith(
            ("ai-service/src/", "schemas/src/", ".private/")
        ) or name in {
            "pom.xml",
            "ai-service/pom.xml",
            "ai-service/Dockerfile",
        }:
            path = (root / name).resolve()
            if not path.is_relative_to(root.resolve()) or digest(path) != expected:
                raise ValueError("The candidate differs from the workspace: " + name)
    return launcher, config


def copy_evidence(source: Path, target: Path):
    if source.is_dir():
        shutil.copytree(source, target)


def summarize(root: Path, models: tuple[str, ...], return_code: int) -> dict:
    native = root / "native"
    summary = read_json(native / "acceptance/summary.json")
    rows = summary["results"]
    expected = {(m, "CONTROLLED_REGISTERED_COMPONENTS", None) for m in models}
    expected |= {(m, "FULL_PRODUCT_PIPELINE", c) for m in models for c in CASES}
    actual = [(r["model"], r["scope"], r.get("case")) for r in rows]
    if len(actual) != len(expected) or set(actual) != expected:
        raise ValueError("The native acceptance matrix is incomplete.")
    failures = []
    passed = (
        not return_code
        and summary["outcome"] == "GO"
        and not summary["helperRestoreFailures"]
    )
    for row in rows:
        model = row["model"]
        ok = row["outcome"] == "PASS" and row.get("exitCode", 0) == 0
        passed = passed and ok
        if row["scope"] == "CONTROLLED_REGISTERED_COMPONENTS":
            for component in COMPONENTS:
                folder = native / "acceptance" / model / "components" / component
                receipt_path = folder / "result.json"
                receipt = read_json(receipt_path) if receipt_path.is_file() else {}
                recovered = (
                    ok
                    and receipt.get("outcome") == "PASS"
                    and receipt.get("realProvider") is True
                    and receipt.get("nativeCaptureAccepted") is True
                )
                if ok and not recovered:
                    raise ValueError(
                        "The component has no real-provider continuation receipt."
                    )
                evidence = [
                    str(p.relative_to(root)) for p in sorted(folder.glob("*.json"))
                ]
                failures.append(
                    {
                        "caseId": model + "/" + component,
                        "category": "controlled-component-recovery",
                        "error": {
                            "code": component,
                            "message": "Controlled native refusal: " + component,
                        },
                        "recovery": {
                            "status": "RECOVERED" if recovered else "FAILED",
                            "attempts": [{"nativeReceipt": receipt}],
                            "evidence": evidence,
                            "diagnosis": diagnosis(),
                        },
                    }
                )
        else:
            case = row["case"]
            folder = native / "product" / model / case
            verdict = (
                read_json(folder / "wave-result.json")
                if (folder / "wave-result.json").is_file()
                else {}
            )
            if ok and (
                verdict.get("outcome") != "PASS"
                or verdict.get("localScript", {}).get("outcome") != "PASS_LOCAL_HARNESS"
            ):
                raise ValueError(
                    "The full product case has no native proof and script verdict."
                )
            controlled = case == "parcel-credit-controlled"
            if ok and controlled and verdict.get("recovery") != "OBSERVED":
                raise ValueError("The controlled full case did not prove recovery.")
            durable = (
                read_json(folder / "durable.json")
                if (folder / "durable.json").is_file()
                else {}
            )
            attempts = durable.get("attempts", [])
            rejected = [a for a in attempts if a.get("outcome") == "FAILED"]
            if controlled or not ok or rejected:
                recovered = ok and verdict.get("recovery") == "OBSERVED"
                recovery_diagnosis = diagnosis()
                if not recovered and verdict.get("reasons"):
                    recovery_diagnosis["summary"] = "; ".join(verdict["reasons"])
                failures.append(
                    {
                        "caseId": model + "/" + case,
                        "category": (
                            "full-product-recovery"
                            if controlled
                            else "chain-construction"
                        ),
                        "error": {
                            "code": (
                                rejected[0].get("failureCode", "NATIVE_ACCEPTANCE")
                                if rejected
                                else "NATIVE_ACCEPTANCE"
                            ),
                            "message": (
                                rejected[0].get("failureEvidence") if rejected else None
                            )
                            or "; ".join(verdict.get("reasons", []))
                            or "Controlled HTTP capture refusal",
                        },
                        "recovery": {
                            "status": (
                                "RECOVERED"
                                if recovered
                                else "FAILED" if controlled else "NOT_OBSERVED"
                            ),
                            "attempts": attempts or [{"nativeVerdict": verdict}],
                            "evidence": (
                                [str((folder / "wave-result.json").relative_to(root))]
                                if verdict
                                else []
                            ),
                            "diagnosis": recovery_diagnosis,
                        },
                    }
                )
    if summary["helperRestoreFailures"] or return_code:
        failures.append(
            {
                "caseId": "acceptance-lifecycle",
                "category": "infrastructure",
                "error": {
                    "code": "ACCEPTANCE_PROCESS_FAILED",
                    "message": "Launcher exit status: "
                    + str(return_code)
                    + "; helper restoration failures: "
                    + json.dumps(summary["helperRestoreFailures"]),
                },
            }
        )
    return {
        "schemaVersion": 1,
        "status": "PASS" if passed else "FAIL",
        "verifiedBoundary": "registered-components-and-separate-full-product-cases",
        "failures": failures,
        "UI": "NOT_RUN",
        "engineRuntime": "NOT_RUN",
        "newMechanismsFullPipelineRecovery": "NOT_PROVEN_BY_COMPONENT_CHECKS",
        "sourceMeaningWithoutNativeProof": "OPEN",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "run"))
    parser.add_argument("--model", choices=(*MODELS, "both"), default="kimi")
    args = parser.parse_args()
    root = Path(os.environ["QIP_SERVICE_ROOT"])
    output = Path(os.environ["QIP_TEST_TASK_DIR"])
    (output / "native").mkdir()
    result = {
        "schemaVersion": 1,
        "status": "BLOCKED",
        "failures": [],
        "verifiedBoundary": "prepared-acceptance-readiness",
    }
    code = 0
    try:
        launcher, config = preparation(root)
        write_json(output / "native/preparation.json", config)
        command = [sys.executable, "-B", str(launcher), "check"]
        checked = subprocess.run(command, cwd=root, capture_output=True, text=True)
        print(checked.stdout, end="", flush=True)
        print(checked.stderr, end="", file=sys.stderr, flush=True)
        ready = [
            json.loads(line)
            for line in checked.stdout.splitlines()
            if line.startswith("{")
        ]
        if checked.returncode or not ready or ready[-1].get("outcome") != "READY":
            raise ValueError("The pinned acceptance preflight did not return READY.")
        if (
            ready[-1].get("limitsChanged") is not False
            or ready[-1].get("paidRequestsInPreparation") != 0
        ):
            raise ValueError(
                "The acceptance preparation changed limits or used paid requests."
            )
        write_json(output / "native/readiness.json", ready[-1])
        result["status"] = "PASS"
        if args.command == "run":
            attempt = "merged-" + output.parent.parent.name[-24:]
            models = MODELS if args.model == "both" else (args.model,)
            try:
                code = subprocess.call(
                    [
                        sys.executable,
                        "-B",
                        str(launcher),
                        "run",
                        "--model",
                        args.model,
                        "--attempt",
                        attempt,
                    ],
                    cwd=root,
                )
            finally:
                copy_evidence(
                    launcher.parent / "runs" / attempt, output / "native/acceptance"
                )
                wave = (root / config["waveRoot"]).resolve()
                for model in models:
                    for case in CASES:
                        source = (
                            wave
                            / "attempts"
                            / attempt
                            / "phases/final/runs"
                            / model
                            / case
                        )
                        copy_evidence(source, output / "native/product" / model / case)
            result = summarize(output, models, code)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result.update(
            status="BLOCKED" if result["status"] == "BLOCKED" else "ERROR",
            reason=str(error),
        )
    write_json(output / "result.json", result)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
