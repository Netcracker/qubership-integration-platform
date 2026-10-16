#!/usr/bin/env python3
"""Exercise the live runner with advertised cards and no network or model calls."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

DIR = Path(__file__).resolve().parent

CURL = """#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ["RUNNER_FIXTURE"])
args = sys.argv[1:]
turns = json.loads((root / "turns.json").read_text())
if "-X" in args and args[args.index("-X") + 1] == "POST":
    payload = json.loads(pathlib.Path(args[args.index("-d") + 1][1:]).read_text())
    turns.append(payload)
    (root / "turns.json").write_text(json.dumps(turns))
    print('event: done\\ndata: {"conversationId":"fixture"}\\n')
else:
    url = next(arg for arg in args if arg.startswith("http://"))
    states = json.loads((root / "states.json").read_text())
    state = states[min(len(turns) - 1, len(states) - 1)]
    print(json.dumps(state["decision"] if url.endswith("/decision") else state["evidence"]))
"""


def waiting(
    stage, status, revision, actions, artifact=None, content_hash=None, reason=""
):
    return {
        "evidence": {
            "currentState": status,
            "runRevision": revision - 1,
            "attempts": [{"stageId": stage}],
            "transitions": [{"toStatus": status, "reason": reason}],
        },
        "decision": {
            "kind": "approve" if artifact else "clarify",
            "revision": revision,
            "actions": actions,
            "artifactType": artifact,
            "artifactHash": content_hash,
        },
    }


class RunnerDecisionTest(unittest.TestCase):
    def run_fixture(
        self,
        reopen=True,
        changed_plan=True,
        owner="design-planning",
        confirm_rebuild=True,
    ):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "curl").write_text(CURL)
            (root / "curl").chmod(0o755)
            (root / "sleep").write_text("#!/bin/sh\nexit 0\n")
            (root / "sleep").chmod(0o755)
            (root / "turns.json").write_text("[]")
            states = [
                waiting(
                    "requirement-discovery",
                    "WAITING_FOR_INPUT",
                    6,
                    ["yes", "no"],
                    reason="__GATE:ids-path-choice__Show IDS?",
                ),
                waiting(
                    "requirement-analysis",
                    "WAITING_FOR_APPROVAL",
                    9,
                    ["approve"],
                    "requirement-brief",
                    "brief-hash",
                ),
                waiting(
                    "design-input",
                    "WAITING_FOR_INPUT",
                    12,
                    ["approve"],
                    "IDS_DOCUMENT",
                    "ids-hash",
                ),
                waiting(
                    "design-planning",
                    "WAITING_FOR_APPROVAL",
                    18,
                    ["approve-and-create"],
                    "implementation-plan",
                    "rejected-plan-hash",
                ),
                waiting(
                    "design-execution",
                    "WAITING_FOR_INPUT",
                    21,
                    ["rebuild-plan", "stop-with-report"],
                    reason="__GATE:recovery-rebuild-plan__Regenerate the rejected plan.",
                ),
                waiting(
                    "design-planning",
                    "WAITING_FOR_APPROVAL",
                    24,
                    ["approve-and-create"],
                    "implementation-plan",
                    "repaired-plan-hash",
                ),
                {
                    "decision": None,
                    "evidence": {
                        "currentState": "CHAIN_MATERIALIZED",
                        "attempts": [
                            {
                                "stageId": "design-planning",
                                "outputs": [
                                    {
                                        "kind": "IMPLEMENTATION_PLAN",
                                        "contentHash": "rejected-plan-hash",
                                    }
                                ],
                            },
                            {
                                "stageId": "design-execution",
                                "failureEvidence": "E2E recovery fault: rejected plan",
                            },
                            {
                                "stageId": "design-planning",
                                "outputs": [
                                    {
                                        "kind": "IMPLEMENTATION_PLAN",
                                        "contentHash": (
                                            "repaired-plan-hash"
                                            if changed_plan
                                            else "rejected-plan-hash"
                                        ),
                                    }
                                ],
                            },
                        ],
                        "transitions": (
                            [{"reason": f"author-reopen:{owner}\u0000CONTRACT_SHAPE"}]
                            if reopen
                            else []
                        ),
                        "committedArtifactKinds": [
                            "MATERIALIZATION_RESULT",
                            "CATALOG_CHAIN_SNAPSHOT",
                            "RECONCILE_RESULT",
                        ],
                    },
                },
            ]
            states[4]["evidence"]["attempts"][0][
                "failureEvidence"
            ] = "E2E recovery fault: unknown planner owner"
            if not confirm_rebuild:
                del states[4]
            (root / "states.json").write_text(json.dumps(states))
            result = subprocess.run(
                [
                    "bash",
                    str(DIR / "run-product-scenario.sh"),
                    "--scenario",
                    "product-create-chain-recovery-revise-plan",
                    "--rep",
                    "1",
                    "--base-url",
                    "http://fixture",
                    "--evaluator-url",
                    "http://fixture",
                    "--report",
                    str(root / "report.json"),
                ],
                env={
                    **os.environ,
                    "PATH": f"{root}:{os.environ['PATH']}",
                    "RUNNER_FIXTURE": str(root),
                    "PRODUCT_PIPELINE_STUB_MODE": "0",
                },
                capture_output=True,
                check=False,
                text=True,
                timeout=20,
            )
            return (
                json.loads((root / "turns.json").read_text()),
                result.stderr,
                (root / "recovery.json").exists(),
            )

    def test_answers_early_ids_and_both_approval_cards_with_their_current_metadata(
        self,
    ):
        turns, error, recovery = self.run_fixture()
        self.assertEqual(
            [
                {"action": "yes", "revision": 6},
                {
                    "action": "approve",
                    "revision": 9,
                    "artifactType": "requirement-brief",
                    "artifactHash": "brief-hash",
                },
                {
                    "action": "approve",
                    "revision": 12,
                    "artifactType": "IDS_DOCUMENT",
                    "artifactHash": "ids-hash",
                },
                {
                    "action": "approve-and-create",
                    "revision": 18,
                    "artifactType": "implementation-plan",
                    "artifactHash": "rejected-plan-hash",
                },
                {"action": "rebuild-plan", "revision": 21},
                {
                    "action": "approve-and-create",
                    "revision": 24,
                    "artifactType": "implementation-plan",
                    "artifactHash": "repaired-plan-hash",
                },
            ],
            [turn.get("decision") for turn in turns[1:]],
            error,
        )
        self.assertTrue(all(turn["message"] == "" for turn in turns[1:]))
        self.assertTrue(recovery, error)

    def test_materialization_without_reopening_design_planning_still_fails(self):
        _, error, recovery = self.run_fixture(reopen=False)
        self.assertIn(
            "does not prove every automatic recovery and materialization", error
        )
        self.assertFalse(recovery)

    def test_reopening_without_changing_the_rejected_plan_still_fails(self):
        _, error, recovery = self.run_fixture(changed_plan=False)
        self.assertIn(
            "does not prove every automatic recovery and materialization", error
        )
        self.assertFalse(recovery)

    def test_reopening_design_execution_does_not_count_as_plan_recovery(self):
        _, error, recovery = self.run_fixture(owner="design-execution")
        self.assertIn(
            "does not prove every automatic recovery and materialization", error
        )
        self.assertFalse(recovery)

    def test_author_reopen_requires_the_runner_to_confirm_the_rebuild_card(self):
        _, error, recovery = self.run_fixture(confirm_rebuild=False)
        self.assertIn(
            "does not prove every automatic recovery and materialization", error
        )
        self.assertFalse(recovery)


if __name__ == "__main__":
    unittest.main()
