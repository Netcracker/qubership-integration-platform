#!/usr/bin/env python3
"""Run isolated recovery cases and judge native evidence without model grading."""

import argparse
import copy
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


HERE = Path(__file__).resolve().parent
DEFAULT_CASES = HERE / "fixtures/recovery/cases.json"
ROOT = HERE.parents[2]
COMPOSE = ROOT / "infrastructure/docker/docker-compose.ai-recovery.yml"
PASS = {"CHECKPOINT_PASS", "COMPLETION_PASS"}
ALLOWED_ACTIONS = {
    "approve",
    "approve-and-create",
    "create-chain",
    "yes",
    "no",
    "continue-to-planning",
}


def read_json(path):
    return json.loads(Path(path).read_text())


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def load_cases(path=DEFAULT_CASES):
    manifest = read_json(path)
    if manifest.get("schemaVersion") != 1 or not isinstance(
        manifest.get("cases"), list
    ):
        raise ValueError("Expected schemaVersion=1 and a cases array")
    cases = {}
    for case in manifest["cases"]:
        if not case.get("id") or case["id"] in cases:
            raise ValueError("Case identities must be nonempty and unique")
        if case.get("boundary") not in {"checkpoint", "completion"}:
            raise ValueError("Unknown case boundary")
        if case.get("inputMode") not in {"complete", "missing-input"}:
            raise ValueError("Unknown input mode")
        if case.get("mechanism") not in {"model-correction", "clarification"}:
            raise ValueError("Unknown recovery mechanism")
        scenario = case["scenario"]
        if any(
            scenario.get(key)
            for key in ("discoveryAnswers", "designInputAnswers", "recovery")
        ):
            raise ValueError("Strict cases cannot contain scripted repair assistance")
        if case["mechanism"] == "clarification":
            if (
                case["inputMode"] != "missing-input"
                or not case.get("clarification")
                or case.get("fault")
            ):
                raise ValueError(
                    "Clarification controls need missing input and no synthetic fault"
                )
        elif not case.get("fault") or case["fault"].get("applications") != (
            2 if case["fault"].get("kind") == "http-distinct-fields" else 1
        ):
            raise ValueError("Cases require the declared finite fault count")
        elif case["fault"].get("kind") not in {
            "http-semantic-reference",
            "http-exposure-type",
            "http-distinct-fields",
            "semantic-required-node",
            "requirement-literal-binding",
            "element-property-values",
        }:
            raise ValueError("Unsupported pilot fault")
        cases[case["id"]] = case
    return cases


def graph_errors(case, graph):
    errors = []
    nodes = graph.get("nodes", [])
    expected = case["checks"]
    if sorted(node.get("type", "") for node in nodes) != sorted(expected["exactTypes"]):
        errors.append("Graph element types differ from the case")
    for check in expected["properties"]:
        matches = [node for node in nodes if node.get("type") == check["type"]
                   and (not check.get("label") or node.get("label") == check["label"])]
        if len(matches) != 1:
            errors.append("Expected one " + check["type"])
            continue
        properties = {
            prop["key"]: prop.get("value") for prop in matches[0].get("properties", [])
        }
        if properties.get(check["key"]) != check["equals"]:
            errors.append("Lost requirement: " + check["key"])
    scripts = [node for node in nodes if node.get("type") == "script"]
    if expected.get("scriptContains") and not any(
        expected["scriptContains"] in str(node.get("properties", []))
        for node in scripts
    ):
        errors.append("Script does not preserve the required response")
    for check in expected.get("scriptSnippets", []):
        matching = [node for node in scripts if node.get("label") == check["label"]]
        if len(matching) != 1 or not any(prop.get("key") == "script" and check["contains"] in prop.get("value", "")
                                         for prop in matching[0].get("properties", [])):
            errors.append("Required script value is absent from its owner")
    if expected.get("namedEdges") is not None:
        labels = {node["nodeId"]: node.get("label") for node in nodes}
        actual = sorted([labels.get(edge.get("fromNodeId")), labels.get(edge.get("toNodeId"))]
                        for edge in graph.get("edges", []))
        if actual != sorted(expected["namedEdges"]):
            errors.append("Directed edges differ from the named author steps")
    node_ids = {node.get("nodeId") for node in nodes}
    edges = graph.get("edges", [])
    if len(edges) != expected["edges"]:
        errors.append("Unexpected execution edge count")
    for edge in edges:
        if (
            edge.get("fromNodeId") not in node_ids
            or edge.get("toNodeId") not in node_ids
        ):
            errors.append("Execution edge references an absent node")
    triggers = {node["nodeId"] for node in nodes if node.get("type") == "http-trigger"}
    script_ids = {node["nodeId"] for node in scripts}
    if expected.get("edgeTypes") is not None:
        types = {node["nodeId"]: node["type"] for node in nodes}
        actual = sorted(
            [types.get(edge.get("fromNodeId")), types.get(edge.get("toNodeId"))]
            for edge in edges
            if edge.get("fromNodeId") in types and edge.get("toNodeId") in types
        )
        if actual != sorted(expected["edgeTypes"]):
            errors.append("Execution edges differ from the declared topology")
    elif not any(
        edge.get("fromNodeId") in triggers and edge.get("toNodeId") in script_ids
        for edge in edges
    ):
        errors.append("HTTP trigger does not lead to the script")
    return errors


def model_boundary_errors(case, evidence):
    records = evidence.get("providerBoundary", [])
    requests = {row.get("id"): row for row in records if "request" in row
                and row.get("runId") == evidence.get("runId")}
    outcomes = {row.get("requestId") for row in records if row.get("kind") in {"response", "error"}}
    if len(requests) != evidence.get("budget", {}).get("requests") or set(requests) != outcomes.intersection(requests):
        return ["Required provider request/outcome recording is incomplete"]
    for row in requests.values():
        context = row.get("context", {})
        if context.get("runId") != evidence.get("runId") or not context.get("attemptId"):
            return ["Provider request lacks the actual run/stage/attempt binding"]
        if not row.get("originalRequestHash") or not row.get("redactedRequestHash"):
            return ["Provider request hashes are missing"]
    return []


