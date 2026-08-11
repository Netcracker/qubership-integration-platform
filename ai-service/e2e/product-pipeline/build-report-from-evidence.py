#!/usr/bin/env python3
"""Build a product CREATE run report entirely from the durable evidence endpoint."""
from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Any

# Required facts such as "GET /greetings" may appear as a contiguous substring, as split plan
# lines ("- GET" / "- /greetings"), or as structured trigger fields (operationName + path).
_HTTP_ENDPOINT_FACT_RE = re.compile(
    r"^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(/\S+)$",
    re.IGNORECASE,
)


def _first_decoded(decoded: dict[str, Any], kind_prefix: str) -> Any | None:
    for key, value in decoded.items():
        if key.startswith(kind_prefix + "#") or key == kind_prefix:
            return value
    return None


def _http_endpoint_parts(fact: str) -> tuple[str, str] | None:
    match = _HTTP_ENDPOINT_FACT_RE.match(fact.strip())
    if not match:
        return None
    return match.group(1).upper(), match.group(2)


def _element_property(entry: dict[str, Any], *names: str) -> str | None:
    props = entry.get("properties")
    if not isinstance(props, dict):
        return None
    for name in names:
        value = props.get(name)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, dict):
            nested = value.get("value")
            if isinstance(nested, str) and nested.strip():
                return nested.strip()
    return None


def _structured_http_endpoints(decoded: dict[str, Any]) -> set[tuple[str, str]]:
    """Collect (METHOD, /path) pairs from design-flow and catalog/graph element properties."""
    found: set[tuple[str, str]] = set()

    flow = _first_decoded(decoded, "NORMALIZED_DESIGN_FLOW")
    if isinstance(flow, dict):
        trigger = flow.get("trigger")
        if isinstance(trigger, dict):
            method = trigger.get("operationName")
            path = trigger.get("endpointOrTopic")
            if isinstance(method, str) and isinstance(path, str):
                method_s, path_s = method.strip().upper(), path.strip()
                if method_s and path_s.startswith("/"):
                    found.add((method_s, path_s))

    for kind in ("CATALOG_CHAIN_SNAPSHOT", "CHAIN_PLAN_GRAPH", "GRAPH_ASSEMBLY_RESULT"):
        artifact = _first_decoded(decoded, kind)
        if not isinstance(artifact, dict):
            continue
        roots = [artifact]
        graph = artifact.get("graph")
        if isinstance(graph, dict):
            roots.append(graph)
        for root in roots:
            for field in ("nodes", "elements"):
                entries = root.get(field)
                if not isinstance(entries, list):
                    continue
                for entry in entries:
                    if not isinstance(entry, dict):
                        continue
                    etype = str(entry.get("type") or "").strip().lower()
                    if etype not in {"http-trigger", "http"}:
                        continue
                    method = _element_property(
                        entry, "httpMethodRestrict", "httpMethod", "method"
                    )
                    path = _element_property(
                        entry, "contextPath", "httpUri", "path", "uri"
                    )
                    if method and path and path.startswith("/"):
                        found.add((method.upper(), path))

    plan = _first_decoded(decoded, "IMPLEMENTATION_PLAN")
    if isinstance(plan, dict):
        endpoint_facts = plan.get("endpointFacts")
        if isinstance(endpoint_facts, list):
            for item in endpoint_facts:
                if not isinstance(item, str):
                    continue
                parts = _http_endpoint_parts(item)
                if parts:
                    found.add(parts)

    return found


def _http_endpoint_in_text(haystack: str, method: str, path: str) -> bool:
    """True when METHOD and /path co-occur with limited intervening text (incl. newlines)."""
    pattern = re.compile(
        rf"(?is)\b{re.escape(method)}\b(?:(?!\b(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b).)"
        rf"{{0,120}}{re.escape(path)}\b"
    )
    return pattern.search(haystack) is not None


def _required_fact_present(
    fact: str, haystack: str, decoded: dict[str, Any]
) -> bool:
    if fact in haystack:
        return True
    parts = _http_endpoint_parts(fact)
    if not parts:
        return False
    method, path = parts
    if (method, path) in _structured_http_endpoints(decoded):
        return True
    return _http_endpoint_in_text(haystack, method, path)


