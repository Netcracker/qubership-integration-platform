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
if any(arg.endswith("/storage/objects") for arg in args):
    print(json.dumps({"objectKey": "orders.yaml"}))
    sys.exit(0)
if "-X" in args and args[args.index("-X") + 1] == "POST":
    payload = json.loads(pathlib.Path(args[args.index("-d") + 1][1:]).read_text())
    turns.append(payload)
    (root / "turns.json").write_text(json.dumps(turns))
    print("event: done\\ndata: fixture\\n")
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


class RecoveryFaultCountTest(unittest.TestCase):
    def test_sequential_recovery_uses_each_faults_corresponding_owner(self):
        runner = (DIR / "run-product-scenario.sh").read_text()
        expression = runner.split('--argjson minimumFaults "${recovery_minimum_faults}" \'', 1)[1].split("' <<<", 1)[0]
        evidence = {
            "attempts": [
                {"stageId": "design-input", "runRevision": 5, "outcome": "RUNNING",
                 "failureEvidence": json.dumps({"stageErrorContext": "E2E recovery fault",
                                                "stageErrorFailedStageId": "design-execution",
                                                "recoveryProducerInvocation": json.dumps({"attemptId": "failed-execution"})})},
                {"stageId": "design-execution", "runRevision": 9, "outcome": "RUNNING",
                 "failureEvidence": json.dumps({"stageErrorContext": "E2E recovery fault",
                                                "stageErrorFailedStageId": "materialization",
                                                "recoveryProducerInvocation": json.dumps({"attemptId": "failed-materialization"})})}],
            "transitions": [
                {"toRevision": 5, "reason": "automatic-reopen:design-input\0cause"},
                {"toRevision": 9, "reason": "automatic-reopen:design-execution\0cause"}],
            "committedArtifactKinds": ["MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT"],
        }
        for missing_second_reopen in (False, True):
            with self.subTest(missing_second_reopen=missing_second_reopen):
                document = {**evidence, "transitions": evidence["transitions"][:1] if missing_second_reopen
                            else evidence["transitions"]}
                command = ["jq", "-e", "--argjson", "faults", '["design-execution","materialization"]',
                           "--argjson", "owners", '["design-input","design-execution"]',
                           "--argjson", "rebuilt", "0", "--argjson", "minimumFaults", "2", expression]
                result = subprocess.run(command, input=json.dumps(document), text=True, capture_output=True)
                self.assertEqual(not missing_second_reopen, result.returncode == 0, result.stderr)

    def test_causal_reopen_counts_the_failed_invocation_once_across_snapshots(self):
        runner = (DIR / "run-product-scenario.sh").read_text()
        expression = runner.split('--argjson minimumFaults "${recovery_minimum_faults}" \'', 1)[1].split("' <<<", 1)[0]
        failure = json.dumps({"stageErrorContext": "E2E recovery fault: rejected graph",
                              "stageErrorFailedStageId": "design-execution",
                              "recoveryProducerInvocation": json.dumps({"attemptId": "producer-1"})})
        evidence = {
            "attempts": [{"attemptId": "snapshot-1", "stageId": "design-input", "runRevision": 5,
                          "outcome": "RUNNING", "failureEvidence": failure},
                         {"attemptId": "snapshot-2", "stageId": "design-planning", "runRevision": 7,
                          "outcome": "SUCCEEDED", "failureEvidence": failure}],
            "transitions": [{"toRevision": 5, "reason": "automatic-reopen:design-input\0cause"}],
            "committedArtifactKinds": ["MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT"],
        }
        unproven = {**evidence, "attempts": [{**evidence["attempts"][0], "failureEvidence": json.dumps({
            "stageErrorContext": "E2E recovery fault: rejected graph", "stageErrorFailedStageId": "design-execution"})}]}
        failed = {"attemptId": "producer-1", "stageId": "design-execution", "outcome": "FAILED",
                  "failureEvidence": "E2E recovery fault: rejected graph"}
        mixed = {**unproven, "attempts": [failed, *unproven["attempts"]]}
        cases = [("one-causal-failure", evidence, 1, True),
                 ("snapshots-are-not-two-failures", evidence, 2, False),
                 ("unproven-snapshot-cannot-establish-a-fault", unproven, 1, False),
                 ("failed-and-unproven-snapshot-count-once", mixed, 1, True),
                 ("failed-and-unproven-snapshot-are-not-two-faults", mixed, 2, False),
                 ("failed-and-proven-snapshot-are-not-two-faults",
                  {**evidence, "attempts": [failed, *evidence["attempts"]]}, 2, False),
                 ("marker-without-reopen", {**evidence, "transitions": []}, 1, False),
                 ("unrelated-reopen", {**evidence, "transitions": [
                     {"toRevision": 4, "reason": "automatic-reopen:design-input\0cause"}]}, 1, False)]
        for name, document, minimum, expected in cases:
            with self.subTest(case=name):
                command = ["jq", "-e", "--argjson", "faults", '["design-execution"]',
                           "--argjson", "owners", '["design-input"]', "--argjson", "rebuilt", "0",
                           "--argjson", "minimumFaults", str(minimum), expression]
                result = subprocess.run(command, input=json.dumps(document), text=True, capture_output=True)
                self.assertEqual(expected, result.returncode == 0, result.stderr)

    def test_automatic_recovery_requires_both_injected_faults(self):
        runner = (DIR / "run-product-scenario.sh").read_text()
        expression = runner.split('--argjson minimumFaults "${recovery_minimum_faults}" \'', 1)[1].split("' <<<", 1)[0]
        fault = {"attemptId": "failed-1", "stageId": "design-execution", "outcome": "FAILED", "failureEvidence": "E2E recovery fault"}
        evidence = {
            "attempts": [fault],
            "transitions": [{"reason": "automatic-reopen:design-planning\0cause"}],
            "committedArtifactKinds": ["MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT"],
        }
        evidence["attempts"].extend([
            {"outputs": [{"kind": "IMPLEMENTATION_PLAN", "contentHash": "before"}]},
            {"outputs": [{"kind": "IMPLEMENTATION_PLAN", "contentHash": "after"}]},
        ])
        command = ["jq", "-e", "--argjson", "faults", '["design-execution"]',
                   "--argjson", "owners", '["design-planning"]', "--argjson", "rebuilt", "0",
                   "--argjson", "minimumFaults", "2", expression]
        for second_outcome in ("RUNNING", "FAILED"):
            with self.subTest(second_outcome=second_outcome):
                evidence["attempts"] = [item for item in evidence["attempts"] if "failureEvidence" not in item]
                evidence["attempts"].extend([fault, {**fault, "attemptId": "attempt-2", "outcome": second_outcome}])
                result = subprocess.run(command, input=json.dumps(evidence), text=True, capture_output=True)
                self.assertEqual(second_outcome == "FAILED", result.returncode == 0, result.stderr)

    def test_same_stage_recovery_requires_success_after_failed_fault(self):
        runner = (DIR / "run-product-scenario.sh").read_text()
        expression = runner.split('--argjson minimumFaults "${recovery_minimum_faults}" \'', 1)[1].split("' <<<", 1)[0]
        failed = {"stageId": "design-execution", "outcome": "FAILED", "failureEvidence": "E2E recovery fault"}
        succeeded = {"stageId": "design-execution", "outcome": "SUCCEEDED"}
        evidence = {"transitions": [], "committedArtifactKinds": [
            "MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT"]}
        command = ["jq", "-e", "--argjson", "faults", '["design-execution"]',
                   "--argjson", "owners", '["design-execution"]', "--argjson", "rebuilt", "0",
                   "--argjson", "minimumFaults", "1", expression]
        cases = [([failed, succeeded], [], True), ([succeeded, failed], [], False),
                 ([failed, succeeded], [{"reason": "author-reopen:design-execution"}], False)]
        for attempts, transitions, expected in cases:
            with self.subTest(attempts=attempts, transitions=transitions):
                result = subprocess.run(command, input=json.dumps({**evidence, "attempts": attempts,
                                        "transitions": transitions}), text=True, capture_output=True)
                self.assertEqual(expected, result.returncode == 0, result.stderr)


