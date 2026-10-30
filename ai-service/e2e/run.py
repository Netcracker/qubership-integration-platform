#!/usr/bin/env python3
"""One entry point for agent, terminal, and browser test runs."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from test_system.catalog import Catalog
from test_system.runs import (
    RunStore,
    create_run,
    execute_run,
    markdown_report,
    now,
    start_worker,
)
from test_system.server import serve


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[2]
    parser.add_argument("--root", type=Path, default=root)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--store", type=Path, default=root / ".dev/ai-test-runs")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("catalog")
    for action in ("plan", "start", "run"):
        command = sub.add_parser(action)
        command.add_argument("profile")
        command.add_argument("--suite", action="append")
        command.add_argument("--base-url", default="http://localhost:8094")
        command.add_argument("--evaluator-url", default="http://localhost:8100")
        command.add_argument("--allow-llm", action="store_true")
        command.add_argument("--parent")
        command.add_argument("--model", default="unspecified")
        command.add_argument("--image", default="unspecified")
    sub.add_parser("list")
    for action in ("status", "cancel", "report", "_worker"):
        command = sub.add_parser(action)
        command.add_argument("run_id")
    command = sub.add_parser("diagnose")
    command.add_argument("run_id")
    command.add_argument("failure_id")
    command.add_argument("target", choices=("failure", "recovery"))
    command.add_argument("--file", type=Path, required=True)
    command = sub.add_parser("serve")
    command.add_argument("--port", type=int, default=8110)
    args = parser.parse_args()
    store = RunStore(args.store)
    if args.action == "_worker":
        try:
            execute_run(store, args.run_id)
        except Exception as exc:
            with store.edit(args.run_id) as run:
                run.update(
                    status="ERROR",
                    finishedAt=now(),
                    reason="Worker failure: " + str(exc),
                )
            raise
        return 0
    if args.action in {"list", "status", "cancel", "report", "diagnose"}:
        if args.action == "list":
            result = store.list()
        elif args.action == "cancel":
            result = store.cancel(args.run_id)
        elif args.action == "diagnose":
            result = store.diagnose(
                args.run_id,
                args.failure_id,
                args.target,
                json.loads(args.file.read_text()),
            )
        else:
            result = store.read(args.run_id)
        if args.action == "report":
            print(markdown_report(result), end="")
        else:
            print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    catalog = Catalog(args.root, args.package)
    if args.action == "serve":
        serve(catalog, store, Path(__file__).resolve(), args.port)
        return 0
    if args.action == "catalog":
        result = catalog.describe()
    else:
        settings = {
            "base_url": args.base_url,
            "evaluator_url": args.evaluator_url,
            "model": args.model,
            "image": args.image,
        }
        if args.action == "plan":
            result = catalog.plan(args.profile, args.suite, settings)
        else:
            result = create_run(
                catalog,
                store,
                args.profile,
                args.suite,
                settings,
                args.allow_llm,
                args.parent,
            )
            if args.action == "start":
                result = start_worker(store, result["id"], Path(__file__).resolve())
            elif result["status"] != "BLOCKED":
                execute_run(store, result["id"])
                result = store.read(result["id"])
            result["uiPath"] = "/#" + result["id"]
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 1 if args.action == "run" and result["status"] != "PASS" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError) as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        raise SystemExit(2) from exc
