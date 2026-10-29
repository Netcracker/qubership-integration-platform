from __future__ import annotations

import contextlib
import fcntl
import glob
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import IO, Any, cast

from .catalog import ID, Catalog, digest, inside, read_json, tree_digest
from .reporting import DIAGNOSES, failure, inspect_reports, redact

TERMINAL = {"PASS", "FAIL", "ERROR", "BLOCKED", "CANCELLED"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, document):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def git_identity(root: Path) -> dict:
    def git(*args):
        result = subprocess.run(
            ["git", *args], cwd=root, capture_output=True, check=True
        )
        return result.stdout

    changes = hashlib.sha256(git("diff", "HEAD", "--", ":!.dev"))
    untracked = git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    for name in untracked:
        if name and (root / os.fsdecode(name)).is_file():
            changes.update(name)
            changes.update((root / os.fsdecode(name)).read_bytes())
    return {
        "commit": git("rev-parse", "HEAD").decode().strip(),
        "diffSha256": changes.hexdigest(),
        "dirty": bool(git("status", "--porcelain")),
    }


class RunStore:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def directory(self, run_id: str) -> Path:
        if not ID.fullmatch(run_id):
            raise ValueError("Invalid run ID")
        return inside(self.root, run_id)

    def read(self, run_id: str) -> dict:
        path = self.directory(run_id) / "run.json"
        document = read_json(path)
        if document["status"] not in TERMINAL and document.get("workerPid"):
            try:
                os.kill(document["workerPid"], 0)
            except ProcessLookupError:
                with self.edit(run_id) as current:
                    if current["status"] not in TERMINAL:
                        current.update(
                            status="ERROR",
                            finishedAt=now(),
                            reason="The worker exited before saving a terminal verdict. See worker.log.",
                        )
                        for task in current["tasks"]:
                            if task["status"] in {"RUNNING", "PENDING"}:
                                task.update(
                                    status="NOT_RUN",
                                    reason="The worker exited unexpectedly.",
                                )
                document = read_json(path)
        return document

    @contextlib.contextmanager
    def edit(self, run_id: str):
        folder = self.directory(run_id)
        with (folder / "run.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            document = read_json(folder / "run.json")
            yield document
            document["updatedAt"] = now()
            write_json(folder / "run.json", document)

    def list(self) -> list[dict]:
        rows = []
        for path in sorted(self.root.glob("*/run.json"), reverse=True):
            item = read_json(path)
            rows.append(
                {
                    k: item.get(k)
                    for k in (
                        "id",
                        "profile",
                        "status",
                        "createdAt",
                        "updatedAt",
                        "service",
                        "parentRunId",
                    )
                }
            )
        return sorted(rows, key=lambda item: item["createdAt"], reverse=True)

    def cancel(self, run_id: str):
        with self.edit(run_id) as run:
            if run["status"] not in TERMINAL:
                run["cancelRequested"] = True
                run["status"] = "CANCEL_REQUESTED"
        return self.read(run_id)

    def diagnose(self, run_id: str, failure_id: str, target: str, diagnosis: dict):
        if (
            target not in {"failure", "recovery"}
            or diagnosis.get("status") not in DIAGNOSES
        ):
            raise ValueError("Invalid diagnosis target or status")
        references = diagnosis.get("evidence", [])
        if not isinstance(references, list) or any(
            not isinstance(name, str) for name in references
        ):
            raise ValueError("Diagnosis evidence must be an array of saved paths")
        if diagnosis["status"] == "CONFIRMED" and not references:
            raise ValueError("A confirmed diagnosis needs saved evidence")
        for reference in references:
            if not inside(self.directory(run_id), reference).is_file():
                raise ValueError("Diagnosis evidence does not exist")
        with self.edit(run_id) as run:
            if run["status"] not in TERMINAL:
                raise ValueError(
                    "Wait for the run to finish before updating a diagnosis"
                )
            episode = next(
                (
                    f
                    for task in run["tasks"]
                    for f in task.get("failures", [])
                    if f["id"] == failure_id
                ),
                None,
            )
            if episode is None:
                raise ValueError("Unknown failure ID")
            owner = episode if target == "failure" else episode["recovery"]
            owner.setdefault("diagnosisHistory", []).append(
                {"at": now(), "previous": owner["diagnosis"]}
            )
            owner["diagnosis"] = {
                "status": diagnosis["status"],
                "summary": diagnosis.get("summary", ""),
                "evidence": references,
            }
        return self.read(run_id)


def create_run(
    catalog: Catalog,
    store: RunStore,
    profile: str,
    selection: list[str] | None = None,
    settings: dict | None = None,
    allow_llm: bool = False,
    parent: str | None = None,
) -> dict:
    plan = catalog.plan(profile, selection, settings)
    if parent:
        store.read(parent)
    run_id = (
        "run-"
        + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-")
        + uuid.uuid4().hex[:8]
    )
    folder = store.directory(run_id)
    folder.mkdir()
    block = plan["blocked"]
    if any(s["usesLlm"] for s in plan["suites"]) and not allow_llm:
        block = "LLM execution is disabled. Start with --allow-llm after selecting the intended model and environment."
    run = {
        "schemaVersion": 1,
        "id": run_id,
        "profile": profile,
        "status": "BLOCKED" if block else "QUEUED",
        "createdAt": now(),
        "updatedAt": now(),
        "finishedAt": now() if block else None,
        "service": git_identity(catalog.root),
        "serviceRoot": str(catalog.root),
        "packageRoot": str(catalog.package),
        "sources": catalog.sources,
        "settings": plan["settings"],
        "allowLlm": allow_llm,
        "parentRunId": parent,
        "cancelRequested": False,
        "reason": block,
        "tasks": [
            {"suite": s, "status": "BLOCKED" if block else "PENDING", "failures": []}
            for s in plan["suites"]
        ],
    }
    package_manifest = catalog.package / "manifest.json"
    if package_manifest.is_file():
        try:
            manifest = read_json(package_manifest)
            version = manifest.get("version")
        except (ValueError, AttributeError):
            version = "unreadable"
        run["skillsPackage"] = {"version": version, "sha256": digest(package_manifest)}
        run["skillsPackage"]["contentSha256"] = tree_digest(catalog.package / "skills")
    run["testingBundleSha256"] = tree_digest(catalog.package / "testing")
    run["knowledgePackageSha256"] = tree_digest(
        Path(plan["settings"]["knowledge_package"])
    )
    write_json(folder / "run.json", run)
    return run


def start_worker(store: RunStore, run_id: str, entry: Path) -> dict:
    with store.edit(run_id) as run:
        if run["status"] in TERMINAL or run.get("workerPid"):
            return run
        with (store.directory(run_id) / "worker.log").open("w") as log:
            process = subprocess.Popen(
                [
                    sys.executable,
                    str(entry),
                    "--store",
                    str(store.root),
                    "_worker",
                    run_id,
                ],
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        run["workerPid"] = process.pid
        threading.Thread(target=process.wait, daemon=True).start()
    return store.read(run_id)


def _snapshot(pattern: str) -> dict:
    return {
        Path(name): (Path(name).stat().st_mtime_ns, digest(Path(name)))
        for name in glob.glob(pattern, recursive=True)
        if Path(name).is_file()
    }


def _missing_requirements(suite: dict) -> list[str]:
    missing = []
    for requirement in suite.get("requires", []):
        if requirement.startswith("python:"):
            name = requirement.split(":", 1)[1]
            result = subprocess.run(
                [sys.executable, "-c", f"import {name}"],
                capture_output=True,
                check=False,
            )
            if result.returncode:
                missing.append(requirement)
        elif shutil.which(requirement) is None:
            missing.append(requirement)
    return missing


def _execute(catalog: Catalog, store: RunStore, run_id: str, index: int) -> dict:
    run = store.read(run_id)
    suite = run["tasks"][index]["suite"]
    directory = store.directory(run_id) / "tasks" / f"{index:03d}-{suite['id']}"
    directory.mkdir(parents=True)
    values = catalog.variables(directory, run["settings"])
    command = [part.format_map(values) for part in suite["command"]]
    cwd = Path(suite.get("cwd", "{service_root}").format_map(values)).resolve()
    report_pattern = suite.get("reports", "").format_map(values)
    missing = _missing_requirements(suite)
    if missing:
        return {
            "status": "BLOCKED",
            "reason": "Missing dependencies: " + ", ".join(missing),
            "failures": [],
        }
    before = _snapshot(report_pattern) if report_pattern else {}
    env = os.environ.copy()
    env["PATH"] = str(Path(sys.executable).parent) + os.pathsep + env.get("PATH", "")
    env.update(
        {
            "QIP_SERVICE_ROOT": str(catalog.root),
            "QIP_SKILLS_TESTING_ROOT": str(catalog.package / "testing"),
            "PYTHONUNBUFFERED": "1",
            "QIP_TEST_TASK_DIR": str(directory),
        }
    )
    env.update(
        {name: value.format_map(values) for name, value in suite.get("env", {}).items()}
    )
    if not suite["usesLlm"]:
        env["QIP_RECOVERY_LIVE"] = "false"
        for name in list(env):
            if name.endswith(("API_KEY", "API_TOKEN")) or name in {
                "QIP_LLM_PASSWORD",
                "ANTHROPIC_AUTH_TOKEN",
            }:
                env.pop(name)
    started = time.monotonic()
    with store.edit(run_id) as document:
        document["tasks"][index].update(
            {
                "status": "RUNNING",
                "startedAt": now(),
                "command": command,
                "directory": str(directory.relative_to(store.directory(run_id))),
                "log": str(
                    (directory / "output.log").relative_to(store.directory(run_id))
                ),
            }
        )
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
        start_new_session=True,
    )

    output = cast(IO[str], process.stdout)

    def capture():
        with (directory / "output.log").open("w", encoding="utf-8") as stream:
            for line in output:
                stream.write(redact(line))
                stream.flush()

    reader = threading.Thread(target=capture)
    reader.start()
    stopped = None
    try:
        while process.poll() is None:
            if store.read(run_id)["cancelRequested"]:
                stopped = "CANCELLED"
            elif time.monotonic() - started > suite["timeoutSeconds"]:
                stopped = "ERROR"
            if stopped:
                os.killpg(process.pid, signal.SIGINT)
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                break
            time.sleep(0.15)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=15)
        reader.join(timeout=5)
        output.close()
    reports = []
    for source, checksum in (
        _snapshot(report_pattern) if report_pattern else {}
    ).items():
        if before.get(source) != checksum:
            destination = directory / "reports" / source.name
            destination.parent.mkdir(exist_ok=True)
            shutil.copy2(source, destination)
            reports.append(destination)
    if stopped:
        reason = (
            "Cancellation requested."
            if stopped == "CANCELLED"
            else "The declared runner deadline expired."
        )
        result: dict[str, Any] = {
            "status": stopped,
            "failures": (
                []
                if stopped == "CANCELLED"
                else [
                    failure(
                        suite["id"],
                        suite["id"],
                        reason,
                        "harness",
                        "output.log",
                        "TIMEOUT",
                    )
                ]
            ),
            "reason": reason,
        }
    else:
        result = inspect_reports(suite, directory, reports, process.returncode)
    result.update(
        {
            "finishedAt": now(),
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "exitCode": process.returncode,
            "log": str((directory / "output.log").relative_to(store.directory(run_id))),
        }
    )
    result["artifacts"] = [
        str(path.relative_to(store.directory(run_id)))
        for path in sorted(directory.rglob("*"))
        if path.is_file()
    ]
    return result