def _materialized_element_types(decoded: dict[str, Any]) -> list[str]:
    values: set[str] = set()
    for kind in (
        "CHAIN_PLAN_GRAPH",
        "GRAPH_ASSEMBLY_RESULT",
        "CATALOG_CHAIN_SNAPSHOT",
    ):
        artifact = _first_decoded(decoded, kind)
        if not isinstance(artifact, dict):
            continue
        graph = artifact.get("graph")
        roots = [artifact]
        if isinstance(graph, dict):
            roots.append(graph)
        for root in roots:
            for field in ("nodes", "elements"):
                entries = root.get(field)
                if not isinstance(entries, list):
                    continue
                for entry in entries:
                    if not isinstance(entry, dict):
                        continue
                    value = entry.get("type")
                    if isinstance(value, str) and value.strip():
                        values.add(value.strip())
    return sorted(values)


def _fact_texts(requirement: Any) -> list[str]:
    texts: list[str] = []
    if not isinstance(requirement, dict):
        return texts
    facts = requirement.get("facts")
    if isinstance(facts, list):
        for fact in facts:
            if isinstance(fact, dict):
                for field in ("text", "value", "name", "id"):
                    value = fact.get(field)
                    if isinstance(value, str) and value.strip():
                        texts.append(value.strip())
            elif isinstance(fact, str) and fact.strip():
                texts.append(fact.strip())
    for field in ("summary", "goal", "approvedDraftText"):
        value = requirement.get(field)
        if isinstance(value, str) and value.strip():
            texts.append(value.strip())
    for field in ("inputs", "constraints"):
        values = requirement.get(field)
        if isinstance(values, list):
            texts.extend(str(item).strip() for item in values if str(item).strip())
    return texts


def _positive_fact_texts(requirement: Any) -> list[str]:
    """Return only POSITIVE requirement fact texts (negations must not trip forbidden checks)."""
    texts: list[str] = []
    if not isinstance(requirement, dict):
        return texts
    facts = requirement.get("facts")
    if not isinstance(facts, list):
        return texts
    for fact in facts:
        if not isinstance(fact, dict):
            continue
        polarity = str(fact.get("polarity") or "").strip().upper()
        if polarity == "NEGATIVE":
            continue
        for field in ("text", "value", "name", "id"):
            value = fact.get(field)
            if isinstance(value, str) and value.strip():
                texts.append(value.strip())
                break
    return texts


def _findings(validation: Any) -> list[dict[str, Any]]:
    if not isinstance(validation, dict):
        return []
    raw = validation.get("findings")
    if not isinstance(raw, list):
        return []
    return [item for item in raw if isinstance(item, dict)]


def _approval_eligible(findings: list[dict[str, Any]]) -> bool:
    return not any(bool(item.get("blocker")) for item in findings)


def _validation_verdict(findings: list[dict[str, Any]]) -> str:
    return "PASS" if _approval_eligible(findings) else "FAIL"


def _haystack(
    decoded: dict[str, Any],
    requirement_texts: list[str],
    findings: list[dict[str, Any]],
    attempts: list[Any],
    transitions: list[Any],
    validation_verdict: str,
) -> str:
    parts = [json.dumps(decoded, ensure_ascii=False), "\n".join(requirement_texts)]
    parts.append(validation_verdict)
    if validation_verdict == "FAIL":
        parts.append("VALIDATION_FAILURE")
    for finding in findings:
        for field in ("code", "message"):
            value = finding.get(field)
            if isinstance(value, str) and value.strip():
                parts.append(value.strip())
    for attempt in attempts:
        if isinstance(attempt, dict):
            for field in ("failureEvidence", "outcome", "stageId"):
                value = attempt.get(field)
                if isinstance(value, str) and value.strip():
                    parts.append(value.strip())
    for transition in transitions:
        if isinstance(transition, dict):
            for field in ("reason", "toStatus", "fromStatus"):
                value = transition.get(field)
                if isinstance(value, str) and value.strip():
                    parts.append(value.strip())
    return "\n".join(parts)


