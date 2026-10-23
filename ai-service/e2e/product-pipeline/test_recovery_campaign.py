import copy
import json
import unittest
import os
from pathlib import Path
import subprocess
import tempfile
import runpy
from unittest.mock import MagicMock, patch

import recovery_campaign as campaign


def evidence_fixture():
    ref = {"kind": "CHAIN_PLAN_GRAPH", "artifactId": "graph-2", "contentHash": "hash-2"}
    proposal = {
        "endpoints": [
            {"roleId": "entry", "semanticNodeId": "http", "externalRoute": False}
        ]
    }
    mutated = copy.deepcopy(proposal)
    mutated["endpoints"][0]["semanticNodeId"] = "absent"
    configured = {"triggers": [{"semanticNodeId": "http"}]}
    value = {
        "conversationId": "conversation",
        "runId": "run",
        "budget": {"grants": 0, "requests": 5},
        "checkpoint": {
            "runId": "run",
            "conversationId": "conversation",
            "sequence": 3,
            "boundary": "materialization-inputs-accepted",
            "graphRef": ref,
            "inputRefs": [ref],
        },
        "pipelineArtifacts": [
            {
                **ref,
                "compilationId": "run",
                "createdAt": 20,
                "payload": {
                    "nodes": [
                        {
                            "nodeId": "http",
                            "type": "http-trigger",
                            "properties": [
                                {"key": "contextPath", "value": "/greetings"},
                                {"key": "httpMethodRestrict", "value": "GET"},
                                {"key": "externalRoute", "value": "false"},
                                {"key": "accessControlType", "value": "RBAC"},
                                {"key": "roles", "value": '["qip-viewer"]'},
                            ],
                        },
                        {
                            "nodeId": "script",
                            "type": "script",
                            "properties": [
                                {"key": "script", "value": 'return "Hello world"'}
                            ],
                        },
                    ],
                    "edges": [{"fromNodeId": "http", "toNodeId": "script"}],
                },
            }
        ],
        "captureArtifacts": [
            {
                "kind": "CAPTURE_ATTEMPT_FEEDBACK",
                "compilationId": "separate-compilation",
                "payload": {
                    "proposal": {
                        "arguments": mutated,
                        "capabilityId": "trigger",
                        "toolName": "captureHttpTriggers",
                    },
                    "feedback": {"kind": "VALIDATION"},
                },
            },
            {"kind": "CONFIGURED_TRIGGER_SET", "payload": configured},
        ],
        "events": [
            {
                "type": "FAULT_APPLIED",
                "sequence": 1,
                "conversationId": "conversation",
                "capabilityId": "trigger",
                "original": proposal,
                "mutated": mutated,
            },
            {
                "type": "CAPTURE_ACCEPTED",
                "sequence": 2,
                "conversationId": "conversation",
                "capabilityId": "trigger",
                "source": "model-tool",
                "nativeAdmission": "configured-trigger-set",
                "proposal": proposal,
                "configuredTriggers": configured,
            },
        ],
        "interactions": [
            {"kind": "initial"},
            {
                "kind": "decision",
                "action": "approve",
                "revision": 3,
                "artifactType": "plan",
                "artifactHash": "hash",
            },
        ],
    }
    return json.loads(json.dumps(value))


