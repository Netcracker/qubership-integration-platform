from __future__ import annotations

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

DIAGNOSES = {"UNKNOWN", "HYPOTHESIS", "CONFIRMED"}


def diagnosis() -> dict:
    return {
        "status": "UNKNOWN",
        "summary": "The root cause has not been established.",
        "evidence": [],
    }


def failure(
    suite_id: str,
    case_id: str,
    message: str,
    category: str,
    artifact: str,
    code: str = "",
) -> dict:
    fingerprint = hashlib.sha256(f"{suite_id}:{case_id}:{code}".encode()).hexdigest()[
        :20
    ]
    return {
        "suiteId": suite_id,
        "caseId": case_id,
        "fingerprint": fingerprint,
        "category": category,
        "error": {"code": code, "message": redact(message), "artifact": artifact},
        "diagnosis": diagnosis(),
        "recovery": {
            "status": "NOT_OBSERVED",
            "attempts": [],
            "diagnosis": diagnosis(),
        },
    }


def inspect_reports(
    suite: dict, task_dir: Path, reports: list[Path], return_code: int
) -> dict:
    failures = []
    counts = {"tests": 0, "failures": 0, "skipped": 0}
    skipped_cases = []
    evidence = [str(p.relative_to(task_dir)) for p in reports]
    kind = suite.get("format", "command")
    boundary = suite.get("verifiedBoundary", "test-command")
    declared_status = "PASS"
    if kind != "command" and not reports:
        raise ValueError("The command did not produce its required report")
    for path in reports:
        relative = str(path.relative_to(task_dir))
        if kind == "junit":
            root = ET.parse(path).getroot()
            for case in root.iter("testcase"):
                counts["tests"] += 1
                if case.find("skipped") is not None:
                    counts["skipped"] += 1
                    skipped_cases.append(
                        failure(
                            suite["id"],
                            f"{case.get('classname', '')}#{case.get('name', '')}",
                            case.find("skipped").get(
                                "message", "The selected test was not executed."
                            ),
                            "coverage",
                            relative,
                            "REQUIRED_TEST_NOT_RUN",
                        )
                    )
                rejected = case.find("failure")
                if rejected is None:
                    rejected = case.find("error")
                if rejected is not None:
                    case_id = f"{case.get('classname', '')}#{case.get('name', '')}"
                    failures.append(
                        failure(
                            suite["id"],
                            case_id,
                            redact(
                                (
                                    rejected.get("message", "")
                                    + "\n"
                                    + (rejected.text or "")
                                ).strip()
                            ),
                            suite.get("feature", "test"),
                            relative,
                            rejected.get("type", "TEST_FAILURE"),
                        )
                    )
        elif kind == "jest":
            document = json.loads(path.read_text())
            counts["tests"] += document["numTotalTests"]
            counts["skipped"] += document["numPendingTests"]
            for file in document["testResults"]:
                for case in file["assertionResults"]:
                    if case["status"] == "failed":
                        failures.append(
                            failure(
                                suite["id"],
                                case["fullName"],
                                "\n".join(case["failureMessages"]),
                                suite.get("feature", "ui"),
                                relative,
                                "ASSERTION_FAILED",
                            )
                        )
            if not document["success"] and not failures:
                failures.append(
                    failure(
                        suite["id"],
                        suite["id"],
                        "Jest reported an unsuccessful run.",
                        "harness",
                        relative,
                        "JEST_ERROR",
                    )
                )
        elif kind == "result":
            document = json.loads(path.read_text())
            if document.get("schemaVersion") != 1 or document.get("status") not in {
                "PASS",
                "FAIL",
                "ERROR",
                "BLOCKED",
            }:
                raise ValueError("Invalid package result contract")
            counts["tests"] += 1
            boundary = document.get("verifiedBoundary", "test-command")
            for episode in document.get("failures", []):
                if not isinstance(episode.get("error", {}).get("message"), str):
                    raise ValueError("Failure episodes need an error message")
                row = failure(
                    suite["id"],
                    episode.get("caseId", suite["id"]),
                    redact(episode["error"]["message"]),
                    episode.get("category", suite.get("feature", "test")),
                    relative,
                    episode["error"].get("code", ""),
                )
                row["diagnosis"] = episode.get("diagnosis", diagnosis())
                row["recovery"] = episode.get("recovery", row["recovery"])
                _validate_diagnoses(row, task_dir)
                recovery = row["recovery"]
                if not isinstance(recovery.get("attempts"), list) or any(
                    not isinstance(a, dict) for a in recovery["attempts"]
                ):
                    raise ValueError("Recovery attempts must be an array of objects")
                if recovery.get("status") not in {
                    "NOT_OBSERVED",
                    "NOT_ATTEMPTED",
                    "FAILED",
                    "RECOVERED",
                }:
                    raise ValueError("Unknown recovery status")
                if recovery["status"] == "RECOVERED":
                    if not recovery.get("attempts") or not recovery.get("evidence"):
                        raise ValueError(
                            "Successful recovery needs attempts and saved continuation evidence"
                        )
                    _validate_evidence(recovery["evidence"], task_dir)
                failures.append(row)
            if document["status"] != "PASS":
                declared_status = document["status"]
                if not document.get("failures"):
                    failures.append(
                        failure(
                            suite["id"],
                            suite["id"],
                            document.get("reason", "The native runner did not pass."),
                            "harness",
                            relative,
                            document["status"],
                        )
                    )
    if kind in {"junit", "jest"} and counts["tests"] <= counts["skipped"]:
        raise ValueError("The command produced no executed tests")
    if return_code and not failures:
        failures.append(
            failure(
                suite["id"],
                suite["id"],
                f"Command exited with status {return_code}. See output.log.",
                suite.get("feature", "test"),
                "output.log",
                "COMMAND_FAILED",
            )
        )
    if counts["skipped"] and not failures and not return_code:
        declared_status = "BLOCKED"
    failures.extend(skipped_cases)
    counts["failures"] = len(failures)
    status = (
        declared_status
        if declared_status != "PASS"
        else (
            "FAIL"
            if return_code
            or any(x["recovery"]["status"] != "RECOVERED" for x in failures)
            else "PASS"
        )
    )
    return {
        "status": status,
        "counts": counts,
        "failures": failures,
        "evidence": evidence,
        "verifiedBoundary": boundary,
    }


def _validate_diagnoses(episode: dict, root: Path):
    for item in (
        episode["diagnosis"],
        episode["recovery"].get("diagnosis", diagnosis()),
    ):
        if item.get("status") not in DIAGNOSES:
            raise ValueError("Unknown diagnosis status")
        if item["status"] == "CONFIRMED" and not item.get("evidence"):
            raise ValueError("A confirmed diagnosis needs evidence")
        _validate_evidence(item.get("evidence", []), root)


def _validate_evidence(references: list, root: Path):
    if not isinstance(references, list) or any(
        not isinstance(name, str) for name in references
    ):
        raise ValueError("Evidence must be an array of saved paths")
    for name in references:
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            raise ValueError("Evidence is missing or outside the task")


def redact(text: str) -> str:
    text = re.sub(
        r"(?i)(authorization\s*[:=]\s*)(?:bearer|basic)\s+\S+", r"\1[REDACTED]", text
    )
    return re.sub(
        r"(?i)((?:api[_-]?key|password|secret|access[_-]?token)\s*[:=]\s*)[^\s,;]+",
        r"\1[REDACTED]",
        text,
    )