def execute_run(store: RunStore, run_id: str):
    run = store.read(run_id)
    catalog = Catalog(Path(run["serviceRoot"]), Path(run["packageRoot"]))
    # Native runners share Maven reports and the local stack, so runs execute serially.
    workspace_lock = catalog.root / ".dev/ai-test-execution.lock"
    workspace_lock.parent.mkdir(exist_ok=True)
    with workspace_lock.open("a") as lock:
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if store.read(run_id)["cancelRequested"]:
                    with store.edit(run_id) as current:
                        current.update(status="CANCELLED", finishedAt=now())
                    return
                time.sleep(0.2)
        with store.edit(run_id) as current:
            if current["status"] in TERMINAL:
                return
            current["status"] = "RUNNING"
        result: dict[str, Any]
        failure_number = 0
        for index, task in enumerate(run["tasks"]):
            current = store.read(run_id)
            if current["cancelRequested"]:
                break
            gates = [t for t in current["tasks"][:index] if t["suite"].get("gate")]
            gate_failed = task["suite"].get("requiresCore") and (
                not gates or any(t["status"] != "PASS" for t in gates)
            )
            if gate_failed:
                result = {
                    "status": "NOT_RUN",
                    "reason": "The required core regression gate did not pass.",
                    "failures": [],
                }
            else:
                try:
                    if any(
                        digest(Path(source["path"])) != source["sha256"]
                        for source in run["sources"]
                    ):
                        raise ValueError(
                            "A test manifest changed after the run was created"
                        )
                    result = _execute(catalog, store, run_id, index)
                except Exception as exc:  # pylint: disable=broad-exception-caught
                    result = {
                        "status": "ERROR",
                        "reason": str(exc),
                        "failures": [
                            failure(
                                task["suite"]["id"],
                                task["suite"]["id"],
                                redact(str(exc)),
                                "harness",
                                "worker.log",
                                "RUNNER_ERROR",
                            )
                        ],
                    }
            for episode in result.get("failures", []):
                failure_number += 1
                episode["id"] = f"F{failure_number:03d}"
                episode["taskIndex"] = index
            with store.edit(run_id) as current:
                current["tasks"][index].update(result)
        with store.edit(run_id) as current:
            statuses = [t["status"] for t in current["tasks"]]
            if current["cancelRequested"]:
                status = "CANCELLED"
            else:
                status = next(
                    (s for s in ("ERROR", "FAIL", "BLOCKED") if s in statuses), "PASS"
                )
                if (
                    any(s in {"PENDING", "RUNNING", "NOT_RUN"} for s in statuses)
                    and status == "PASS"
                ):
                    status = "ERROR"
            current.update(status=status, finishedAt=now())
            current["summary"] = {
                status: statuses.count(status) for status in sorted(set(statuses))
            }


