"""Verify native semantic-capture recovery from immutable exported server records.

This proves delivery and the native invocation-to-commit link, not a second semantic
adapter. Callers must retain their graph-to-bundle/materialization checks. Payload
SHA-256 uses compact UTF-8 JSON in exported insertion order, including every null
and empty value; keys are never sorted or projected for artifact hashing.
"""

import copy
import hashlib
import json
import math
import re

import recovery_campaign as campaign


_TOOL = "captureChainSemanticRevision"
_REPAIR_HEADER = "\n\nThe previous capture was rejected:\n"
_REBUILD = ("\nRebuild the topology so this rejection cannot recur. Preserve the approved element"
            " placements. Call captureChainSemanticRevision once.")
_SEMANTIC_FIELDS = {
    "schemaVersion", "revisionId", "chainIdentity", "compilerContractVersion",
    "entryPoints", "nodes", "regions", "executionEdges", "containment",
    "mappingIntents", "constraints", "assumptions", "citations",
}
# Only declared ChainSemanticCapture DTO members have transport defaults. This
# comparison never removes arbitrary nulls, empty collections, or unknown fields.
_DTO = {
    "capture": ("chainIdentity", {
        "operations": "operation", "sequenceRegions": "sequence",
        "conditionRegions": "condition", "splitRegions": "split",
        "loopRegions": "loop", "retryRegions": "retry",
        "errorScopeRegions": "error", "edges": "edge", "containment": "containment",
    }),
    "operation": ("nodeId elementType", {"sourceFactIds": None}),
    "sequence": ("regionId", {"memberNodeIds": None}),
    "condition": ("regionId ownerNodeId reconvergenceNodeId", {"branches": "conditionBranch"}),
    "conditionBranch": ("branchId role predicate priority entryNodeId", {"exitNodeIds": None}),
    "split": ("regionId ownerNodeId mode reconvergenceNodeId mainBranchId", {"branches": "splitBranch"}),
    "splitBranch": ("branchId order entryNodeId", {"exitNodeIds": None}),
    "loop": ("regionId ownerNodeId bodyEntryNodeId exitNodeId loopMode loopExpression loopSafetyBound",
             {"bodyExitNodeIds": None}),
    "retry": ("regionId ownerNodeId bodyEntryNodeId exhaustedNodeId retryCount retryDelayMillis",
              {"bodyExitNodeIds": None}),
    "error": ("regionId ownerNodeId tryEntryNodeId finallyEntryNodeId",
              {"handlers": "handler", "exitNodeIds": None}),
    "handler": ("handlerId exceptionClass entryNodeId", {"exitNodeIds": None}),
    "edge": ("sourceNodeId targetNodeId regionId routeKind branchId handlerId mappingIntentId", {"branchIds": None}),
    "containment": ("parentNodeId childNodeId role", {}),
}


class _InvalidProof(ValueError):
    pass


def _need(condition, message):
    if not condition:
        raise _InvalidProof(message)


def _obj(value, label):
    _need(isinstance(value, dict), label + " must be an object")
    return value


def _list(value, label):
    _need(isinstance(value, list), label + " must be an array")
    return value


def _string(value, label):
    _need(isinstance(value, str) and bool(value.strip()), label + " must be a nonempty string")
    return value


def _time(value):
    _need(isinstance(value, (int, float, str)) and not isinstance(value, bool), "Invalid native timestamp")
    try:
        result = campaign.native_time(value)
    except (ValueError, OverflowError):
        raise _InvalidProof("Invalid native timestamp") from None
    _need(math.isfinite(result), "Invalid native timestamp")
    return result


def _json(value, label):
    _need(isinstance(value, str), label + " must be JSON text")
    try:
        return json.loads(value)
    except ValueError:
        raise _InvalidProof(label + " is malformed JSON") from None


def _ref(value):
    value = _obj(value, "Artifact reference")
    return {key: _string(value.get(key), "Reference " + key)
            for key in ("kind", "artifactId", "contentHash")}


def _refs(value):
    result = [_ref(item) for item in _list(value, "Input references")]
    _need(len({tuple(ref.values()) for ref in result}) == len(result), "Duplicate input reference")
    return result


def _one(values, label):
    _need(len(values) == 1, label + " must have exactly one match")
    return values[0]