def fault_scope_errors(kind, original, mutated, parameters=None):
    expected = copy.deepcopy(original)
    if kind in {"http-exposure-type", "http-distinct-fields"}:
        if mutated.get("endpoints", [{}])[0].get("semanticNodeId") is False:
            expected["endpoints"][0]["semanticNodeId"] = False
        else:
            expected["endpoints"][0]["externalRoute"] = str(expected["endpoints"][0]["externalRoute"]).lower()
    elif kind == "element-property-values":
        values = (parameters or {}).get("propertyValues", {})
        if not values:
            return ["Element property fault has no declared values"]
        for key, value in values.items():
            targets = [row for row in expected.get("propertyPatches", []) if row.get("key") == key and row.get("operation") != "REMOVE"]
            if not targets:
                return ["Element property fault has no matching patch field"]
            target = targets[0]
            for field in ("arrayValue", "mapEntries", "mapValue"):
                target[field] = None
            target["value"] = {}
            target["scalarValue"] = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
    elif kind == "semantic-required-node":
        variants = [original["operations"][:index] + original["operations"][index + 1:]
                    for index in range(len(original.get("operations", [])))]
        if mutated.get("operations") not in variants:
            return ["Semantic fault must omit exactly one processing node"]
        expected["operations"] = mutated["operations"]
    elif kind == "requirement-literal-binding":
        changed = 0
        for before, after in zip(expected["facts"], mutated.get("facts", [])):
            if before != after and isinstance(before.get("elementIntent"), dict):
                changed += 1
                intent = before["elementIntent"]
                values = after.get("elementIntent", {}).get("exactValues", [])
                if not intent.get("exactValues") and (len(values) != 1 or not any(
                    row.get("value") == values[0] and values[0] in before.get("text", "")
                    for row in intent.get("requestedProperties", []))):
                    return ["Fault invented a literal instead of retaining supplied data"]
                intent["requestedProperties"] = []
                if not intent.get("exactValues"):
                    intent["exactValues"] = values
        if changed != 1:
            return ["Literal fault must affect one declared owner"]
    if expected != mutated:
        return ["Fault changed fields outside its declared mutation"]
    return []


def catalog_graph_errors(evidence, graph):
    revisions = evidence.get("pipelineArtifacts", [])
    by_ref = {(row["kind"], row["artifactId"], row["contentHash"]): row for row in revisions}
    completed = [row["payload"] for row in revisions if row["kind"] == "MATERIALIZATION_RESULT"
                 and row["payload"].get("completedPhase") == "COMPLETE"]
    if not completed:
        return ["No completed native materialization"]
    result = completed[-1]
    def payload(reference):
        reference = reference or {}
        return by_ref.get(tuple(reference.get(key) for key in ("kind", "artifactId", "contentHash")), {}).get("payload", {})
    snapshot = payload(result.get("catalogSnapshotRef"))
    reconcile = payload(result.get("reconcileResultRef"))
    if reconcile.get("matches") is not True:
        return ["Native catalog reconciliation did not pass"]
    elements = {row["elementId"]: row for row in snapshot.get("elements", [])}
    mapping = result.get("materializationMap", {}).get("nodeIdToElementId", {})
    if len(elements) != len(graph.get("nodes", [])):
        return ["Catalog element count differs from the admitted graph"]
    def decoded(value):
        if not isinstance(value, str):
            return value
        try:
            return json.loads(value)
        except ValueError:
            return value
    for node in graph.get("nodes", []):
        actual = elements.get(mapping.get(node["nodeId"]), {})
        if actual.get("type") != node.get("type") or actual.get("name") != node.get("label"):
            return ["Catalog element identity, type, or name differs from the graph"]
        if any(decoded(actual.get("properties", {}).get(prop["key"])) != decoded(prop.get("value"))
               for prop in node.get("properties", [])):
            return ["Catalog properties differ from the admitted graph"]
    expected_edges = sorted((mapping.get(edge["fromNodeId"]), mapping.get(edge["toNodeId"]))
                            for edge in graph.get("edges", []))
    actual_edges = sorted((row["fromElementId"], row["toElementId"]) for row in snapshot.get("dependencies", []))
    if actual_edges != expected_edges:
        return ["Catalog directed topology differs from the admitted graph"]
    return []


def capture_value(value):
    # Capture records normalize absent optional lists to empty lists and nullable members to null.
    if isinstance(value, dict):
        return {key: capture_value(child) for key, child in value.items() if child is not None and child != [] and child != {}}
    if isinstance(value, list):
        return [capture_value(child) for child in value]
    return value