class RunnerDecisionTest(unittest.TestCase):
    def run_fixture(
        self,
        reopen=True,
        changed_plan=True,
        owner="design-planning",
        confirm_rebuild=True,
        initial_cards=None,
        scenario=None,
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
                    "requirement-discovery",
                    "WAITING_FOR_INPUT",
                    7,
                    ["continue-to-planning"],
                    reason="__GATE:requirement-draft-ready__Requirements draft saved. Continue to planning when ready.",
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
                                "outcome": "FAILED",
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
            states[5]["evidence"]["attempts"][0][
                "failureEvidence"
            ] = "E2E recovery fault: unknown planner owner"
            if not confirm_rebuild:
                del states[5]
            if initial_cards is not None:
                states = [
                    *initial_cards,
                    {"decision": None, "evidence": {"currentState": "FAILED"}},
                ]
            (root / "states.json").write_text(json.dumps(states))
            scenario_args = []
            if scenario is not None:
                (root / "orders.yaml").write_text("openapi: 3.0.3")
                corpus = {"product-create-chain-recovery-revise-plan": {
                    "prompt": "Create the order integration.",
                    "pipeline": "create-chain-v1",
                    "profileId": "create-chain",
                    "profileVersion": "2",
                    "terminalState": "CHAIN_MATERIALIZED",
                    "retainCatalogChain": True,
                    **scenario,
                }}
                (root / "scenarios.json").write_text(json.dumps(corpus))
                scenario_args = ["--scenarios-file", str(root / "scenarios.json")]
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
                    *scenario_args,
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

    def test_uploaded_specification_can_wait_for_a_pinned_schema_answer(self):
        clarification = waiting("requirement-discovery", "WAITING_FOR_INPUT", 6, [],
                                reason="__GATE:stage-clarification__Provide the request schema.")
        clarification["decision"]["id"] = "request-schema-offer"
        import_card = waiting("uploaded-spec-import", "WAITING_FOR_INPUT", 8,
                             ["import-specification"], "uploaded-specs-import-proposal", "upload-hash")

        turns, _, _ = self.run_fixture(
            initial_cards=[clarification, import_card],
            scenario={"uploadedSpec": {"fixture": "orders.yaml", "systemType": "EXTERNAL"},
                      "discoveryAnswers": ['{"type":"object"}']},
        )

        self.assertEqual(
            [
                {"message": "Create the order integration.", "attachmentObjectKeys": ["orders.yaml"]},
                {"message": '{"type":"object"}', "conversationId": "fixture",
                 "clarificationOfferId": "request-schema-offer"},
                {"message": "", "conversationId": "fixture", "decision": {
                    "action": "import-specification", "artifactType": "uploaded-specs-import-proposal",
                    "artifactHash": "upload-hash", "revision": 8, "specSystemTypes": {"orders.yaml": "EXTERNAL"},
                }},
            ],
            turns,
        )

    def test_unchanged_clarification_stops_before_spending_another_scripted_answer(self):
        clarification = waiting("requirement-discovery", "WAITING_FOR_INPUT", 6, [],
                                reason="__GATE:stage-clarification__Provide the request schema.")
        clarification["decision"]["id"] = "request-schema-offer"

        turns, error, _ = self.run_fixture(
            initial_cards=[clarification, clarification],
            scenario={"discoveryAnswers": ["first answer", "spare answer"]},
        )

        self.assertEqual(["Create the order integration.", "first answer"], [turn["message"] for turn in turns])
        self.assertIn("requirement clarification did not advance", error)

    def test_answers_early_ids_and_both_approval_cards_with_their_current_metadata(
        self,
    ):
        turns, error, recovery = self.run_fixture()
        self.assertEqual(
            [
                {"action": "yes", "revision": 6},
                {"action": "continue-to-planning", "revision": 7},
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

    def test_design_recovery_cards_do_not_receive_an_ids_answer(self):
        for recovery_card in (True, False):
            with self.subTest(recovery_card=recovery_card):
                card = waiting(
                    "design-input",
                    "WAITING_FOR_INPUT",
                    42,
                    ["retry-creation", "stop-with-report"] if recovery_card else [],
                    reason=(
                        "__GATE:recovery-rebuild-design__CAPTURE_VALIDATION: ELEMENT_INTENT_ORDER"
                        if recovery_card else "Show IDS?"
                    ),
                )
                if recovery_card:
                    card["decision"]["recovery"] = {"category": "design-artifact-defect"}
                cards = [card]
                if not recovery_card:
                    cards.append(waiting(
                        "design-input", "WAITING_FOR_INPUT", 43, [],
                        reason="How should edge data be mapped?",
                    ))
                turns, error, _ = self.run_fixture(initial_cards=cards)
                self.assertEqual(1 if recovery_card else 3, len(turns), error)
                if recovery_card:
                    self.assertIn("preserving pending card", error)
                else:
                    self.assertEqual("Generate full IDS", turns[1]["message"])
                    self.assertEqual(
                        "Pass the data through unchanged. No field mapping and no transformation on any edge.",
                        turns[2]["message"],
                    )

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