def _forbidden_haystack(decoded: dict[str, Any], requirement: Any) -> str:
    """Haystack for forbidden facts: topology and positive facts only.

    Skill metadata, negativeConstraints, and phrases such as "No APIHub" must not count as
    present forbidden facts. Match against plan/catalog topology and POSITIVE requirement texts.
    """
    parts: list[str] = []
    for kind in ("CHAIN_PLAN_GRAPH", "CATALOG_CHAIN_SNAPSHOT"):
        artifact = _first_decoded(decoded, kind)
        if artifact is not None:
            parts.append(json.dumps(artifact, ensure_ascii=False))
    plan = _first_decoded(decoded, "IMPLEMENTATION_PLAN")
    if isinstance(plan, dict):
        # Exclude planText, negativeConstraints, and serviceBindings. Planner LLM text often
        # invents "cip-service-call-generator" step labels for script-only flows; real topology
        # lives in CHAIN_PLAN_GRAPH / CATALOG_CHAIN_SNAPSHOT above.
        for field in ("endpointFacts", "branchFacts", "scriptOutcomes"):
            value = plan.get(field)
            if value is not None:
                parts.append(json.dumps(value, ensure_ascii=False))
    parts.extend(_positive_fact_texts(requirement))
    return "\n".join(parts)


def build_report(
    evidence: dict[str, Any],
    *,
    scenario_id: str,
    rep: int,
    required_facts: list[str],
    forbidden_facts: list[str],
    expected_terminal_state: str,
) -> dict[str, Any]:
    if not isinstance(evidence, dict) or not evidence:
        raise ValueError("evidence payload is missing")

    manifest = evidence.get("runManifest") or {}
    if not isinstance(manifest, dict):
        raise ValueError("runManifest must be an object")
    decoded = evidence.get("decodedArtifacts") or {}
    if not isinstance(decoded, dict):
        raise ValueError("decodedArtifacts must be an object")
    attempts = evidence.get("attempts") or []
    transitions = evidence.get("transitions") or []
    if not isinstance(attempts, list):
        raise ValueError("attempts must be an array")
    if not isinstance(transitions, list):
        raise ValueError("transitions must be an array")

    plan = _first_decoded(decoded, "IMPLEMENTATION_PLAN")
    validation = _first_decoded(decoded, "PLAN_VALIDATION_RESULT")
    approval = _first_decoded(decoded, "APPROVAL_RECORD")
    requirement = _first_decoded(decoded, "REQUIREMENT_BRIEF")
    requirement_facts = _fact_texts(requirement)

    knowledge_package = manifest.get("knowledgePackage")
    if not isinstance(knowledge_package, dict):
        raise ValueError("runManifest.knowledgePackage must be an object")
    package_fields = {
        "packageKey",
        "knowledgeVersion",
        "schemaVersion",
        "packageChecksum",
        "certificationStatus",
        "certificationDigest",
    }
    if set(knowledge_package) != package_fields:
        raise ValueError(
            "runManifest.knowledgePackage must contain the complete package ref"
        )
    for field in sorted(package_fields):
        value = knowledge_package.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"knowledgePackage.{field} is required")
    if knowledge_package["certificationStatus"] != "CERTIFIED":
        raise ValueError("knowledgePackage must be CERTIFIED")

    raw_context = evidence.get("knowledgeContext")
    if not isinstance(raw_context, dict):
        raise ValueError("knowledgeContext must be an object")
    context_ref = raw_context.get("packageRef")
    if not isinstance(context_ref, dict):
        raise ValueError("knowledgeContext.packageRef must be an object")
    if context_ref != knowledge_package:
        raise ValueError(
            "knowledgeContext.packageRef must equal runManifest.knowledgePackage"
        )
    object_ids = raw_context.get("objectIds")
    if not isinstance(object_ids, list) or not all(
        isinstance(object_id, str) and object_id.strip()
        for object_id in object_ids
    ):
        raise ValueError("knowledgeContext.objectIds must be an array of IDs")
    if len(object_ids) != len(dict.fromkeys(object_ids)):
        raise ValueError("knowledgeContext.objectIds must not contain duplicates")
    content_chars = raw_context.get("contentChars")
    if (
        not isinstance(content_chars, int)
        or isinstance(content_chars, bool)
        or content_chars < 0
    ):
        raise ValueError("knowledgeContext.contentChars must be a nonnegative integer")

    runtime_mode = manifest.get("runtimeSelection")
    profile_id = manifest.get("profileId")
    profile_version = manifest.get("profileVersion")

    for label, value in (
        ("runtimeMode", runtime_mode),
        ("profileId", profile_id),
        ("profileVersion", profile_version),
    ):
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"evidence missing {label}")
        if value.strip().lower() == "live":
            raise ValueError(f"{label} must not be a placeholder")

    # PlanValidationResult is produced by the planning stage. Early terminals such as
    # WAITING_FOR_INPUT during requirement discovery must not require it.
    validation_required = expected_terminal_state in {
        "PLAN_APPROVED",
        "WAITING_FOR_APPROVAL",
        "WAITING_FOR_IMPLEMENT",
        "CHAIN_MATERIALIZED",
        "FAILED",
    }
    if validation_required:
        if validation is None:
            raise ValueError("evidence missing PLAN_VALIDATION_RESULT")
        if not isinstance(validation, dict) or "findings" not in validation:
            raise ValueError("PLAN_VALIDATION_RESULT must expose findings")
        findings = _findings(validation)
        validation_verdict = _validation_verdict(findings)
        approval_eligible = _approval_eligible(findings)
    elif validation is None:
        findings = []
        validation_verdict = "SKIPPED"
        approval_eligible = False
    else:
        if not isinstance(validation, dict) or "findings" not in validation:
            raise ValueError("PLAN_VALIDATION_RESULT must expose findings")
        findings = _findings(validation)
        validation_verdict = _validation_verdict(findings)
        approval_eligible = _approval_eligible(findings)

    approval_target_hash = None
    if isinstance(approval, dict):
        approval_target_hash = approval.get("targetContentHash")
    if expected_terminal_state == "PLAN_APPROVED":
        if not isinstance(approval_target_hash, str) or not approval_target_hash.strip():
            raise ValueError("PLAN_APPROVED evidence missing APPROVAL_RECORD.targetContentHash")
        if approval_target_hash.strip().lower() == "live":
            raise ValueError("approvalTargetHash must not be a placeholder")
    elif isinstance(approval_target_hash, str) and approval_target_hash.strip().lower() == "live":
        raise ValueError("approvalTargetHash must not be a placeholder")
    else:
        approval_target_hash = approval_target_hash if isinstance(approval_target_hash, str) else ""

    approved_plan_hash = evidence.get("approvedPlanContentHash")
    if not isinstance(approved_plan_hash, str) or not approved_plan_hash.strip():
        approved_plan_hash = approval_target_hash if isinstance(approval_target_hash, str) else ""

    materialization = _first_decoded(decoded, "MATERIALIZATION_RESULT")
    snapshot = _first_decoded(decoded, "CATALOG_CHAIN_SNAPSHOT")
    reconcile = _first_decoded(decoded, "RECONCILE_RESULT")
    materialized_chain_id = evidence.get("materializedChainId")
    if not isinstance(materialized_chain_id, str) or not materialized_chain_id.strip():
        materialized_chain_id = ""
        for candidate in (materialization, snapshot):
            if isinstance(candidate, dict):
                value = candidate.get("chainId")
                if isinstance(value, str) and value.strip():
                    materialized_chain_id = value.strip()
                    break

    reconcile_matches = evidence.get("reconcileMatches")
    if not isinstance(reconcile_matches, bool):
        reconcile_matches = None
        if isinstance(reconcile, dict) and isinstance(reconcile.get("matches"), bool):
            reconcile_matches = reconcile.get("matches")

    graph_patches: list[dict[str, Any]] = []
    for key, value in decoded.items():
        if not (key.startswith("GRAPH_PATCH_ARTIFACT#") or key == "GRAPH_PATCH_ARTIFACT"):
            continue
        if isinstance(value, dict):
            graph_patches.append(value)

    pin: dict[str, Any] = {}
    if isinstance(manifest.get("compilerRunPin"), dict):
        pin = manifest.get("compilerRunPin") or {}
    compiler_package_digest = evidence.get("compilerPackageDigest") or pin.get(
        "compilerPackageDigest"
    )
    pipeline_index_digest = evidence.get("pipelineIndexDigest") or pin.get("pipelineIndexDigest")
    resolved_dag = pin.get("resolvedDag") if isinstance(pin.get("resolvedDag"), dict) else {}
    resolved_dag_digest = evidence.get("resolvedDagDigest") or resolved_dag.get("digest")
    compiler_pipeline_digest = (
        pipeline_index_digest
        if isinstance(pipeline_index_digest, str) and pipeline_index_digest.strip()
        else compiler_package_digest
    )

    haystack = _haystack(
        decoded, requirement_facts, findings, attempts, transitions, validation_verdict
    )
    forbidden_haystack = _forbidden_haystack(decoded, requirement)
    missing_required = [
        fact
        for fact in required_facts
        if not _required_fact_present(fact, haystack, decoded)
    ]
    present_forbidden = [fact for fact in forbidden_facts if fact in forbidden_haystack]

    kinds = evidence.get("committedArtifactKinds") or []
    if not isinstance(kinds, list):
        raise ValueError("committedArtifactKinds must be an array")

    run_id = evidence.get("runId") or manifest.get("runId")
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError("evidence missing runId")

    terminal_state = evidence.get("currentState")
    if not isinstance(terminal_state, str) or not terminal_state.strip():
        raise ValueError("evidence missing currentState")

    knowledge_report_fields = {
        "knowledgePackage": dict(knowledge_package),
        "knowledgeContext": {
            "packageChecksum": context_ref["packageChecksum"],
            "objectIds": list(object_ids),
            "contentChars": content_chars,
        },
        "materializedElementTypes": _materialized_element_types(decoded),
    }

    return {
        "scenarioId": scenario_id,
        "rep": rep,
        "conversationId": evidence.get("conversationId"),
        "runId": run_id,
        "runtimeMode": runtime_mode,
        "profileId": profile_id,
        "profileVersion": str(profile_version),
        **knowledge_report_fields,
        "validationVerdict": validation_verdict,
        "approvalTargetHash": approval_target_hash if isinstance(approval_target_hash, str) else "",
        "approvedPlanContentHash": approved_plan_hash,
        "compilerPackageDigest": compiler_package_digest or "",
        "pipelineIndexDigest": pipeline_index_digest or "",
        "resolvedDagDigest": resolved_dag_digest or "",
        "compilerPipelineDigest": compiler_pipeline_digest or "",
        "materializedChainId": materialized_chain_id,
        "reconcileMatches": reconcile_matches,
        "graphPatchArtifacts": graph_patches,
        "terminalState": terminal_state,
        "expectedTerminalState": expected_terminal_state,
        "approvalEligible": approval_eligible,
        "requiredFacts": required_facts,
        "forbiddenFacts": forbidden_facts,
        "missingRequiredFacts": missing_required,
        "presentForbiddenFacts": present_forbidden,
        "committedArtifactKinds": kinds,
        "hasGeneratedBundle": False,  # legacy bundle kind removed after cutover
        "hasPublicationReceipt": False,  # legacy publication receipt removed after cutover
        "hasCatalogMutation": "CATALOG_MUTATION" in kinds,
        "hasDeploymentArtifact": "DEPLOYMENT_ARTIFACT" in kinds,
        "decodedPlan": plan,
        "requirementFacts": requirement_facts,
        "validationFindings": findings,
        "stub": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--scenario-id", required=True)
    parser.add_argument("--rep", type=int, required=True)
    parser.add_argument("--required-facts", required=True, help="JSON array")
    parser.add_argument("--forbidden-facts", required=True, help="JSON array")
    parser.add_argument("--expected-terminal-state", required=True)
    parser.add_argument(
        "--expects-approval",
        required=False,
        default="false",
        help="Ignored for report fields; kept for runner CLI compatibility",
    )
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    evidence = json.loads(open(args.evidence, encoding="utf-8").read())
    required = json.loads(args.required_facts)
    forbidden = json.loads(args.forbidden_facts)
    try:
        report = build_report(
            evidence,
            scenario_id=args.scenario_id,
            rep=args.rep,
            required_facts=required,
            forbidden_facts=forbidden,
            expected_terminal_state=args.expected_terminal_state,
        )
    except ValueError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