def _sha(payload):
    try:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, UnicodeError):
        raise _InvalidProof("Artifact is not a complete JSON payload") from None
    return hashlib.sha256(raw).hexdigest()


def _capture(value, family="capture"):
    value = _obj(value, "Capture " + family)
    scalars, lists = _DTO[family]
    scalars = scalars.split()
    _need(set(value) <= set(scalars) | set(lists), "Unknown capture DTO member")
    result = {key: value.get(key) for key in scalars}
    _need(all(child is None or type(child) in (str, int) for child in result.values()),
          "Invalid capture scalar")
    for key, child_family in lists.items():
        children = value.get(key)
        children = [] if children is None else _list(children, "Capture " + key)
        if child_family:
            result[key] = [_capture(child, child_family) for child in children]
        else:
            _need(all(isinstance(child, str) for child in children), "Invalid capture string array")
            result[key] = children
    return result


def _history(value):
    value = _obj(value, "Repair history")
    _need(set(value) <= {"readOnly", "partial", "attempts", "projectionId"}, "Unknown history header")
    _need(value.get("readOnly") is True and value.get("partial") is False,
          "Repair history is missing its complete read-only header")
    if "projectionId" in value:
        _string(value["projectionId"], "History projectionId")
    _list(value.get("attempts"), "Repair attempts")
    return {key: value[key] for key in ("readOnly", "partial", "attempts")}


def _user_text(request):
    messages = _list(_obj(request, "Provider request").get("messages"), "Messages")
    _need(len(messages) == 2, "Expected exact system/user capture request")
    _obj(messages[0], "System message")
    user = _obj(messages[1], "User message")
    contents = _list(user.get("contents"), "User contents")
    _need(len(contents) == 1 and set(_obj(contents[0], "User content")) == {"text"},
          "Expected one unmodified text content")
    return _string(contents[0]["text"], "User text")


def _invocation(attempt):
    failure = _obj(_json(attempt.get("failureEvidence"), "Failure evidence"), "Failure evidence")
    invocation = _obj(_json(failure.get("recoveryProducerInvocation"), "Producer invocation"), "Producer invocation")
    _string(invocation.get("attemptId"), "Invocation attemptId")
    _string(invocation.get("stageId"), "Invocation stageId")
    _refs(invocation.get("inputs"))
    return failure, invocation