def markdown_report(run: dict) -> str:
    rows = [
        f"# {run['id']}",
        "",
        f"Status: {run['status']}",
        f"Profile: {run['profile']}",
        f"Service commit: {run['service']['commit']}",
        "",
        "| Suite | Status |",
        "| --- | --- |",
    ]
    rows.extend(f"| {t['suite']['id']} | {t['status']} |" for t in run["tasks"])
    for task in run["tasks"]:
        for episode in task.get("failures", []):
            case_id = episode["caseId"].replace("\n", " ").replace("\r", " ")
            rows.extend(
                [
                    "",
                    f"## {episode['id']}: {case_id}",
                    "",
                    *[
                        "    " + line
                        for line in episode["error"]["message"].splitlines()
                    ],
                    "",
                    f"Root cause: {episode['diagnosis']['status']}",
                    "",
                    *[
                        "    " + line
                        for line in episode["diagnosis"].get("summary", "").splitlines()
                    ],
                    f"Recovery: {episode['recovery']['status']}",
                    f"Recovery diagnosis: {episode['recovery']['diagnosis']['status']}",
                    "",
                    *[
                        "    " + line
                        for line in episode["recovery"]["diagnosis"]
                        .get("summary", "")
                        .splitlines()
                    ],
                    "",
                    *[
                        "    " + line
                        for line in json.dumps(
                            episode["recovery"]["attempts"], indent=2
                        ).splitlines()
                    ],
                ]
            )
    return "\n".join(rows) + "\n"