class RecoveryContractTest(unittest.TestCase):
    def test_shared_process_stopping_changes_its_recorded_identity(self):
        container = {"Id": "shared", "RestartCount": 0,
                     "State": {"StartedAt": "unchanged", "Running": True}}
        running = campaign.process_identity(container)
        container["State"]["Running"] = False
        self.assertNotEqual(running, campaign.process_identity(container))

    def setUp(self):
        self.case = next(iter(campaign.load_cases(campaign.HERE / "test-fixtures/recovery/cases.json").values()))
        self.evidence = evidence_fixture()

    def outcome(self):
        return campaign.verdict(self.case, self.evidence)["outcome"]

    def test_named_script_checks_do_not_require_a_global_script_literal(self):
        del self.case["checks"]["scriptContains"]
        graph = self.evidence["pipelineArtifacts"][0]["payload"]
        graph["nodes"][1]["label"] = "Response"
        self.case["checks"]["scriptSnippets"] = [{"label": "Response", "contains": "Hello world"}]
        self.assertEqual([], campaign.graph_errors(self.case, graph))
        graph["nodes"][1]["properties"][0]["value"] = 'return "Wrong value"'
        self.assertIn("Required script value is absent from its owner", campaign.graph_errors(self.case, graph))

    def test_catalog_outgoing_check_uses_the_declared_trigger_type(self):
        for trigger_type, connected in [("http-trigger", True), ("async-api-trigger", True),
                                        ("async-api-trigger", False)]:
            with self.subTest(trigger=trigger_type, connected=connected), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                campaign.write_json(root / "chain.json", {"id": "chain"})
                campaign.write_json(root / "elements.json", [
                    {"id": "entry", "type": trigger_type, "properties": {}},
                    {"id": "reply", "type": "script", "properties": {}}])
                campaign.write_json(root / "dependencies.json", [{"from": "entry", "to": "reply"}] if connected else [])
                skeleton = {"triggerMustHaveOutgoing": True, "reachableFromTrigger": ["script"]}
                if trigger_type != "http-trigger":
                    skeleton["triggerType"] = trigger_type
                campaign.write_json(root / "scenario.json", {"fixture": {"catalog": {
                    "requiredTypes": [trigger_type, "script"], "skeleton": skeleton}}})
                (root / "curl").write_text(
                    "#!/usr/bin/env python3\nimport os,sys\nfrom pathlib import Path\n"
                    "suffix=sys.argv[-1].rstrip('/').split('/')[-1]\n"
                    "name=suffix if suffix in ['elements','dependencies'] else 'chain'\n"
                    "print((Path(os.environ['CATALOG_FIXTURE_DIR'])/(name+'.json')).read_text())\n")
                (root / "curl").chmod(0o755)
                checked = subprocess.run(["bash", str(campaign.HERE / "scripts/assert-catalog.sh"),
                    str(root / "scenario.json"), "fixture", "http://fixture", "chain"],
                    env={**os.environ, "PATH": str(root)+os.pathsep+os.environ["PATH"],
                         "CATALOG_FIXTURE_DIR": str(root)}, capture_output=True, text=True)
                self.assertEqual(connected, checked.returncode == 0, checked.stdout+checked.stderr)
                if not connected:
                    self.assertIn("trigger has no outgoing dependencies", checked.stderr)

    def test_tool_prohibition_allows_negative_text_and_tool_schemas(self):
        self.enhanced()
        self.case["checks"]["forbiddenTools"] = ["getApiHubDocument"]
        self.evidence["providerBoundary"][0]["request"].update(
            parameters={"tools": [{"name": "getApiHubDocument"}]}
        )
        self.evidence["providerBoundary"][0]["request"]["messages"].append(
            {"text": "Do not use APIHub."}
        )
        self.assertEqual("CHECKPOINT_PASS", self.outcome())

    def test_tool_prohibition_rejects_response_tool_calls(self):
        self.enhanced()
        self.case["checks"]["forbiddenTools"] = ["getApiHubDocument"]
        self.evidence["providerBoundary"][-1]["value"]["message"]["toolExecutionRequests"].append(
            {"name": "getApiHubDocument", "arguments": "{}"}
        )
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_tool_prohibition_rejects_completed_stream_tool_calls(self):
        self.enhanced()
        self.case["checks"]["forbiddenTools"] = ["getApiHubDocument"]
        self.evidence["providerBoundary"].append(
            {"kind": "tool-completed", "value": {"toolExecutionRequest": {"name": "getApiHubDocument"}}}
        )
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def enhanced(self):
        self.case.update(requireProviderEvidence=True)
        self.case["fault"]["kind"] = "http-exposure-type"
        fault = self.evidence["events"][0]
        fault.update(tool="captureHttpTriggers", parameter="capture", observedAt=20)
        fault["mutated"] = copy.deepcopy(fault["original"])
        fault["mutated"]["endpoints"][0]["externalRoute"] = "false"
        ref = {"kind": "CAPTURE_ATTEMPT_FEEDBACK", "artifactId": "rejected", "contentHash": "reject-hash"}
        issue = {"code": "INVALID_TYPE", "path": "/capture/endpoints/0/externalRoute", "message": "Wrong type"}
        self.evidence["captureArtifacts"] = [{**ref, "payload": {"feedback": {"issues": [issue]}}}]
        rejection = {"type": "CAPTURE_REJECTED", "tool": "captureHttpTriggers", "proposal": fault["mutated"],
                     "sequence": 2, "observedAt": 21, "rejectionRef": ref,
                     "nativeResult": {"accepted": False, "issues": [issue]}}
        accepted = self.evidence["events"][-1]
        accepted.update(tool="captureHttpTriggers", sequence=3)
        self.evidence["events"].insert(1, rejection)
        self.evidence["checkpoint"]["sequence"] = 4
        self.evidence["budget"]["requests"] = 1
        self.evidence["providerBoundary"] = [
            {"id": "request", "runId": "run", "at": 21.5, "context": {"runId": "run", "attemptId": "attempt"},
             "request": {"messages": [{"text": "INVALID_TYPE /capture/endpoints/0/externalRoute Wrong type"}]},
             "originalRequestHash": "original", "redactedRequestHash": "redacted"},
            {"requestId": "request", "kind": "response", "at": 22,
             "value": {"message": {"toolExecutionRequests": [{"name": "captureHttpTriggers", "id": "corrected",
                        "arguments": json.dumps({"capture": accepted["proposal"]})}]}}},
        ]

    def test_actual_provider_refusal_and_model_correction_are_required(self):
        self.enhanced()
        self.assertEqual("CHECKPOINT_PASS", self.outcome())
        self.evidence["providerBoundary"][0]["request"] = {"messages": [{"text": "No refusal delivered"}]}
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_a_request_prepared_before_the_refusal_cannot_prove_repair(self):
        self.enhanced()
        self.evidence["providerBoundary"][0]["at"] = 19
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_optional_capture_defaults_preserve_false_zero_and_explicit_string_values(self):
        self.assertEqual(campaign.capture_value({"operations": [{"nodeId": "reply"}], "loopRegions": [], "owner": None}),
                         campaign.capture_value({"operations": [{"nodeId": "reply"}]}))
        self.assertNotEqual(campaign.capture_value({"externalRoute": False, "count": 0, "value": "[]"}),
                            campaign.capture_value({}))

    def test_projection_without_a_corrected_model_call_cannot_pass(self):
        self.enhanced()
        self.evidence["providerBoundary"][1]["value"]["message"]["toolExecutionRequests"] = []
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_incomplete_provider_recording_cannot_pass(self):
        self.enhanced()
        self.evidence["budget"]["requests"] = 2
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_fault_cannot_change_undeclared_source_fields(self):
        self.enhanced()
        self.evidence["events"][0]["mutated"]["endpoints"][0]["roleId"] = "other-role"
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_full_readback_checks_directed_edges_and_each_bound_property(self):
        graph = self.evidence["pipelineArtifacts"][0]["payload"]
        snapshot_ref = {"kind": "CATALOG_CHAIN_SNAPSHOT", "artifactId": "catalog", "contentHash": "catalog-hash"}
        reconcile_ref = {"kind": "RECONCILE_RESULT", "artifactId": "reconcile", "contentHash": "reconcile-hash"}
        snapshot = {"elements": [{"elementId": node["nodeId"], "type": node["type"], "name": node.get("label"),
                                "properties": {prop["key"]: prop["value"] for prop in node["properties"]}}
                               for node in graph["nodes"]],
                    "dependencies": [{"fromElementId": "http", "toElementId": "script"}]}
        self.evidence["pipelineArtifacts"] += [
            {**snapshot_ref, "payload": snapshot}, {**reconcile_ref, "payload": {"matches": True}},
            {"kind": "MATERIALIZATION_RESULT", "artifactId": "result", "contentHash": "result-hash",
             "payload": {"completedPhase": "COMPLETE", "catalogSnapshotRef": snapshot_ref,
                         "reconcileResultRef": reconcile_ref,
                         "materializationMap": {"nodeIdToElementId": {"http": "http", "script": "script"}}}},
        ]
        self.assertEqual([], campaign.catalog_graph_errors(self.evidence, graph))
        snapshot["dependencies"] = [{"fromElementId": "script", "toElementId": "http"}]
        self.assertTrue(campaign.catalog_graph_errors(self.evidence, graph))

    def test_native_correction_and_consumer_admission_pass(self):
        self.assertEqual("CHECKPOINT_PASS", self.outcome())

    def test_held_out_topology_checks_actual_order(self):
        self.case["checks"]["exactTypes"].append("log")
        self.case["checks"]["edges"] = 2
        self.case["checks"]["edgeTypes"] = [["http-trigger", "log"], ["log", "script"]]
        graph = self.evidence["pipelineArtifacts"][0]["payload"]
        graph["nodes"].append({"nodeId": "log", "type": "log", "properties": []})
        graph["edges"] = [
            {"fromNodeId": "http", "toNodeId": "log"},
            {"fromNodeId": "log", "toNodeId": "script"},
        ]
        self.assertEqual("CHECKPOINT_PASS", self.outcome())
        graph["edges"][1] = {"fromNodeId": "script", "toNodeId": "log"}
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_schema_repair_needs_exact_native_rejection_and_later_admission(self):
        self.case["fault"]["kind"] = "http-exposure-type"
        fault = self.evidence["events"][0]
        fault["mutated"] = copy.deepcopy(fault["original"])
        fault["mutated"]["endpoints"][0]["externalRoute"] = "false"
        ref = {
            "kind": "CAPTURE_ATTEMPT_FEEDBACK",
            "artifactId": "reject",
            "contentHash": "reject-hash",
        }
        self.evidence["captureArtifacts"][0] = {
            **ref,
            "payload": {"feedback": {"summary": "Wrong type"}},
        }
        event = {
            "type": "CAPTURE_REJECTED",
            "conversationId": "conversation",
            "capabilityId": "trigger",
            "sequence": 2,
            "proposal": copy.deepcopy(fault["mutated"]),
            "rejectionRef": ref,
            "nativeResult": {
                "accepted": False,
                "issues": [
                    {
                        "code": "INVALID_TYPE",
                        "path": "/capture/endpoints/0/externalRoute",
                        "message": "Wrong type",
                    }
                ],
            },
        }
        self.evidence["events"].insert(1, event)
        self.evidence["events"][-1]["sequence"] = 3
        self.evidence["checkpoint"]["sequence"] = 4
        self.assertEqual("CHECKPOINT_PASS", self.outcome())
        event["rejectionRef"] = {**ref, "contentHash": "foreign"}
        self.assertEqual("RECOVERY_FAILURE", self.outcome())
        event["rejectionRef"] = ref
        self.evidence["events"][-1]["sequence"] = 1
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_a_different_artifact_kind_cannot_stand_in_for_the_graph(self):
        self.evidence["checkpoint"]["graphRef"]["kind"] = "USER_INPUT"
        self.evidence["checkpoint"]["inputRefs"][0]["kind"] = "USER_INPUT"
        self.evidence["pipelineArtifacts"][0]["kind"] = "USER_INPUT"
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_model_can_correct_another_bad_generated_field_too(self):
        for key in ("original", "mutated"):
            self.evidence["events"][0][key]["endpoints"][0]["externalRoute"] = True
        self.evidence["captureArtifacts"][0]["payload"]["proposal"]["arguments"][
            "endpoints"
        ][0]["externalRoute"] = True
        self.assertEqual("CHECKPOINT_PASS", self.outcome())

    def test_false_successes_do_not_pass(self):
        mutations = {
            "unchanged rejection": lambda e: e["events"][-1].update(
                proposal=e["events"][0]["mutated"]
            ),
            "wrong revision": lambda e: e["checkpoint"]["graphRef"].update(
                contentHash="foreign"
            ),
            "no continuation": lambda e: e["checkpoint"].update(sequence=1),
            "lost requirement": lambda e: e["pipelineArtifacts"][0]["payload"]["nodes"][
                0
            ]["properties"].clear(),
            "unused fault": lambda e: e.update(events=[]),
            "projection only": lambda e: e["events"][-1].update(source="projection"),
            "missing native rejection": lambda e: e.update(captureArtifacts=[]),
            "earlier failure": lambda e: e.update(earlierFailure=True),
            "hidden allowance": lambda e: e["budget"].update(grants=1),
            "retry assistance": lambda e: e["interactions"].append(
                {"kind": "decision", "action": "retry"}
            ),
            "unnecessary question": lambda e: e["interactions"].append(
                {"kind": "clarification", "text": "hint"}
            ),
            "catalog write": lambda e: e.update(catalogWritten=True),
            "missing accounting": lambda e: e.update(budget={}),
            "unbound approval": lambda e: e["interactions"][-1].pop("artifactHash"),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                self.evidence = copy.deepcopy(evidence_fixture())
                mutate(self.evidence)
                self.assertNotIn(self.outcome(), campaign.PASS)

    def test_completion_needs_readback(self):
        self.case["boundary"] = "completion"
        self.assertEqual("RECOVERY_FAILURE", self.outcome())
        self.evidence.update(currentState="CHAIN_MATERIALIZED", catalogVerified=True)
        self.assertEqual("COMPLETION_PASS", self.outcome())
        self.evidence["publicationFailure"] = "Catalog rejected the patch"
        self.evidence["strictFailure"] = {
            "reason": "Catalog failure needs manual recovery"
        }
        self.assertEqual("PUBLICATION_FAILURE", self.outcome())

    def test_archived_native_repeat_question_is_not_recovered(self):
        directory = campaign.HERE / "test-fixtures/recovery"
        case = next(
            iter(
                campaign.load_cases(directory / "missing-route-repeated.json").values()
            )
        )
        trace = campaign.read_json(directory / "missing-route-repeated-evidence.json")
        self.assertEqual("RECOVERY_FAILURE", campaign.verdict(case, trace)["outcome"])
        self.assertTrue(
            any(
                row["payload"]["text"] == "/greetings"
                for row in trace["pipelineArtifacts"]
            )
        )
        waits = [
            transition
            for transition in trace["transitions"]
            if transition["toStatus"] == "WAITING_FOR_INPUT"
            and "route" in transition["reason"]
        ]
        self.assertGreater(waits[-1]["toRevision"], waits[0]["toRevision"])
        self.assertEqual(waits[0]["reason"], waits[-1]["reason"])

    def test_clarification_does_not_require_artificial_rejection(self):
        self.case.update(
            mechanism="clarification",
            inputMode="missing-input",
            fault=None,
            clarification={"questionPattern": "route.*path", "answer": "/greetings"},
        )
        self.evidence.update(events=[], captureArtifacts=[])
        self.evidence["interactions"].append(
            {
                "kind": "clarification",
                "question": "Which route path?",
                "text": "/greetings",
                "stageId": "discovery",
                "questionRevision": 3,
            }
        )
        self.evidence["pipelineArtifacts"].append(
            {
                "kind": "USER_INPUT",
                "compilationId": "run",
                "artifactId": "answer",
                "contentHash": "answer-hash",
                "createdAt": 10,
                "payload": {"text": "/greetings", "targetStageId": "discovery"},
            }
        )
        self.evidence["runDocument"] = {
            "transitions": [
                {
                    "fromRevision": 3,
                    "fromStatus": "WAITING_FOR_INPUT",
                    "toStatus": "RUNNING",
                    "stageId": "discovery",
                }
            ]
        }
        self.assertEqual("CHECKPOINT_PASS", self.outcome())
        self.evidence["interactions"][-1]["question"] = "Which database?"
        self.assertEqual("RECOVERY_FAILURE", self.outcome())

    def test_setup_failure_is_reported_without_losing_the_attempt(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            campaign, "IsolatedInstance", side_effect=RuntimeError("unavailable")
        ):
            output = Path(temporary) / "attempt"
            result = campaign.run_case(self.case, output, "candidate")
            self.assertEqual("INFRASTRUCTURE_FAILURE", result["outcome"])
            self.assertEqual(result, campaign.read_json(output / "verdict.json"))
            self.assertTrue((output / "report.md").exists())

    def test_export_or_stop_failure_cannot_publish_a_passing_report(self):
        for failure in ("export", "stop"):
            with self.subTest(
                failure=failure
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "attempt"
                control = root / "control"
                control.mkdir()
                sample = evidence_fixture()
                campaign.write_json(control / "checkpoint.json", sample["checkpoint"])
                for event in sample["events"]:
                    campaign.write_json(
                        control / "events" / (str(event["sequence"]) + ".json"), event
                    )
                instance = MagicMock(
                    settings={"LLM_TIMEOUT": "180s"},
                    control=control,
                    url="http://fixture",
                )

                def start(*args):
                    (output / "interactions.jsonl").write_text(
                        "\n".join(json.dumps(item) for item in sample["interactions"])
                    )

                instance.start.side_effect = start
                instance.export.return_value = sample
                getattr(instance, failure).side_effect = RuntimeError(
                    "injected " + failure + " failure"
                )
                driver = MagicMock()
                driver.poll.return_value = 0
                with patch.object(
                    campaign, "IsolatedInstance", return_value=instance
                ), patch.object(
                    campaign, "inspect_container_image", return_value="image"
                ), patch.object(
                    campaign, "source_fingerprint", return_value={}
                ), patch.object(
                    campaign, "fetch_json", return_value={"currentState": "RUNNING"}
                ), patch.object(
                    campaign.subprocess, "Popen", return_value=driver
                ):
                    result = campaign.run_case(self.case, output, "candidate")
                self.assertEqual("INFRASTRUCTURE_FAILURE", result["outcome"])
                self.assertEqual(result, campaign.read_json(output / "verdict.json"))
                self.assertNotIn("CHECKPOINT_PASS", (output / "report.md").read_text())
                instance.close.assert_called_once()
                if failure == "export":
                    instance.stop.assert_called_once_with(retain=True)

    def test_observation_budget_uses_only_reachable_backoffs(self):
        base = {"LLM_TIMEOUT": "180s"}
        self.assertEqual(
            campaign.observation_seconds(base),
            campaign.observation_seconds(
                {**base, "LLM_TRANSIENT_RETRY_BACKOFF_SECONDS": "2,5,99999"}
            ),
        )
        self.assertGreater(
            campaign.observation_seconds(base), campaign.duration_seconds("PT15M")
        )

    def test_strict_runner_rejects_assistance_before_making_a_request(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scenario = copy.deepcopy(self.case["scenario"])
            scenario["discoveryAnswers"] = ["repair hint"]
            campaign.write_json(root / "scenario.json", {"strict": scenario})
            process = subprocess.run(
                [
                    "bash",
                    str(campaign.HERE / "run-product-scenario.sh"),
                    "--scenario",
                    "strict",
                    "--rep",
                    "1",
                    "--base-url",
                    "http://127.0.0.1:1",
                    "--evaluator-url",
                    "http://127.0.0.1:1",
                    "--report",
                    str(root / "report.json"),
                    "--scenarios-file",
                    str(root / "scenario.json"),
                    "--conversation-id",
                    "owned",
                    "--strict-recovery",
                ],
                capture_output=True,
                text=True,
                env={**os.environ, "PRODUCT_PIPELINE_STUB_MODE": "1"},
            )
            self.assertEqual(2, process.returncode)
            self.assertIn("refuses scripted repair assistance", process.stderr)

    def test_strict_runner_distinguishes_ready_draft_and_missing_author_input(self):
        fixtures = runpy.run_path(str(campaign.HERE / "test-runner-decisions.py"))
        for missing in (False, True):
            with self.subTest(
                missing=missing
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "curl").write_text(fixtures["CURL"])
                (root / "curl").chmod(0o755)
                (root / "sleep").write_text("#!/bin/sh\nexit 0\n")
                (root / "sleep").chmod(0o755)
                campaign.write_json(root / "turns.json", [])
                ready = fixtures["waiting"](
                    "requirement-discovery",
                    "WAITING_FOR_INPUT",
                    6,
                    ["continue-to-planning"],
                    reason="__GATE:requirement-draft-ready__Ready",
                )
                question = fixtures["waiting"](
                    "requirement-discovery",
                    "WAITING_FOR_INPUT",
                    7,
                    [],
                    reason="Which route path?",
                )
                question["decision"]["question"] = "Which route path?"
                campaign.write_json(
                    root / "states.json",
                    [
                        ready,
                        question,
                        {"evidence": {"currentState": "FAILED"}, "decision": {}},
                    ],
                )
                scenario = copy.deepcopy(self.case["scenario"])
                if missing:
                    scenario["clarification"] = {
                        "questionPattern": "route.*path",
                        "answer": "/greetings",
                    }
                campaign.write_json(root / "scenario.json", {"strict": scenario})
                subprocess.run(
                    [
                        "bash",
                        str(campaign.HERE / "run-product-scenario.sh"),
                        "--scenario",
                        "strict",
                        "--rep",
                        "1",
                        "--base-url",
                        "http://fixture",
                        "--evaluator-url",
                        "http://fixture",
                        "--report",
                        str(root / "report.json"),
                        "--scenarios-file",
                        str(root / "scenario.json"),
                        "--conversation-id",
                        "fixture",
                        "--strict-recovery",
                    ],
                    env={
                        **os.environ,
                        "PATH": str(root) + os.pathsep + os.environ["PATH"],
                        "RUNNER_FIXTURE": str(root),
                    },
                    capture_output=True,
                    text=True,
                    timeout=15,
                )
                turns = campaign.read_json(root / "turns.json")
                self.assertEqual("continue-to-planning", turns[1]["decision"]["action"])
                self.assertEqual(3 if missing else 2, len(turns))
                if missing:
                    self.assertEqual("/greetings", turns[2]["message"])
                else:
                    self.assertIn(
                        "Which route path?",
                        campaign.read_json(root / "strict-failure.json")["reason"],
                    )


class EvidenceProbeOutputTest(unittest.TestCase):
    def test_probe_creates_nested_output_parent_before_launching_java(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classpath = root / "ai-service/target/recovery-probe-classpath.txt"
            classpath.parent.mkdir(parents=True)
            classpath.write_text("fixture-classpath")
            instance = object.__new__(campaign.IsolatedInstance)
            instance.output = root
            instance.storage_url = "http://localhost:9000"
            output = root / "nested/evidence/snapshot.json"

            def write_probe(arguments, **kwargs):
                destination = Path(arguments[-1])
                self.assertEqual(output, destination)
                self.assertTrue(destination.parent.is_dir())
                destination.write_text('{"captured": true}')

            with patch.object(campaign, "ROOT", root), patch.object(
                campaign.subprocess, "run", side_effect=write_probe
            ) as invoke:
                self.assertEqual({"captured": True}, instance.export("conversation", output))
                invoke.assert_called_once()


class ElementPatchEvidenceTest(unittest.TestCase):
    def test_actual_messages_preserve_complete_schema_data(self):
        issue = {"code": "enum", "entityId": "element", "path": "/properties/options/mode",
                 "message": "Rejected mode", "details": {"rejectedValue": "invalid",
                 "constraint": {"enum": ["value-" + str(index) for index in range(90)]},
                 "propertySchema": {"if": {"properties": {"kind": {"const": "Map"}}},
                                    "then": {"required": ["mapping"]}}}}
        text = "Remaining validation issues (recheck every issue; preserve corrections):\n" + json.dumps([issue])
        for payload in (text, json.dumps(text)):
            self.assertIn(issue, campaign.delivered_capture_issues([{"text": payload}]))
        self.assertEqual([], campaign.delivered_capture_issues([{"text": text[:800]}]))

    def test_shared_constraints_resolve_to_the_exact_native_schema(self):
        constraint = {"enum": ["value-" + str(index) for index in range(160)]}
        native = [{"code": "enum", "path": "/properties/mode", "entityId": str(index),
                   "details": {"rejectedValue": "invalid", "constraint": constraint}} for index in range(4)]
        shared = copy.deepcopy(native)
        for issue in shared:
            issue["details"]["constraint"] = {"$ref": "#/constraints/c0"}
        packet = {"issues": shared, "constraints": {"c0": copy.deepcopy(constraint)}}
        marker = "Remaining validation issues (recheck every issue; preserve corrections):\n"
        self.assertEqual(native, campaign.delivered_capture_issues([{"text": marker + json.dumps(packet)}]))
        packet["constraints"]["c0"]["enum"].pop()
        self.assertNotEqual(native, campaign.delivered_capture_issues([{"text": marker + json.dumps(packet)}]))

    def test_element_fault_scope_preserves_other_fields(self):
        original = {"patchId": "same", "propertyPatches": [
            {"key": "logLevel", "targetNodeId": "element", "operation": "ADD", "scalarValue": "Info", "value": {}},
            {"key": "message", "targetNodeId": "element", "scalarValue": "retained"}]}
        mutated = copy.deepcopy(original)
        mutated["propertyPatches"][0]["scalarValue"] = "invalid"
        for field in ("arrayValue", "mapEntries", "mapValue"):
            mutated["propertyPatches"][0][field] = None
        parameters = {"propertyValues": {"logLevel": "invalid"}}
        self.assertEqual([], campaign.fault_scope_errors("element-property-values", original, mutated, parameters))
        mutated["propertyPatches"][1]["scalarValue"] = "destroyed"
        self.assertTrue(campaign.fault_scope_errors("element-property-values", original, mutated, parameters))


if __name__ == "__main__":
    unittest.main()
