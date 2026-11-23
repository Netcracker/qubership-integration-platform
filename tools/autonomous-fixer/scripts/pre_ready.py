"""Prepare a GitHub issue for pre-ready triage and write its Actions summary."""

import hashlib
import json
import os
import re
import sys
from datetime import UTC, datetime
from pathlib import Path

ACTIONS = {
    "ready": "Ready for implementation",
    "diagnose": "Start with a bounded investigation",
    "request_info": "Ask for missing information",
    "uncertain": "Engineer judgment needed",
}
MISSING = {
    "expected_behavior": "Which observable behavior should the fix preserve or change?",
    "integration_contract": "Which external contract or downstream implementation can we inspect?",
    "reproduction_context": "Which input or setup produced the reported symptom?",
    "access": "How can we access the required environment or source?",
}


def prepare(issue, number, repo):
    if not re.fullmatch(r"[1-9][0-9]*", number) or issue["number"] != int(number):
        raise ValueError("Issue number differs from the requested number")
    if "pull_request" in issue:
        return None, "The selected number belongs to a pull request."
    if issue["state"] != "open":
        return None, "The issue is closed."
    if any(label["name"] == "ready-for-agent" for label in issue["labels"]):
        return None, "The issue already has the ready-for-agent label."
    return {
        "task_id": f"{repo}#{number}",
        "phase": "pre-ready",
        "task": issue["title"] + "\n\n" + (issue["body"] or ""),
    }, None


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n").encode(
        "utf-8"
    )


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def source_digest(issue, repo):
    source = {
        "repository": repo.lower(),
        "number": issue["number"],
        "title": issue["title"],
        "body": issue["body"] or "",
    }
    return hashlib.sha256(json_bytes(source)).hexdigest()


def write_snapshot(root, source_sha256):
    state = read_json(root / "request" / "request.json")["state"]
    snapshot = json_bytes({"source_sha256": source_sha256, "state": state})
    (root / "snapshot.json").write_bytes(snapshot)
    return hashlib.sha256(snapshot).hexdigest()


def manual_assessment():
    decision = os.environ["ENGINEER_DECISION"]
    snapshot = os.environ["ASSESSMENT_SNAPSHOT"]
    if decision not in ACTIONS:
        raise ValueError("Unknown engineer decision")
    if not re.fullmatch(r"[0-9a-f]{64}", snapshot):
        raise ValueError("Enter the SHA-256 hash printed by the snapshot command.")
    return decision, snapshot


def write_summary(root, issue, repo, number, skip, source_sha256):
    manual = os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch"
    human, assessed_snapshot = manual_assessment() if manual else (None, None)
    snapshot_file = root / "snapshot.json"
    snapshot = snapshot_file.read_bytes() if snapshot_file.exists() else None
    snapshot_sha256 = hashlib.sha256(snapshot).hexdigest() if snapshot else None
    choice = None
    lines = [f"## Pre-ready triage for issue #{number}", ""]
    if manual:
        lines.append(f"Engineer assessment before this run: **{ACTIONS[human]}**.")
        lines.append(f"Assessed snapshot SHA-256: `{assessed_snapshot}`.")
    else:
        lines.append("Automatic triage for a newly opened issue.")
    if skip:
        lines.append(skip)
        status = "issue_ineligible"
    else:
        captured = datetime.fromtimestamp((root / "issue.json").stat().st_mtime, UTC)
        comments = issue["comments"]
        lines += [
            f"Snapshot taken at {captured.isoformat(timespec='seconds')}.",
            f"Title and description SHA-256: `{source_sha256}`.",
            f"Comments excluded from this snapshot: {comments}. Attachment contents were not inspected.",
            "",
        ]
        if snapshot_sha256:
            lines.append(f"Captured snapshot SHA-256: `{snapshot_sha256}`.")
        if manual and not snapshot:
            status = "snapshot_unavailable"
            lines.append(
                "The workflow could not prepare the issue snapshot. Check the snapshot step."
            )
        elif manual and assessed_snapshot != snapshot_sha256:
            status = "snapshot_mismatch"
            lines.append(
                "The assessment and the current issue snapshot differ. Capture and assess a new snapshot."
            )
        else:
            status, choice = append_recommendation(
                root, issue, repo, number, source_sha256, lines
            )
            if manual:
                (root / "reviewed-snapshot.json").write_bytes(snapshot)
                if status == "classified":
                    if "uncertain" in (human, choice):
                        status = "abstained"
                    else:
                        status = "agree" if human == choice else "disagree"
        lines += ["", "Review any comments or attachments before assigning work."]
    if manual:
        lines += ["", f"Agreement status: `{status}`."]
        agreement = {
            "repository": repo,
            "issue": int(number),
            "engineer_decision": human,
            "jev_recommendation": choice,
            "assessed_snapshot_sha256": assessed_snapshot,
            "captured_snapshot_sha256": snapshot_sha256,
            "status": status,
        }
        (root / "agreement.json").write_bytes(json_bytes(agreement))
    recommendation = "\n".join(lines) + "\n"
    (root / "recommendation.md").write_text(recommendation, encoding="utf-8")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
        f.write(recommendation)