def _verify(evidence, rejected, graph_row):
    evidence = _obj(evidence, "Evidence")
    run, conversation = (_string(evidence.get(key), key) for key in ("runId", "conversationId"))
    artifacts = [_obj(row, "Artifact") for row in
                 _list(evidence.get("pipelineArtifacts"), "Pipeline artifacts")
                 + _list(evidence.get("captureArtifacts"), "Capture artifacts")]

    def resolve(reference):
        reference = _ref(reference)
        return _one([row for row in artifacts if _ref(row) == reference], "Immutable artifact")

    def immutable(row):
        _need(resolve(row) == row, "Supplied artifact differs from immutable record")
        payload = _obj(row.get("payload"), "Artifact payload")
        _need(_sha(payload) == row["contentHash"], "Full native payload SHA mismatch")
        return payload

    def provenance(row, owner, producer=None):
        actual = _obj(row.get("provenance"), "Artifact provenance")
        _need(row.get("compilationId") == run and row.get("producerId") == (producer or owner)
              and all(actual.get(key) == value for key, value in
                      (("runId", run), ("stageId", owner), ("capabilityId", owner))),
              "Artifact run/stage/capability provenance differs")

    rejected = _obj(rejected, "Rejected artifact")
    _need(rejected.get("kind") == "CAPTURE_ATTEMPT_FEEDBACK" and rejected.get("producerId") == "capture-feedback",
          "Not immutable native capture feedback")
    observation = immutable(rejected)
    _need(observation.get("channel") == "PLAN", "Not PLAN feedback")
    owner = _string(observation.get("capabilityId"), "Native capability")
    proposal = _obj(observation.get("proposal"), "Native proposal")
    _need(proposal.get("toolName") == _TOOL and proposal.get("capabilityId") == owner,
          "Unsupported native PLAN tool or owner")
    scope = _string(proposal.get("inputScope"), "Native input scope")
    _need(proposal.get("scope") == scope, "Native capture scope differs")
    original_value = _capture(proposal.get("arguments"))
    feedback = _obj(observation.get("feedback"), "Native feedback")
    _need(set(feedback) == {"kind", "summary", "failureClass", "outerAllowed", "fieldHints",
                            "repairHistoryJson", "noProgress", "evidenceReference", "issues"},
          "Incomplete native feedback")
    _need(feedback.get("kind") == "VALIDATION" and feedback.get("failureClass") == "CORRECTABLE"
          and feedback.get("outerAllowed") is True and feedback.get("noProgress") is False,
          "PLAN feedback does not permit native correction")
    _string(feedback.get("summary"), "Native diagnostic")
    _list(feedback.get("issues"), "Native issues")
    _list(feedback.get("fieldHints"), "Native hints")
    capture_compilation = _string(rejected.get("compilationId"), "Capture compilationId")
    _need(any(_obj(link, "Capture link").get("compilationId") == capture_compilation
              and link.get("conversationId") == conversation
              for link in _list(evidence.get("captureLinks"), "Capture links")), "Foreign capture compilation")
    refused_at = _time(rejected.get("createdAt"))

    boundary = [_obj(row, "Provider boundary") for row in _list(evidence.get("providerBoundary"), "Provider boundary")]
    requests = [row for row in boundary if "request" in row and row.get("runId") == run]
    for request in requests:
        _string(request.get("id"), "Provider requestId")
    _need(len({row.get("id") for row in requests}) == len(requests), "Duplicate provider request identity")

    def context(request):
        _need(request.get("conversationId") == conversation, "Foreign provider conversation")
        ctx = _obj(request.get("context"), "Provider invocation context")
        _need(ctx.get("runId") == run and ctx.get("conversationId") == conversation
              and ctx.get("stageId") == owner, "Foreign provider owner context")
        _string(ctx.get("attemptId"), "Provider attemptId")
        _refs(ctx.get("inputRefs"))
        _need(request.get("redacted") is False
              and request.get("originalRequestHash") == request.get("redactedRequestHash")
              and bool(request.get("originalRequestHash")), "Provider request is redacted or lacks identity")
        return ctx

    def calls(request):
        # Tool observations share requestId but are not provider completions.
        response = _one([row for row in boundary if row.get("requestId") == request.get("id")
                         and row.get("kind") in ("response", "error")], "Provider completion")
        _need(response.get("kind") == "response", "Provider capture did not complete normally")
        ended = _time(response.get("at"))
        _need(_time(request.get("at")) <= ended, "Provider response precedes request")
        message = _obj(_obj(response.get("value"), "Provider response").get("message"), "Response message")
        tools = message.get("toolExecutionRequests")
        return [] if tools is None else _list(tools, "Provider tool calls"), ended

    originals = []
    for request in requests:
        if _time(request.get("at")) >= refused_at:
            continue
        responses = [row for row in boundary if row.get("requestId") == request.get("id") and row.get("kind") == "response"]
        if not responses:
            continue
        tools, ended = calls(request)
        for call in tools:
            call = _obj(call, "Tool call")
            if call.get("name") != _TOOL:
                continue
            _string(call.get("id"), "Original callId")
            args = _obj(_json(call.get("arguments"), "Tool arguments"), "Tool arguments")
            _need(set(args) == {"capture"}, "Unknown semantic tool argument")
            if _capture(args["capture"]) == original_value and ended <= refused_at:
                originals.append((request, call, ended))
    original, original_call, original_end = _one(originals, "Original model capture")
    original_ctx = context(original)
    _need(proposal.get("toolCallId") is None or proposal["toolCallId"] == original_call.get("id"),
          "Rejected proposal callId differs")
    following = sorted([row for row in requests if _time(row.get("at")) > refused_at], key=lambda row: _time(row["at"]))
    _need(bool(following), "No FIRST next provider request")
    first = following[0]
    first_ctx = context(first)
    _need(first_ctx["inputRefs"] == original_ctx["inputRefs"], "Repair changed approved input references")
    _need(sum(row.get("context", {}).get("attemptId") == first_ctx["attemptId"] for row in requests
              if isinstance(row.get("context"), dict)) == 1, "Ambiguous corrected provider invocation")
    original_request = _obj(original.get("request"), "Original provider request")
    first_request = _obj(first.get("request"), "FIRST provider request")
    _need(original_request.get("parameters") == first_request.get("parameters"), "Repair provider parameters changed")
    specs = _list(_obj(first_request.get("parameters"), "Provider parameters").get("toolSpecifications"), "Tools")
    _need(len(specs) == 1 and _obj(specs[0], "Tool specification").get("name") == _TOOL,
          "Repair is not the native semantic capture invocation")
    before, after = _user_text(original_request), _user_text(first_request)
    _need(original_request["messages"][0] == first_request["messages"][0]
          and {k: v for k, v in original_request["messages"][1].items() if k != "contents"}
          == {k: v for k, v in first_request["messages"][1].items() if k != "contents"}
          and after.startswith(before) and len(after) > len(before), "Repair changed original prompt or message metadata")
    suffix = after[len(before):]
    _need(suffix.startswith(_REPAIR_HEADER) and suffix.count(_REBUILD) == 1,
          "Missing current native repair section boundaries")
    diagnostic, native_section = suffix[len(_REPAIR_HEADER):].split(_REBUILD)
    causes = re.findall(r"^- typedCause: (.+)$", diagnostic, re.MULTILINE)
    typed = _obj(_json(_one(causes, "Current typed cause"), "Typed cause"), "Typed cause")
    def history_blocks(section):
        return [_history(_json(value, "Repair history")) for value in
                re.findall(r"<repair-history>(.*?)</repair-history>", section, re.DOTALL)]
    diagnostic_histories, native_histories = history_blocks(diagnostic), history_blocks(native_section)
    _need(native_section.startswith("\n<repair-history>") and len(native_histories) == 1,
          "FIRST request has no current structured native history")

    recoveries = []
    for row in artifacts:
        if row.get("kind") != "RECOVERY_EVIDENCE":
            continue
        payload = _obj(row.get("payload"), "Recovery evidence")
        failure = payload.get("captureFailure")
        if isinstance(failure, dict) and failure.get("rejectedCandidate") == _ref(rejected):
            recoveries.append(row)
    recovery = _one(recoveries, "Current immutable recovery evidence")
    recovery_payload = immutable(recovery)
    provenance(recovery, owner, "product-pipeline-runtime")
    for line in ("- outcomeClass: CONTRACT_FAILURE", "- failedStageId: " + owner,
                 "- causeCode: " + _string(recovery_payload.get("observedCauseCode"), "Observed causeCode"),
                 "- recoveryEvidenceRef: " + recovery["contentHash"]):
        _need(diagnostic.splitlines().count(line) == 1, "Current native repair diagnostic header differs")
    failure = _obj(recovery_payload.get("captureFailure"), "Capture failure")
    _need(typed.get("captureFailure") == failure, "FIRST typed capture failure differs from immutable evidence")
    native_findings = [_obj(_json(_obj(finding, "Recovery finding").get("rawValidatorJson"),
                                 "Native validator finding"), "Native validator finding")
                       for finding in _list(recovery_payload.get("findings"), "Recovery findings")]
    _need(set(typed) == {"findings", "noProgress", "causeCode", "requestedFact", "captureFailure"}
          and typed.get("findings") == native_findings
          and typed.get("causeCode") == _string(recovery_payload.get("observedCauseCode"), "Observed causeCode")
          and typed.get("noProgress") is False and typed.get("requestedFact") == "",
          "Current typed cause differs from full immutable native findings")
    native_feedback = _obj(failure.get("feedback"), "Attached capture feedback")
    expected_feedback = copy.deepcopy(feedback)
    expected_feedback["evidenceReference"] = _ref(rejected)
    native_history = _history(_json(native_feedback.get("repairHistoryJson"), "Native full history"))
    expected_feedback["repairHistoryJson"] = native_feedback["repairHistoryJson"]
    _need(native_feedback == expected_feedback, "Native feedback lost or changed immutable fields")
    expected_observation = copy.deepcopy(observation)
    expected_observation["feedback"]["evidenceReference"] = _ref(rejected)
    _need(expected_observation in native_history["attempts"] and native_histories == [native_history],
          "FIRST request lacks equal full immutable native history")
    for historical in native_history["attempts"]:
        historical = _obj(historical, "Historical observation")
        historical_feedback = _obj(historical.get("feedback"), "Historical feedback")
        stored = resolve(historical_feedback.get("evidenceReference"))
        expected = copy.deepcopy(immutable(stored))
        _obj(expected.get("feedback"), "Stored historical feedback")["evidenceReference"] = _ref(stored)
        historical_proposal = _obj(historical.get("proposal"), "Historical proposal")
        _need(historical == expected and historical.get("capabilityId") == owner
              and historical_proposal.get("inputScope") == scope,
              "Native history contains a foreign or incomplete observation")
    _need(recovery_payload.get("producerStageId") == owner and recovery_payload.get("observingStageId") == owner
          and recovery_payload.get("producerInputs") == first_ctx["inputRefs"]
          and recovery_payload.get("approvedBriefRef") in first_ctx["inputRefs"], "Recovery evidence has foreign producer inputs")
    _need(any(any(isinstance(item, dict) and item.get("evidenceRef") == _ref(recovery)
                      and item.get("failureId") == recovery_payload.get("failureId")
                      and item.get("producerStageId") == owner and item.get("inputs") == first_ctx["inputRefs"]
                      for item in history["attempts"]) for history in diagnostic_histories), "FIRST request lost recovery evidence identity")

    tools, corrected_end = calls(first)
    corrected_call = _obj(_one(tools, "Corrected native tool call"), "Corrected native tool call")
    _need(corrected_call.get("name") == _TOOL, "FIRST correction uses a foreign tool")
    _string(corrected_call.get("id"), "Corrected callId")
    args = _obj(_json(corrected_call.get("arguments"), "Corrected arguments"), "Corrected arguments")
    _need(set(args) == {"capture"} and _capture(args["capture"]) != original_value, "Corrected capture is absent or unchanged")

    document = _obj(evidence.get("runDocument"), "Run document")
    current_run = _obj(document.get("run"), "Durable run")
    _need(current_run.get("runId") == run and current_run.get("conversationId") == conversation, "Foreign durable run")
    attempts = [_obj(row, "Stage attempt") for row in _list(document.get("attempts"), "Stage attempts")]
    _need(all(type(row.get("runRevision")) is int for row in attempts)
          and len({row["runRevision"] for row in attempts}) == len(attempts), "Ambiguous stage revision")
    failed = []
    settlements = []
    for attempt in attempts:
        if attempt.get("stageId") != owner or attempt.get("outcome") not in ("FAILED", "RUNNING") or not attempt.get("failureEvidence"):
            continue
        attrs, invocation = _invocation(attempt)
        if invocation.get("attemptId") == original_ctx["attemptId"] and attempt["outcome"] == "FAILED":
            failed.append((attempt, attrs, invocation))
        if invocation.get("attemptId") == first_ctx["attemptId"] and attempt["outcome"] == "RUNNING":
            settlements.append((attempt, attrs, invocation))
    failed_attempt, failed_attrs, failed_invocation = _one(failed, "Original failed producer commit")
    settlement, settled_attrs, settled_invocation = _one(settlements, "Corrected producer settlement")
    for attrs, invocation in ((failed_attrs, failed_invocation), (settled_attrs, settled_invocation)):
        _need(invocation.get("stageId") == owner and invocation.get("inputs") == first_ctx["inputRefs"]
              and attrs.get("recoveryEvidenceRef") == recovery["contentHash"]
              and _json(attrs.get("stageErrorCauseJson"), "Durable typed cause") == typed,
              "Durable producer invocation or typed evidence differs")
    failed_revision = failed_attempt["runRevision"]
    _need(settlement["runRevision"] == failed_revision + 1, "Intervening stage attempt before settlement")
    success = _one([row for row in attempts if row["runRevision"] == settlement["runRevision"] + 1], "Adjacent producer commit")
    _need(success.get("stageId") == owner and success.get("outcome") == "SUCCEEDED", "Native producer did not commit adjacent success")
    transitions = [_obj(row, "Transition") for row in _list(document.get("transitions"), "Transitions")]
    for prior, later in ((failed_attempt, settlement), (settlement, success)):
        transition = _one([row for row in transitions if row.get("fromRevision") == prior["runRevision"]
                           and row.get("toRevision") == later["runRevision"]], "Native commit transition")
        _need(transition.get("stageId") == owner, "Foreign commit transition")
        if later is settlement:
            _need(transition.get("reason") == "recovery-dispatched:" + str(failed_revision), "Missing exact recovery settlement reason")
    semantic_ref = _one([ref for ref in _refs(success.get("outputs")) if ref["kind"] == "CHAIN_SEMANTIC_REVISION"], "Native semantic output")
    semantic = resolve(semantic_ref)
    semantic_payload = immutable(semantic)
    provenance(semantic, owner)
    _need(set(semantic_payload) == _SEMANTIC_FIELDS and semantic_payload.get("schemaVersion") == "chain-semantic-revision/v1",
          "Incomplete native semantic payload")
    for key in ("revisionId", "chainIdentity", "compilerContractVersion"):
        _string(semantic_payload.get(key), "Semantic " + key)
    _need(_refs(semantic.get("inputs")) == first_ctx["inputRefs"], "Native semantic output consumed other inputs")
    _need(original_end <= refused_at <= _time(failed_attempt.get("finishedAt")) < _time(first.get("at"))
          <= corrected_end <= _time(settlement.get("finishedAt")) <= _time(semantic.get("createdAt"))
          <= _time(success.get("finishedAt")), "Native invocation/commit chronology differs")
    _need(not any(row is not first and _time(first["at"]) <= _time(row["at"]) <= _time(success["finishedAt"])
                  for row in requests), "Intervening provider invocation before native commit")

    graph_row = _obj(graph_row, "Consumed graph")
    _need(graph_row.get("kind") == "CHAIN_PLAN_GRAPH", "Consumer is not a graph")
    immutable(graph_row)
    provenance(graph_row, "design-execution")
    graph_inputs = _refs(graph_row.get("inputs"))
    _need(semantic_ref in graph_inputs, "Supplied graph did not consume exact corrected semantic revision")
    approval = resolve(_one([ref for ref in graph_inputs if ref["kind"] == "APPROVAL_RECORD"], "Graph approval"))
    approval_payload = immutable(approval)
    provenance(approval, "design-planning", "product-pipeline-runtime")
    _need(semantic_ref in _refs(approval.get("inputs"))
          and semantic_ref in _refs(approval_payload.get("approvedCandidates"))
          and approval_payload.get("subjectArtifactKind") == semantic_ref["kind"]
          and approval_payload.get("subjectSchemaVersion") == semantic_payload["schemaVersion"]
          and approval_payload.get("subjectRevisionId") == semantic_payload["revisionId"]
          and approval_payload.get("compilerContractVersion") == semantic_payload["compilerContractVersion"],
          "Approval does not bind the exact native semantic subject")
    target = _ref(approval_payload.get("target"))
    _need(target in graph_inputs and target in _refs(approval.get("inputs"))
          and approval_payload.get("targetContentHash") == target["contentHash"], "Approval target is not consumed by supplied graph")
    _need(_time(success["finishedAt"]) <= _time(approval.get("createdAt")) <= _time(graph_row.get("createdAt")),
          "Graph/approval predates corrected native commit")
    return {"refusalRef": _ref(rejected), "recoveryEvidenceRef": _ref(recovery),
            "firstNextRequestId": first["id"], "originalRequestId": original["id"],
            "originalCallId": original_call["id"], "correctedCallId": corrected_call["id"],
            "producerAttemptId": first_ctx["attemptId"], "failedRevision": failed_revision,
            "settlementRevision": settlement["runRevision"], "succeededRevision": success["runRevision"],
            "semanticRef": semantic_ref, "approvalRef": _ref(approval), "graphRef": _ref(graph_row)}


def verify_plan_recovery(evidence, rejected, graph_row):
    """Return errors and one proof; malformed or unsupported evidence fails closed.

    Records must be the immutable native export. Provider observations alone are
    not accepted capture receipts. Native producer commits supply that linkage;
    the caller still verifies downstream bundle, materialization, and catalog use.
    """
    try:
        return [], _verify(evidence, rejected, graph_row)
    except _InvalidProof as error:
        return ["Native PLAN proof: " + str(error)], None