def text_fragments(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from text_fragments(child)
    elif isinstance(value, list):
        for child in value:
            yield from text_fragments(child)
    elif isinstance(value, str):
        yield value
        try:
            decoded = json.loads(value)
        except ValueError:
            return
        if decoded != value:
            yield from text_fragments(decoded)


def delivered_capture_issues(messages):
    """Read the structured repair payload from actual provider messages, including quoted tool results."""
    delivered = []
    marker = "Remaining validation issues (recheck every issue; preserve corrections):"
    for fragment in text_fragments(messages):
        text = fragment
        for _ in range(2):
            try:
                decoded = json.loads(text)
            except (ValueError, TypeError):
                break
            if not isinstance(decoded, str):
                break
            text = decoded
        start = text.find(marker)
        if start < 0:
            continue
        try:
            issues, _ = json.JSONDecoder().raw_decode(text[start + len(marker):].lstrip())
        except ValueError:
            continue
        if isinstance(issues, dict):
            constraints = issues.get("constraints", {})
            issues = copy.deepcopy(issues.get("issues", []))
            if not isinstance(issues, list) or not isinstance(constraints, dict):
                continue
            for issue in issues:
                if not isinstance(issue, dict) or not isinstance(issue.get("details", {}), dict):
                    continue
                details = issue.get("details", {})
                reference = details.get("constraint", {}).get("$ref") if isinstance(details.get("constraint"), dict) else None
                if isinstance(reference, str) and reference.startswith("#/constraints/"):
                    target = constraints.get(reference[len("#/constraints/"):])
                    if target is not None:
                        details["constraint"] = target
        if isinstance(issues, list):
            delivered.extend(issues)
    return delivered


def typed_recovery_errors(case, evidence, checkpoint):
    evidence["providerProof"] = []
    faults = [row for row in evidence.get("events", []) if row.get("type") == "FAULT_APPLIED"]
    if len(faults) != case["fault"]["applications"]:
        return ["Declared finite faults were not all exercised"]
    artifacts = evidence.get("captureArtifacts", []) + evidence.get("pipelineArtifacts", [])
    by_ref = {(row["kind"], row["artifactId"], row["contentHash"]): row for row in artifacts}
    records = evidence.get("providerBoundary", [])
    requests = {row.get("id"): row for row in records if "request" in row
                and row.get("runId") == evidence.get("runId")}
    for fault in faults:
        scope_errors = fault_scope_errors(case["fault"]["kind"], fault["original"], fault["mutated"], case["fault"])
        if scope_errors:
            return scope_errors
        if fault.get("original") == fault.get("mutated"):
            return ["Fault did not change the candidate"]
        rejections = [row for row in evidence.get("events", []) if row.get("type") == "CAPTURE_REJECTED"
                      and row.get("tool") == fault.get("tool") and row.get("proposal") == fault.get("mutated")
                      and row.get("sequence", 0) > fault["sequence"]]
        if not rejections:
            return ["Missing correlated native refusal of the injected candidate"]
        refusal = rejections[0]
        native = refusal.get("nativeResult", {})
        if native.get("accepted") is not False or not native.get("issues"):
            return ["Injected candidate was not rejected by native admission"]
        reference = refusal.get("rejectionRef") or {}
        saved = by_ref.get(tuple(reference.get(key) for key in ("kind", "artifactId", "contentHash")))
        if saved is None:
            return ["Native refusal has no immutable stored evidence"]
        if case["fault"]["kind"] == "element-property-values":
            if native["issues"] != saved.get("payload", {}).get("feedback", {}).get("issues"):
                return ["Observed element refusal differs from immutable native schema findings"]
            following = [request for request in requests.values()
                         if native_time(request.get("at", 0)) > native_time(refusal["observedAt"])]
            if not following:
                return ["No actual provider request followed the element refusal"]
            first_request = min(following, key=lambda request: native_time(request["at"]))
            delivered = delivered_capture_issues(first_request.get("request", {}).get("messages", []))
            if not all(issue in delivered for issue in native["issues"]):
                return ["The next actual provider request lost native schema repair details"]
        accepted = [row for row in evidence.get("events", []) if row.get("type") == "CAPTURE_ACCEPTED"
                    and row.get("tool") == fault.get("tool") and row.get("source") == "model-tool"
                    and (not fault.get("capabilityId") or row.get("capabilityId") == fault["capabilityId"])
                    and row.get("sequence", 0) > refusal["sequence"]]
        if not accepted:
            return ["No subsequent model candidate passed native admission"]
        repaired = accepted[-1]
        if repaired.get("proposal") == fault.get("mutated"):
            return ["Accepted proposal did not correct the rejected candidate"]
        proven = False
        for response in records:
            if response.get("kind") != "response" or native_time(response["at"]) < native_time(refusal["observedAt"]):
                continue
            request = requests.get(response.get("requestId"), {})
            if native_time(request.get("at", 0)) <= native_time(refusal["observedAt"]):
                continue
            packet = "\n".join(text_fragments(request.get("request", {}).get("messages", [])))
            if case["fault"]["kind"] != "element-property-values" and not any(issue.get("message") in packet and (
                issue.get("code") not in {"INVALID_TYPE", "INVALID_JSON", "MISSING_FIELD", "DUPLICATE_JSON_KEY"}
                or issue.get("code") in packet and issue.get("path") in packet)
                for issue in native["issues"] if issue.get("code") and issue.get("message")):
                continue
            message = response.get("value", {}).get("message") or {}
            for call in message.get("toolExecutionRequests", []):
                try:
                    arguments = json.loads(call.get("arguments", ""))
                except (TypeError, ValueError):
                    continue
                if call.get("name") == fault.get("tool") and capture_value(arguments.get(fault.get("parameter", "capture"))) == capture_value(repaired.get("proposal")):
                    proven = True
                    evidence["providerProof"].append({"faultSequence": fault["sequence"], "requestId": response["requestId"],
                        "modelCallId": call.get("id"), "tool": call["name"], "rejectionRef": reference,
                        "acceptedSequence": repaired["sequence"], "consumerSequence": checkpoint["sequence"]})
        if not proven:
            return ["Actual next provider request and corrected model tool call are not proven"]
        if checkpoint.get("sequence", 0) <= repaired["sequence"]:
            return ["Consumer did not continue after native model correction"]
    return []


def verdict(case, evidence):
    def result(outcome, *reasons):
        return {"caseId": case["id"], "outcome": outcome, "reasons": list(reasons)}

    if evidence.get("infrastructureErrors"):
        return result("INFRASTRUCTURE_FAILURE", *evidence["infrastructureErrors"])
    forbidden_tools = set(case.get("checks", {}).get("forbiddenTools", []))
    if forbidden_tools and not evidence.get("providerBoundary"):
        return result("RECOVERY_FAILURE", "Tool prohibition has no provider evidence")
    for record in evidence.get("providerBoundary", []) if forbidden_tools else []:
        value = record.get("value") or {}
        calls = (
            list((value.get("message") or {}).get("toolExecutionRequests", []))
            if record.get("kind") == "response" else []
        )
        if record.get("kind", "").startswith("tool-"):
            calls.append(value.get("toolExecutionRequest") or {})
        for call in calls:
            if call.get("name") in forbidden_tools:
                return result("RECOVERY_FAILURE", "Forbidden model tool call: " + call["name"])
    for interaction in evidence.get("interactions", []):
        if (
            interaction.get("kind") == "decision"
            and interaction.get("action") not in ALLOWED_ACTIONS
        ):
            return result(
                "RECOVERY_FAILURE",
                "Forbidden assistance: " + str(interaction.get("action")),
            )
        if interaction.get("kind") == "decision":
            if not isinstance(interaction.get("revision"), int):
                return result(
                    "RECOVERY_FAILURE",
                    "Decision is not bound to an advertised revision",
                )
            if interaction["action"] not in {
                "yes",
                "no",
                "continue-to-planning",
            } and not all(
                interaction.get(key) for key in ("artifactType", "artifactHash")
            ):
                return result(
                    "RECOVERY_FAILURE", "Approval is not bound to an artifact"
                )
            if interaction["action"] == "continue-to-planning" and not interaction.get(
                "gate", ""
            ).startswith("__GATE:requirement-draft-ready__"):
                return result(
                    "RECOVERY_FAILURE",
                    "Planning continuation has no ordinary ready-draft gate",
                )
        if interaction.get("kind") not in {"initial", "decision", "clarification"}:
            return result("RECOVERY_FAILURE", "Undeclared repair assistance")
    if not evidence.get("budget") or evidence["budget"].get("requests", 0) < 1:
        return result("RECOVERY_FAILURE", "Native model accounting is missing")
    if evidence["budget"].get("grants", 0) or evidence["budget"].get("refusal"):
        return result(
            "RECOVERY_FAILURE", "Native model allowance was granted or exhausted"
        )
    if case["boundary"] == "completion" and evidence.get("publicationFailure"):
        return result("PUBLICATION_FAILURE", evidence["publicationFailure"])
    if evidence.get("strictFailure"):
        return result("RECOVERY_FAILURE", evidence["strictFailure"]["reason"])
    if evidence.get("earlierFailure"):
        return result(
            "RECOVERY_FAILURE", "A later success cannot erase an earlier failure"
        )
    checkpoint = evidence.get("checkpoint") or {}
    if not evidence.get("runId") or checkpoint.get("runId") != evidence["runId"]:
        return result("RECOVERY_FAILURE", "No correlated same-run consumer checkpoint")
    if checkpoint.get("conversationId") != evidence.get("conversationId"):
        return result("RECOVERY_FAILURE", "Checkpoint belongs to another conversation")
    if checkpoint.get("boundary") != "materialization-inputs-accepted":
        return result("RECOVERY_FAILURE", "Consumer admission has not been established")
    revisions = evidence.get("pipelineArtifacts", [])
    by_ref = {(r["kind"], r["artifactId"], r["contentHash"]): r for r in revisions}
    graph_ref = checkpoint.get("graphRef", {})
    if graph_ref.get("kind") != "CHAIN_PLAN_GRAPH":
        return result("RECOVERY_FAILURE", "Consumer reference is not a chain graph")
    graph_revision = by_ref.get(
        tuple(graph_ref.get(key) for key in ("kind", "artifactId", "contentHash"))
    )
    if (
        graph_revision is None
        or graph_revision.get("compilationId") != evidence["runId"]
    ):
        return result(
            "RECOVERY_FAILURE", "Consumer graph revision is missing or foreign"
        )
    if graph_errors(case, graph_revision["payload"]):
        return result(
            "RECOVERY_FAILURE", *graph_errors(case, graph_revision["payload"])
        )
    for ref in checkpoint.get("inputRefs", []):
        if (
            tuple(ref.get(key) for key in ("kind", "artifactId", "contentHash"))
            not in by_ref
        ):
            return result("RECOVERY_FAILURE", "Consumer input revision is missing")
    if not checkpoint.get("inputRefs"):
        return result("RECOVERY_FAILURE", "Consumer inputs are absent")
    interactions = evidence.get("interactions", [])
    answers = [item for item in interactions if item.get("kind") == "clarification"]
    if case["inputMode"] == "complete" and answers:
        return result("RECOVERY_FAILURE", "A complete request needed an extra answer")
    if case["mechanism"] == "clarification":
        control = case["clarification"]
        if len(answers) != 1 or answers[0].get("text") != control["answer"]:
            return result(
                "RECOVERY_FAILURE", "Missing or invented clarification answer"
            )
        if not re.search(
            control["questionPattern"], answers[0].get("question", ""), re.I
        ):
            return result(
                "RECOVERY_FAILURE", "The declared missing value was not requested"
            )
        answer = answers[0]
        accepted_inputs = [
            row
            for row in revisions
            if row.get("kind") == "USER_INPUT"
            and row.get("compilationId") == evidence["runId"]
            and row.get("payload", {}).get("text") == control["answer"]
            and row.get("payload", {}).get("targetStageId") == answer.get("stageId")
        ]
        transitions = evidence.get("runDocument", {}).get("transitions", [])
        resumed = any(
            transition.get("fromRevision") == answer.get("questionRevision")
            and transition.get("fromStatus") == "WAITING_FOR_INPUT"
            and transition.get("toStatus") == "RUNNING"
            and transition.get("stageId") == answer.get("stageId")
            for transition in transitions
        )
        if not accepted_inputs or not resumed:
            return result(
                "RECOVERY_FAILURE",
                "The control answer was not accepted by the waiting native stage",
            )
        answer_time = min(native_time(row["createdAt"]) for row in accepted_inputs)
        if native_time(graph_revision["createdAt"]) < answer_time or any(
            row.get("kind") == "CHAIN_PLAN_GRAPH"
            and native_time(row["createdAt"]) < answer_time
            for row in revisions
        ):
            return result(
                "RECOVERY_FAILURE",
                "Construction invented the missing value before its answer",
            )
    elif case.get("requireProviderEvidence"):
        errors = typed_recovery_errors(case, evidence, checkpoint)
        if errors:
            return result("RECOVERY_FAILURE", *errors)
    else:
        faults = [
            event
            for event in evidence.get("events", [])
            if event.get("type") == "FAULT_APPLIED"
        ]
        if not faults:
            return result("NOT_EXERCISED", "Declared fault was not applied")
        if len(faults) != case["fault"]["applications"]:
            return result("RECOVERY_FAILURE", "Unexpected fault application count")
        fault = faults[0]
        if fault.get("original") == fault.get("mutated"):
            return result("NOT_EXERCISED", "Candidate was unchanged")
        declared_mutation = copy.deepcopy(fault["original"])
        if case["fault"]["kind"] == "http-semantic-reference":
            declared_mutation["endpoints"][0]["semanticNodeId"] = fault["mutated"][
                "endpoints"
            ][0]["semanticNodeId"]
        else:
            declared_mutation["endpoints"][0]["externalRoute"] = str(
                declared_mutation["endpoints"][0]["externalRoute"]
            ).lower()
        if declared_mutation != fault["mutated"]:
            return result(
                "RECOVERY_FAILURE", "Fault changed fields outside its declared selector"
            )
        feedback = [
            r
            for r in evidence.get("captureArtifacts", [])
            if r.get("kind") == "CAPTURE_ATTEMPT_FEEDBACK"
        ]
        rejected = [
            r
            for r in feedback
            if (r.get("payload", {}).get("proposal") or {}).get("arguments")
            == fault.get("mutated")
            and (r.get("payload", {}).get("proposal") or {}).get("capabilityId")
            == fault.get("capabilityId")
            and (r.get("payload", {}).get("proposal") or {}).get("toolName")
            == "captureHttpTriggers"
            and r.get("payload", {}).get("feedback")
        ]
        if case["fault"]["kind"] == "http-exposure-type":
            rejection_sequence = 0
            by_capture_ref = {
                (row.get("kind"), row.get("artifactId"), row.get("contentHash")): row
                for row in feedback
            }
            rejected = []
            for event in evidence.get("events", []):
                ref = event.get("rejectionRef") or {}
                native = by_capture_ref.get(
                    tuple(ref.get(key) for key in ("kind", "artifactId", "contentHash"))
                )
                issues = event.get("nativeResult", {}).get("issues", [])
                if (
                    event.get("type") == "CAPTURE_REJECTED"
                    and event.get("conversationId") == fault.get("conversationId")
                    and event.get("capabilityId") == fault.get("capabilityId")
                    and event.get("proposal") == fault["mutated"]
                    and event.get("nativeResult", {}).get("accepted") is False
                    and event.get("sequence", 0) > fault.get("sequence", 0)
                    and native
                    and any(
                        issue.get("code") == "INVALID_TYPE"
                        and issue.get("path") == "/capture/endpoints/0/externalRoute"
                        and (issue.get("message") == native.get("payload", {}).get("feedback", {}).get("summary")
                             or any(typed.get("code") == issue.get("code") and typed.get("path") == issue.get("path")
                                    and typed.get("message") == issue.get("message")
                                    for typed in native.get("payload", {}).get("feedback", {}).get("issues", [])))
                        for issue in issues
                    )
                ):
                    rejected.append(native)
                    rejection_sequence = max(rejection_sequence, event["sequence"])
        accepted = [
            event
            for event in evidence.get("events", [])
            if event.get("type") == "CAPTURE_ACCEPTED"
            and event.get("conversationId") == fault.get("conversationId")
            and event.get("capabilityId") == fault.get("capabilityId")
            and event.get("sequence", 0) > fault.get("sequence", 0)
            and (
                case["fault"]["kind"] != "http-exposure-type"
                or event.get("sequence", 0) > rejection_sequence
            )
        ]
        if not rejected or not accepted:
            return result(
                "RECOVERY_FAILURE",
                "Missing native rejection or accepted subsequent model proposal",
            )
        repaired = accepted[-1]
        if (
            repaired.get("proposal") == fault["mutated"]
            or repaired.get("source") != "model-tool"
            or repaired.get("nativeAdmission") != "configured-trigger-set"
        ):
            return result(
                "RECOVERY_FAILURE", "Projection alone cannot prove model correction"
            )
        endpoints = repaired.get("proposal", {}).get("endpoints", [])
        if not endpoints or any(
            endpoint.get("semanticNodeId")
            not in {node["nodeId"] for node in graph_revision["payload"]["nodes"]}
            for endpoint in endpoints
        ):
            return result(
                "RECOVERY_FAILURE", "Corrected model reference was not consumed"
            )
        configured = repaired.get("configuredTriggers")
        if not configured or not configured.get("triggers"):
            return result("RECOVERY_FAILURE", "Accepted native capture is missing")
        graph_nodes = {
            node["nodeId"]: node for node in graph_revision["payload"]["nodes"]
        }
        for trigger in configured["triggers"]:
            node = graph_nodes.get(trigger.get("semanticNodeId"))
            if node is None:
                return result("RECOVERY_FAILURE", "Native trigger was not consumed")
            properties = {
                prop["key"]: prop.get("value") for prop in node.get("properties", [])
            }
            if any(
                properties.get(prop["key"]) != prop.get("value")
                for prop in trigger.get("properties", [])
            ):
                return result(
                    "RECOVERY_FAILURE",
                    "Accepted trigger properties changed before continuation",
                )
        if checkpoint.get("sequence", 0) <= repaired.get("sequence", 0):
            return result(
                "RECOVERY_FAILURE", "No continuation after accepted correction"
            )
    if case.get("requireProviderEvidence"):
        errors = model_boundary_errors(case, evidence)
        if errors:
            return result("RECOVERY_FAILURE", *errors)
    if case["boundary"] == "checkpoint":
        if evidence.get("catalogWritten"):
            return result(
                "RECOVERY_FAILURE", "Focused execution crossed the pre-write boundary"
            )
        return result("CHECKPOINT_PASS")
    if evidence.get("publicationFailure"):
        return result("PUBLICATION_FAILURE", evidence["publicationFailure"])
    if evidence.get("currentState") != "CHAIN_MATERIALIZED" or not evidence.get(
        "catalogVerified"
    ):
        return result(
            "RECOVERY_FAILURE", "Construction and catalog readback are incomplete"
        )
    if case.get("requireProviderEvidence"):
        errors = catalog_graph_errors(evidence, graph_revision["payload"])
        if errors:
            return result("RECOVERY_FAILURE", *errors)
    return result("COMPLETION_PASS")


def native_time(value):
    return (
        value
        if isinstance(value, (int, float))
        else datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    )


def duration_seconds(value):
    value = value.upper()
    match = re.fullmatch(
        r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value
    )
    if match and any(match.groups()):
        return sum(
            float(part or 0) * factor
            for part, factor in zip(match.groups(), (3600, 60, 1))
        )
    match = re.fullmatch(r"(\d+(?:\.\d+)?)(MS|S|M|H)", value)
    if not match:
        raise ValueError("Unsupported duration: " + value)
    return float(match[1]) * {"MS": 0.001, "S": 1, "M": 60, "H": 3600}[match[2]]


def observation_seconds(settings):
    active = duration_seconds(settings.get("LLM_MAX_ACTIVE_TIME_PER_RUN", "PT15M"))
    transport = duration_seconds(settings["LLM_TIMEOUT"])
    requests = int(settings.get("LLM_MAX_REQUESTS_PER_RUN", "128"))
    attempts = int(settings.get("LLM_TRANSIENT_RETRY_MAX_ATTEMPTS", "3"))
    delays = [
        int(value)
        for value in settings.get(
            "LLM_TRANSIENT_RETRY_BACKOFF_SECONDS", "2,5,10"
        ).split(",")
    ]
    reachable = sum(
        delays[min(index, len(delays) - 1)] for index in range(attempts - 1)
    )
    # Provider waits have no fixed ceiling. Allow one transport-timeout window per turn backoff.
    backoffs = int(settings.get("QIP_AI_LLM_RATE_LIMIT_MAX_TURN_BACKOFFS", "12"))
    return int(active + requests * reachable + (backoffs + 2) * transport)


def fetch_json(url):
    try:
        with urlopen(url, timeout=15) as response:
            return json.load(response)
    except HTTPError as error:
        if error.code == 404:
            return {}
        raise


def inspect_container(identity):
    return json.loads(
        subprocess.check_output(["docker", "inspect", identity], text=True)
    )[0]


def process_identity(container):
    return {
        "id": container["Id"],
        "startedAt": container["State"]["StartedAt"],
        "restarts": container["RestartCount"],
        "running": container["State"]["Running"],
    }


class IsolatedInstance:
    def __init__(self, output, image, baseline="qip-ai-service"):
        self.output = Path(output).resolve()
        self.project = "qip-recovery-" + uuid.uuid4().hex[:12]
        self.image = image
        self.baseline = inspect_container(baseline)
        self.settings = dict(
            item.split("=", 1) for item in self.baseline["Config"]["Env"] if "=" in item
        )
        if self.settings.get("QUARKUS_PROFILE") == "test" or not self.settings.get(
            "LLM_API_KEY"
        ):
            raise ValueError("An ordinary configured model is required")
        self.shared = {
            name: process_identity(inspect_container(name))
            for name in (
                baseline,
                "postgreSQL",
                "minio",
                "qip-runtime-catalog",
                "qip-knowledge-sidecar",
            )
        }
        networks = list(self.baseline["NetworkSettings"]["Networks"])
        if len(networks) != 1:
            raise ValueError("Select a baseline on one known dependency network")
        self.network = networks[0]
        self.temporary = tempfile.TemporaryDirectory(prefix="qip-recovery-env-")
        env_file = Path(self.temporary.name) / "runtime.env"
        prefix = (
            "LLM_",
            "CHAT_MEMORY_",
            "QIP_",
            "CATALOG_",
            "APIHUB_",
            "QUARKUS_",
            "STORAGE_",
            "MINIO_",
        )
        selected = {
            key: value for key, value in self.settings.items() if key.startswith(prefix)
        }
        if any("\n" in value or "\r" in value for value in selected.values()):
            raise ValueError(
                "Multiline runtime values cannot be copied to a raw environment file"
            )
        env_file.write_text(
            "\n".join(key + "=" + value for key, value in selected.items()) + "\n"
        )
        env_file.chmod(0o600)
        self.control = self.output / "control"
        self.control.mkdir(parents=True)
        self.control.chmod(0o777)
        (self.control / "events").mkdir(mode=0o777)
        (self.control / "events").chmod(0o777)
        self.environment = {
            **os.environ,
            "RECOVERY_AI_IMAGE": image,
            "RECOVERY_SHARED_NETWORK": self.network,
            "RECOVERY_POSTGRES_IMAGE": inspect_container("postgreSQL")["Image"],
            "RECOVERY_STORAGE_IMAGE": inspect_container("minio")["Image"],
            "RECOVERY_SERVICE_ENV_FILE": str(env_file),
            "RECOVERY_CONTROL_DIRECTORY": str(self.control),
        }
        self.started = False

    def compose(self, *args, capture=False):
        command = ["docker", "compose", "-p", self.project, "-f", str(COMPOSE), *args]
        if capture:
            return subprocess.check_output(
                command, env=self.environment, text=True
            ).strip()
        with (self.output / "lifecycle.log").open("a") as log:
            subprocess.run(
                command,
                env=self.environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
            )

    def start(self, conversation, boundary, mutation="", property_values=None):
        write_json(
            self.control / "plan.json",
            {
                "schemaVersion": 1,
                "conversationId": conversation,
                "boundary": boundary,
                "mutation": mutation,
                "propertyValues": property_values or {},
            },
        )
        self.compose("config", "--quiet")
        self.started = True
        self.compose("up", "-d", "--wait", "--wait-timeout", "240")
        self.url = "http://" + self.compose("port", "recovery-ai", "8080", capture=True)
        self.storage_url = "http://" + self.compose(
            "port", "recovery-storage", "9000", capture=True
        )
        self.identities = {}
        shared_mounts = {
            mount["Source"]
            for name in self.shared
            for mount in inspect_container(name).get("Mounts", [])
        }
        for service in ("recovery-ai", "recovery-db", "recovery-storage"):
            instance = inspect_container(
                self.compose("ps", "-q", service, capture=True)
            )
            if (
                instance["Config"]["Labels"].get("com.docker.compose.project")
                != self.project
            ):
                raise RuntimeError("Foreign container in owned project")
            if any(
                mount["Source"] in shared_mounts for mount in instance.get("Mounts", [])
            ):
                raise RuntimeError("Recovery instance shares a persistent volume")
            self.identities[service] = {
                **process_identity(instance),
                "mounts": [mount["Source"] for mount in instance.get("Mounts", [])],
            }
        write_json(
            self.output / "instance.json",
            {
                "project": self.project,
                "containers": self.identities,
                "sharedBefore": self.shared,
            },
        )

    def export(self, conversation, output):
        return self.probe([conversation, str(output)], output)

    def probe(self, arguments, output):
        output.parent.mkdir(parents=True, exist_ok=True)
        classpath = ROOT / "ai-service/target/recovery-probe-classpath.txt"
        if not classpath.exists():
            raise RuntimeError(
                "Build the evidence probe classpath before starting a campaign"
            )
        cp = os.pathsep.join(
            (
                str(ROOT / "ai-service/target/test-classes"),
                str(ROOT / "ai-service/target/classes"),
                classpath.read_text().strip(),
            )
        )
        environment = {
            **os.environ,
            "RECOVERY_STORAGE_ENDPOINT": self.storage_url,
            "RECOVERY_STORAGE_BUCKET": "recovery-evidence",
            "RECOVERY_STORAGE_ACCESS_KEY": "minioadmin",
            "RECOVERY_STORAGE_SECRET_KEY": "minioadmin",
        }
        with (self.output / "probe.log").open("a") as log:
            subprocess.run(
                [
                    "java",
                    "-cp",
                    cp,
                    "org.qubership.integration.platform.ai.compiler.artifact.RecoveryEvidenceProbe",
                    *arguments,
                ],
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
            )
        return read_json(output)

    def stop(self, retain=False):
        if not self.started:
            return
        failure = None
        try:
            with (self.output / "service.log").open("w") as log:
                subprocess.run(
                    [
                        "docker",
                        "compose",
                        "-p",
                        self.project,
                        "-f",
                        str(COMPOSE),
                        "logs",
                        "--no-color",
                    ],
                    env=self.environment,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
        except Exception as error:
            failure = error
            retain = True
        try:
            self.compose("stop", "recovery-ai")
            write_json(
                self.output / "stopped.json",
                {"stoppedAt": time.time(), "project": self.project},
            )
            after = {
                name: process_identity(inspect_container(name)) for name in self.shared
            }
            write_json(self.output / "shared-after.json", after)
            if after != self.shared:
                raise RuntimeError(
                    "Shared service process changed during the owned case"
                )
        except Exception as error:
            failure = error
            retain = True
        try:
            if not retain:
                self.compose("down", "--volumes")
            else:
                self.compose("stop")
        finally:
            if retain or failure:
                write_json(
                    self.output / "retained.json",
                    {
                        "project": self.project,
                        "reason": "Owned resources retained for inspection; see lifecycle.log for stop status",
                    },
                )
        if failure:
            raise RuntimeError(
                "Owned lifecycle failed; stores retained for inspection"
            ) from failure

    def close(self):
        self.temporary.cleanup()


def lifecycle_check(output, image, baseline="qip-ai-service"):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    instance = IsolatedInstance(output, image, baseline)
    marker = uuid.uuid4().hex
    marker_path = output / "marker.txt"
    marker_path.write_text(marker)
    report = {}
    try:
        instance.start(str(uuid.uuid4()), "checkpoint")
        uploaded = json.loads(
            subprocess.check_output(
                [
                    "curl",
                    "-fsS",
                    "--max-time",
                    "30",
                    "-F",
                    "file=@" + str(marker_path),
                    "-F",
                    "prefix=e2e/lifecycle",
                    instance.url + "/api/v1/storage/objects",
                ],
                text=True,
            )
        )
        instance.compose("stop", "recovery-ai")
        exported = instance.probe(
            ["--object", uploaded["objectKey"], str(output / "object.json")],
            output / "object.json",
        )
        if (
            exported["sha256"] != hashlib.sha256(marker.encode()).hexdigest()
            or exported["modelBudgetCount"] != 0
        ):
            raise RuntimeError(
                "Post-stop object evidence or model-free lifecycle check failed"
            )
        instance.stop(retain=True)
        stopped = {
            service: inspect_container(item["id"])["State"]["Running"]
            for service, item in instance.identities.items()
        }
        if any(stopped.values()):
            raise RuntimeError("Owned retained stores are still running")
        report = {
            "privateStorage": True,
            "artifactReadAfterStop": True,
            "retainedStoresStopped": True,
            "sharedUnchanged": True,
            "modelRequests": 0,
            "project": instance.project,
        }
        write_json(output / "lifecycle.json", report)
        return report
    finally:
        try:
            if report:
                instance.compose("down", "--volumes")
            else:
                instance.stop(retain=True)
        finally:
            instance.close()


def run_case(case, output, image, baseline="qip-ai-service"):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    write_json(output / "case.json", case)
    conversation = str(uuid.uuid4())
    evidence = {"conversationId": conversation, "infrastructureErrors": []}
    instance = None
    driver = None
    retain = True
    try:
        instance = IsolatedInstance(output, image, baseline)
        timeout = observation_seconds(instance.settings)
        scenario = copy.deepcopy(case["scenario"])
        scenario["uniqueChainNamePrefix"] += "." + uuid.uuid4().hex[:8]
        if case.get("clarification"):
            scenario["clarification"] = case["clarification"]
        write_json(output / "scenario.json", {case["id"]: scenario})
        public_settings = {
            key: value
            for key, value in instance.settings.items()
            if key
            in {
                "LLM_CHAT_MODEL",
                "LLM_PROVIDER",
                "LLM_MAX_TOKENS",
                "LLM_TEMPERATURE",
                "LLM_TIMEOUT",
                "LLM_CONTEXT_TOKENS",
                "LLM_REASONING_EFFORT",
                "LLM_MAX_REQUESTS_PER_RUN",
                "LLM_MAX_TOKENS_PER_RUN",
                "LLM_MAX_ACTIVE_TIME_PER_RUN",
                "LLM_TRANSIENT_RETRY_MAX_ATTEMPTS",
                "LLM_TRANSIENT_RETRY_BACKOFF_SECONDS",
            }
        }
        write_json(
            output / "candidate.json",
            {
                **source_fingerprint(),
                "image": inspect_container_image(image),
                "modelSettings": public_settings,
                "observationSeconds": timeout,
            },
        )
        instance.start(
            conversation, case["boundary"], (case.get("fault") or {}).get("kind", ""),
            (case.get("fault") or {}).get("propertyValues")
        )
        environment = {
            **os.environ,
            "E2E_TURN_TIMEOUT_SEC": str(timeout),
            "PRODUCT_PIPELINE_POLL_TIMEOUT_SEC": str(timeout),
            "E2E_CATALOG_EVIDENCE_DIR": str(output / "catalog"),
        }
        command = [
            "bash",
            str(HERE / "run-product-scenario.sh"),
            "--scenario",
            case["id"],
            "--rep",
            "1",
            "--base-url",
            instance.url,
            "--evaluator-url",
            "http://127.0.0.1:1",
            "--report",
            str(output / "product-report.json"),
            "--scenarios-file",
            str(output / "scenario.json"),
            "--conversation-id",
            conversation,
            "--strict-recovery",
        ]
        with (output / "driver.log").open("w") as log:
            driver = subprocess.Popen(
                command,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                durable = fetch_json(
                    instance.url
                    + "/api/v1/chat/conversations/"
                    + conversation
                    + "/product-pipeline"
                )
            except (URLError, TimeoutError):
                durable = {}
            if durable:
                write_json(output / "durable.json", durable)
            if (
                case["boundary"] == "checkpoint"
                and (instance.control / "checkpoint.json").exists()
            ) or driver.poll() is not None:
                break
            time.sleep(2)
        else:
            evidence["infrastructureErrors"].append(
                "Derived observation budget expired"
            )
        evidence.update(instance.export(conversation, output / "artifacts.json"))
        evidence["events"] = [
            read_json(path)
            for path in sorted((instance.control / "events").glob("*.json"))
        ]
        evidence["providerBoundary"] = [read_json(path) for path in sorted(
            (instance.control / "provider-boundary").glob("*.json"))]
        if (instance.control / "checkpoint.json").exists():
            evidence["checkpoint"] = read_json(instance.control / "checkpoint.json")
        evidence["interactions"] = [
            json.loads(line)
            for line in (output / "interactions.jsonl").read_text().splitlines()
        ]
        evidence["earlierFailure"] = driver.poll() not in (None, 0) or any(
            "event: error" in path.read_text()
            for path in (output / "sse").glob("*.sse")
        )
        if (output / "strict-failure.json").exists():
            evidence["strictFailure"] = read_json(output / "strict-failure.json")
        durable = read_json(output / "durable.json")
        evidence["currentState"] = durable.get("currentState")
        evidence["catalogWritten"] = any(
            r["kind"] in {"MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT"}
            for r in evidence["pipelineArtifacts"]
        )
        if case["boundary"] == "completion":
            evidence["catalogVerified"] = driver.poll() == 0 and all(
                (output / "catalog" / name).exists()
                for name in ("chain.json", "elements.json", "dependencies.json")
            )
            if evidence.get("checkpoint") and any(
                attempt.get("stageId") == "materialization"
                and attempt.get("outcome") == "FAILED"
                for attempt in durable.get("attempts", [])
            ):
                evidence["publicationFailure"] = (
                    "Materialization failed after local consumer admission; inspect native evidence"
                )
        judged = verdict(case, evidence)
        retain = judged["outcome"] not in PASS
        write_json(output / "evidence.json", evidence)
        write_json(output / "proof-verdict.json", judged)
    except Exception as error:
        evidence["infrastructureErrors"].append(
            type(error).__name__ + ": " + str(error)
        )
        judged = {
            "caseId": case["id"],
            "outcome": "INFRASTRUCTURE_FAILURE",
            "reasons": evidence["infrastructureErrors"],
        }
        write_json(output / "evidence.json", evidence)
        write_json(output / "proof-verdict.json", judged)
    finally:
        try:
            try:
                if instance is not None:
                    instance.stop(retain=retain)
            except Exception as error:
                evidence["infrastructureErrors"].append(
                    "Teardown failed: " + str(error)
                )
        finally:
            try:
                if driver is not None and driver.poll() is None:
                    import signal

                    try:
                        os.killpg(driver.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    try:
                        driver.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(driver.pid, signal.SIGKILL)
                        driver.wait(timeout=15)
            except Exception as error:
                evidence["infrastructureErrors"].append(
                    "Transport teardown failed: " + str(error)
                )
            finally:
                if instance is not None:
                    try:
                        instance.close()
                    except Exception as error:
                        evidence["infrastructureErrors"].append(
                            "Runtime environment cleanup failed: " + str(error)
                        )
    if evidence["infrastructureErrors"]:
        judged = {
            "caseId": case["id"],
            "outcome": "INFRASTRUCTURE_FAILURE",
            "reasons": evidence["infrastructureErrors"],
        }
    write_json(output / "evidence.json", evidence)
    write_json(output / "verdict.json", judged)
    (output / "report.md").write_text(
        "# Recovery pilot\n\n"
        + case["id"]
        + ": "
        + judged["outcome"]
        + "\n\n"
        + "\n".join(judged["reasons"])
        + "\n"
    )
    return judged


def inspect_container_image(image):
    return json.loads(
        subprocess.check_output(["docker", "image", "inspect", image], text=True)
    )[0]["Id"]


def source_fingerprint():
    paths = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).split(b"\0")
    return {
        "head": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "diffHash": hashlib.sha256(
            subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)
        ).hexdigest(),
        "untrackedHashes": {
            os.fsdecode(path): hashlib.sha256(
                (ROOT / os.fsdecode(path)).read_bytes()
            ).hexdigest()
            for path in paths
            if path and (ROOT / os.fsdecode(path)).is_file()
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "run", "pilot", "lifecycle-check"])
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--case")
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--image")
    parser.add_argument("--baseline-container", default="qip-ai-service")
    args = parser.parse_args()
    if args.command == "lifecycle-check":
        if not args.out or not args.image:
            parser.error("lifecycle-check requires --out and --image")
        print(
            json.dumps(lifecycle_check(args.out, args.image, args.baseline_container))
        )
        return 0
    cases = load_cases(args.cases)
    if args.command == "check":
        if not args.case or not args.evidence:
            parser.error("check requires --case and --evidence")
        results = [verdict(cases[args.case], read_json(args.evidence))]
    else:
        if not args.out or not args.image:
            parser.error("live commands require --out and --image")
        if args.command == "run" and args.case not in cases:
            parser.error("run requires a known --case")
        selected = (
            list(cases.values()) if args.command == "pilot" else [cases[args.case]]
        )
        args.out.mkdir(parents=True, exist_ok=True)
        args.out.chmod(0o700)
        write_json(args.out / "cases.json", {"schemaVersion": 1, "cases": selected})
        results = []
        for case in selected:
            result = run_case(
                case, args.out / case["id"], args.image, args.baseline_container
            )
            results.append(result)
            print(json.dumps(result), flush=True)
        write_json(args.out / "campaign.json", results)
    print(json.dumps(results, indent=2))
    return 0 if all(result["outcome"] in PASS for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