def append_recommendation(root, issue, repo, number, source_sha256, lines):
    report = root / "jev" / "report.json"
    current = root / "current.json"
    data = read_json(report) if report.exists() else {}
    response = data.get("response") or {}
    if data:
        request_id = response.get("id")
        if isinstance(request_id, str) and re.fullmatch(
            r"[A-Za-z0-9._:-]{1,128}", request_id
        ):
            lines.append(f"Provider request: `{request_id}`.")
        cost = (response.get("usage") or {}).get("cost")
        lines.append(
            f"Provider cost: ${cost:.8f}."
            if isinstance(cost, (int, float))
            else "Provider cost unavailable."
        )
        lines.append(
            f"Policy: `{data['version']}`. Classifier time: {data['duration_ms']} ms."
        )
    if not current.exists():
        lines.append(
            "The workflow could not recheck the issue. Run triage again before using the result."
        )
        return "recheck_unavailable", None
    current_issue = read_json(current)
    if current_issue["comments"] != issue["comments"]:
        lines.append(
            f"Discussion changed during triage. The issue now has {current_issue['comments']} comments."
        )
    latest, _ = prepare(current_issue, number, repo)
    if latest is None or source_digest(current_issue, repo) != source_sha256:
        lines.append(
            "The issue changed during triage. Capture and assess a new snapshot before using the result."
        )
        return "issue_changed", None
    if not data:
        lines.append("No recommendation is available. Check the classification step.")
        return "classification_unavailable", None
    if data.get("error"):
        lines.append(
            "The classification failed. An engineer can still triage this issue."
        )
        return "classification_failed", None
    choice = data["selection"]["pre_ready_action"]
    lines.append(f"Jev recommendation: **{ACTIONS[choice]}**.")
    missing = data["selection"].get("missing_information")
    if choice == "request_info" and missing in MISSING:
        lines.append(f"Missing information: `{missing}`.")
        lines.append("Suggested clarification: " + MISSING[missing])
    return "classified", choice


def main():
    mode, folder = sys.argv[1:]
    root = Path(folder)
    issue = read_json(root / "issue.json")
    number = os.environ["ISSUE_NUMBER"]
    repo = os.environ["GITHUB_REPOSITORY"]
    packet, skip = prepare(issue, number, repo)
    if mode == "prepare":
        if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
            manual_assessment()
        if packet:
            (root / "packet.json").write_bytes(json_bytes(packet))
        if output := os.environ.get("GITHUB_OUTPUT"):
            with open(output, "a", encoding="utf-8") as f:
                print(f"eligible={'true' if packet else 'false'}", file=f)
        elif skip:
            raise SystemExit(skip)
        return
    source_sha256 = source_digest(issue, repo)
    if mode == "snapshot":
        print(write_snapshot(root, source_sha256))
    elif mode == "summary":
        write_summary(root, issue, repo, number, skip, source_sha256)
    else:
        raise ValueError("Unknown operation")


if __name__ == "__main__":
    main()
