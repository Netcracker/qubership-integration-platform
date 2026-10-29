"""Portable native PLAN proof controls; no service, private inputs, or model calls."""

import copy
import hashlib
import json
import unittest

from native_plan_proof import verify_plan_recovery


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def reference(row):
    return {key: row[key] for key in ("kind", "artifactId", "contentHash")}


def artifact(kind, identity, payload, owner, at, inputs=None, compilation="run"):
    return {
        "kind": kind,
        "artifactId": identity,
        "payload": payload,
        "contentHash": hashlib.sha256(encoded(payload).encode("utf-8")).hexdigest(),
        "compilationId": compilation,
        "producerId": owner,
        "createdAt": at,
        "inputs": inputs or [],
        "provenance": {"runId": "run", "stageId": owner, "capabilityId": owner},
    }


def fixture():
    """Independent native record shape, including post-response settlement timing."""
    manifest = artifact(
        "RUN_MANIFEST", "manifest", {"profile": "create"}, "manifest", 0
    )
    brief = artifact(
        "REQUIREMENT_BRIEF", "brief", {"facts": ["anchor", "delivery"]}, "analysis", 0
    )
    inputs = [reference(manifest), reference(brief)]
    original = {
        "chainIdentity": "Exact chain. λ\n",
        "operations": [
            {
                "nodeId": "entry",
                "elementType": "http-trigger",
                "sourceFactIds": ["delivery"],
            }
        ],
        "edges": [
            {
                "sourceNodeId": "entry",
                "targetNodeId": "processor",
                "routeKind": "SEQUENCE",
            }
        ],
    }
    stored = copy.deepcopy(original)
    stored.update(
        {
            key: []
            for key in (
                "sequenceRegions",
                "conditionRegions",
                "splitRegions",
                "loopRegions",
                "retryRegions",
                "errorScopeRegions",
                "containment",
            )
        }
    )
    stored["edges"][0].update(
        {
            "regionId": None,
            "branchId": None,
            "branchIds": [],
            "handlerId": None,
            "mappingIntentId": None,
        }
    )
    feedback = {
        "kind": "VALIDATION",
        "summary": "Required anchor provenance is missing.",
        "failureClass": "CORRECTABLE",
        "outerAllowed": True,
        "fieldHints": [],
        "repairHistoryJson": "",
        "noProgress": False,
        "evidenceReference": None,
        "issues": [],
    }
    observation = {
        "channel": "PLAN",
        "capabilityId": "design-input",
        "proposal": {
            "capabilityId": "design-input",
            "inputScope": "native-scope",
            "scope": "native-scope",
            "toolName": "captureChainSemanticRevision",
            "arguments": stored,
            "toolCallId": None,
        },
        "feedback": feedback,
    }
    rejected = artifact(
        "CAPTURE_ATTEMPT_FEEDBACK",
        "refused",
        observation,
        "capture-feedback",
        3,
        compilation="capture-compilation",
    )
    attached = copy.deepcopy(observation)
    attached["feedback"]["evidenceReference"] = reference(rejected)
    history = {"readOnly": True, "partial": False, "attempts": [attached]}
    failure_feedback = copy.deepcopy(attached["feedback"])
    failure_feedback["repairHistoryJson"] = encoded(history)
    capture_failure = {
        "feedback": failure_feedback,
        "acceptedBase": None,
        "rejectedCandidate": reference(rejected),
        "consequentialFindings": [],
    }
    cause = {
        "findings": [
            {
                "code": "CAPTURE_VALIDATION",
                "message": feedback["summary"],
                "blocker": True,
            }
        ],
        "noProgress": False,
        "causeCode": "CONTRACT_SHAPE",
        "requestedFact": "",
        "captureFailure": capture_failure,
    }
    recovery = artifact(
        "RECOVERY_EVIDENCE",
        "recovery",
        {
            "schemaVersion": 1,
            "failureId": "failure",
            "observingStageId": "design-input",
            "observedCauseCode": "CONTRACT_SHAPE",
            "findings": [{"rawValidatorJson": encoded(cause["findings"][0])}],
            "producerStageId": "design-input",
            "approvedBriefRef": reference(brief),
            "producerInputs": inputs,
            "captureFailure": capture_failure,
        },
        "design-input",
        4,
    )
    recovery["producerId"] = "product-pipeline-runtime"
    evidence_history = {
        "readOnly": True,
        "partial": False,
        "attempts": [
            {
                "evidenceRef": reference(recovery),
                "failureId": "failure",
                "producerStageId": "design-input",
                "inputs": inputs,
            }
        ],
    }
    prompt = "Approved native requirements. Preserve their exact values.\n"
    suffix = (
        "\n\nThe previous capture was rejected:\n- outcomeClass: CONTRACT_FAILURE\n"
        "- failedStageId: design-input\n- causeCode: CONTRACT_SHAPE\n- recoveryEvidenceRef: "
        + recovery["contentHash"]
        + "\n- typedCause: "
        + encoded(cause)
        + "\n"
        "Raw rejected output is retained in the halt evidence and is excluded from this request.\n"
        "<repair-history>"
        + encoded({**evidence_history, "projectionId": "evidence-projection"})
        + "</repair-history>\n"
        "\nRebuild the topology so this rejection cannot recur. Preserve the approved element"
        " placements. Call captureChainSemanticRevision once.\n"
        "<repair-history>"
        + encoded({**history, "projectionId": "native-projection"})
        + "</repair-history>\n"
    )
    parameters = {
        "modelName": "configured-model",
        "maxOutputTokens": 4000,
        "toolSpecifications": [{"name": "captureChainSemanticRevision"}],
        "toolChoice": None,
    }

    def request(identity, attempt, at, text):
        return {
            "id": identity,
            "runId": "run",
            "conversationId": "conversation",
            "capabilityId": "null",
            "context": {
                "attemptId": attempt,
                "runId": "run",
                "conversationId": "conversation",
                "stageId": "design-input",
                "inputRefs": copy.deepcopy(inputs),
            },
            "at": at,
            "redacted": False,
            "originalRequestHash": "same-" + identity,
            "redactedRequestHash": "same-" + identity,
            "request": {
                "parameters": copy.deepcopy(parameters),
                "messages": [
                    {"text": "Native semantic design"},
                    {"name": None, "attributes": {}, "contents": [{"text": text}]},
                ],
            },
        }

    def response(identity, call, at, capture):
        return {
            "requestId": identity,
            "kind": "response",
            "at": at,
            "value": {
                "message": {
                    "toolExecutionRequests": [
                        {
                            "id": call,
                            "name": "captureChainSemanticRevision",
                            "arguments": encoded({"capture": capture}),
                        }
                    ]
                }
            },
        }

    corrected = copy.deepcopy(original)
    corrected["operations"][0]["sourceFactIds"].insert(0, "anchor")
    boundary = [
        request("original-request", "original-invocation", 1, prompt),
        response("original-request", "original-call", 2, original),
        request("first-request", "fresh-corrected-invocation", 5, prompt + suffix),
        response("first-request", "corrected-call", 6, corrected),
    ]
    semantic = artifact(
        "CHAIN_SEMANTIC_REVISION",
        "semantic",
        {
            "schemaVersion": "chain-semantic-revision/v1",
            "revisionId": "semantic-native-revision",
            "chainIdentity": original["chainIdentity"],
            "compilerContractVersion": "native-contract/v1",
            "entryPoints": [{"nodeId": "entry"}],
            "nodes": [{"nodeId": "entry", "sourceFactIds": ["anchor", "delivery"]}],
            "regions": [],
            "executionEdges": [],
            "containment": [],
            "mappingIntents": [],
            "constraints": [],
            "assumptions": [],
            "citations": [],
        },
        "design-input",
        8,
        inputs,
    )
    implementation = artifact(
        "IMPLEMENTATION_PLAN", "implementation", {"steps": []}, "design-planning", 9.5
    )
    approval_inputs = [reference(semantic), reference(implementation)]
    approval = artifact(
        "APPROVAL_RECORD",
        "approval",
        {
            "target": reference(implementation),
            "targetContentHash": implementation["contentHash"],
            "approvedCandidates": approval_inputs,
            "actor": "user",
            "subjectArtifactKind": "CHAIN_SEMANTIC_REVISION",
            "subjectSchemaVersion": "chain-semantic-revision/v1",
            "subjectRevisionId": "semantic-native-revision",
            "compilerContractVersion": "native-contract/v1",
            "subjectSha256": "separate-native-subject-hash",
        },
        "design-planning",
        10,
        approval_inputs,
    )
    approval["producerId"] = "product-pipeline-runtime"
    graph = artifact(
        "CHAIN_PLAN_GRAPH",
        "consumed-graph",
        {"nodes": [{"nodeId": "entry"}], "edges": []},
        "design-execution",
        11,
        [reference(semantic), reference(approval), reference(implementation)],
    )

    def attrs(attempt):
        return encoded(
            {
                "stageErrorCauseJson": encoded(cause),
                "recoveryEvidenceRef": recovery["contentHash"],
                "recoveryProducerInvocation": encoded(
                    {
                        "attemptId": attempt,
                        "stageId": "design-input",
                        "inputs": inputs,
                        "requirementBaseline": None,
                    }
                ),
            }
        )

    attempts = [
        {
            "runRevision": 2,
            "stageId": "design-input",
            "outcome": "FAILED",
            "finishedAt": 4.5,
            "failureEvidence": attrs("original-invocation"),
            "outputs": [],
        },
        {
            "runRevision": 3,
            "stageId": "design-input",
            "outcome": "RUNNING",
            "finishedAt": 7,
            "failureEvidence": attrs("fresh-corrected-invocation"),
            "outputs": [],
        },
        {
            "runRevision": 4,
            "stageId": "design-input",
            "outcome": "SUCCEEDED",
            "finishedAt": 9,
            "failureEvidence": None,
            "outputs": [reference(semantic)],
        },
    ]
    evidence = {
        "runId": "run",
        "conversationId": "conversation",
        "pipelineArtifacts": [
            manifest,
            brief,
            recovery,
            semantic,
            implementation,
            approval,
            graph,
        ],
        "captureArtifacts": [rejected],
        "captureLinks": [
            {"compilationId": "capture-compilation", "conversationId": "conversation"}
        ],
        "providerBoundary": boundary,
        "runDocument": {
            "run": {"runId": "run", "conversationId": "conversation"},
            "attempts": attempts,
            "transitions": [
                {
                    "fromRevision": 2,
                    "toRevision": 3,
                    "stageId": "design-input",
                    "reason": "recovery-dispatched:2",
                },
                {
                    "fromRevision": 3,
                    "toRevision": 4,
                    "stageId": "design-input",
                    "reason": "semantic revision ready for planning",
                },
            ],
        },
    }
    return evidence, rejected, graph


class NativePlanProofTest(unittest.TestCase):  # pylint: disable=too-many-public-methods
    def setUp(self):
        self.evidence, self.rejected, self.graph = fixture()

    def verify(self):
        return verify_plan_recovery(self.evidence, self.rejected, self.graph)

    def refuses(self, expected=None):
        errors, proof = self.verify()
        self.assertTrue(errors)
        self.assertIsNone(proof)
        if expected:
            self.assertIn(expected, " ".join(errors))

    def row(self, kind):
        return next(
            row for row in self.evidence["pipelineArtifacts"] if row["kind"] == kind
        )

    def first(self):
        return self.evidence["providerBoundary"][2]

    def text(self, change):
        content = self.first()["request"]["messages"][1]["contents"][0]
        content["text"] = change(content["text"])

    def attributes(self, index, change):
        attempt = self.evidence["runDocument"]["attempts"][index]
        attrs = json.loads(attempt["failureEvidence"])
        change(attrs)
        attempt["failureEvidence"] = encoded(attrs)

    def test_native_commit_with_fresh_invocation_and_post_response_settlement(self):
        before = copy.deepcopy(self.evidence)
        errors, proof = self.verify()
        self.assertEqual([], errors)
        self.assertEqual(reference(self.graph), proof["graphRef"])
        self.assertEqual("fresh-corrected-invocation", proof["producerAttemptId"])
        self.assertEqual(
            [2, 3, 4],
            [
                proof[key]
                for key in ("failedRevision", "settlementRevision", "succeededRevision")
            ],
        )
        self.assertEqual(before, self.evidence)

    def test_declared_missing_and_null_list_defaults_match_native_dto(self):
        response = self.evidence["providerBoundary"][1]
        value = json.loads(
            response["value"]["message"]["toolExecutionRequests"][0]["arguments"]
        )
        value["capture"]["operations"][0]["sourceFactIds"] = ["delivery"]
        value["capture"]["conditionRegions"] = None
        value["capture"]["edges"][0]["branchIds"] = None
        response["value"]["message"]["toolExecutionRequests"][0]["arguments"] = encoded(
            value
        )
        self.assertEqual([], self.verify()[0])

    def test_null_evidence_fails_closed(self):
        self.evidence = None
        self.refuses("must be an object")

    def test_malformed_provider_collection_fails_closed(self):
        self.evidence["providerBoundary"] = {}
        self.refuses("must be an array")

    def test_malformed_artifact_row_fails_closed(self):
        self.evidence["pipelineArtifacts"].append(None)
        self.refuses("must be an object")

    def test_invalid_timestamp_fails_closed(self):
        self.first()["at"] = "not-time"
        self.refuses("timestamp")

    def test_malformed_provider_identity_fails_closed(self):
        self.first()["id"] = []
        self.refuses("requestId")

    def test_nonfinite_native_payload_fails_closed(self):
        self.row("CHAIN_SEMANTIC_REVISION")["payload"]["constraints"] = [float("nan")]
        self.refuses("complete JSON payload")

    def test_missing_reference_member_fails_closed(self):
        self.graph.pop("contentHash")
        self.refuses("Reference contentHash")

    def test_refused_payload_sha_covers_full_diagnostic(self):
        self.rejected["payload"]["feedback"]["summary"] += " changed"
        self.refuses("SHA")

    def test_foreign_capture_compilation_is_not_evidence(self):
        self.evidence["captureLinks"][0]["compilationId"] = "other"
        self.refuses("compilation")

    def test_matching_malformed_capture_compilations_do_not_establish_identity(self):
        for value in (None, "", " ", 7, [], {}, "missing"):
            with self.subTest(compilationId=value):
                self.evidence, self.rejected, self.graph = fixture()
                link = self.evidence["captureLinks"][0]
                if value == "missing":
                    self.rejected.pop("compilationId")
                    link.pop("compilationId")
                else:
                    self.rejected["compilationId"] = value
                    link["compilationId"] = value
                self.refuses("Capture compilationId")

    def test_equal_original_calls_are_ambiguous(self):
        repeated = copy.deepcopy(self.evidence["providerBoundary"][:2])
        repeated[0]["id"] = "another-original"
        repeated[1]["requestId"] = "another-original"
        self.evidence["providerBoundary"] += repeated
        self.refuses("Original model capture")

    def test_original_unknown_member_is_not_normalized_away(self):
        call = self.evidence["providerBoundary"][1]["value"]["message"][
            "toolExecutionRequests"
        ][0]
        args = json.loads(call["arguments"])
        args["capture"]["unknown"] = []
        call["arguments"] = encoded(args)
        self.refuses("Unknown capture")

    def test_original_literal_whitespace_is_not_normalized(self):
        call = self.evidence["providerBoundary"][1]["value"]["message"][
            "toolExecutionRequests"
        ][0]
        call["arguments"] = call["arguments"].replace("Exact chain.", "Exact  chain.")
        self.refuses("Original model capture")

    def test_malformed_tool_arguments_fails_closed(self):
        self.evidence["providerBoundary"][1]["value"]["message"][
            "toolExecutionRequests"
        ][0]["arguments"] = "{"
        self.refuses("malformed JSON")

    def test_first_next_cannot_be_replaced_by_later_good_request(self):
        earlier = copy.deepcopy(self.first())
        earlier.update(id="earlier", at=4.8)
        earlier["request"]["messages"][1]["contents"][0]["text"] = "No native feedback"
        self.evidence["providerBoundary"].append(earlier)
        self.refuses()

    def test_foreign_first_owner_is_not_accepted(self):
        self.first()["context"]["stageId"] = "other-owner"
        self.refuses("owner context")

    def test_changed_first_inputs_are_not_accepted(self):
        self.first()["context"]["inputRefs"][1]["contentHash"] = "other"
        self.refuses("input references")

    def test_redacted_provider_prompt_is_not_full_delivery(self):
        self.first()["redacted"] = True
        self.refuses("redacted")

    def test_parameter_change_is_not_hidden_by_repair(self):
        self.first()["request"]["parameters"]["maxOutputTokens"] += 1
        self.refuses("parameters")

    def test_original_prompt_must_remain_exact_prefix(self):
        self.text(lambda text: text.replace("requirements.", "requirements!", 1))
        self.refuses("original prompt")

    def test_summary_occurrence_is_not_typed_delivery(self):
        self.text(
            lambda text: "\n".join(
                (
                    "Required anchor provenance is missing."
                    if line.startswith("- typedCause: ")
                    else line
                )
                for line in text.split("\n")
            )
        )
        self.refuses("typed cause")

    def test_changed_typed_cause_is_not_immutable_evidence(self):
        self.text(
            lambda text: text.replace(
                '"consequentialFindings":[]',
                '"consequentialFindings":[{"code":"foreign"}]',
                1,
            )
        )
        self.refuses("capture failure differs")

    def test_partial_native_history_fails_even_with_full_summary(self):
        self.text(lambda text: text.replace('"partial":false', '"partial":true'))
        self.refuses("complete read-only header")

    def test_read_write_history_cannot_prove_delivery(self):
        self.text(lambda text: text.replace('"readOnly":true', '"readOnly":false'))
        self.refuses("complete read-only header")

    def test_missing_full_native_history_is_not_enough(self):
        self.text(lambda text: text[: text.rfind("<repair-history>")])
        self.refuses("current structured native history")

    def test_quoted_exact_objects_are_not_the_current_server_repair_envelope(self):
        self.text(
            lambda text: text.replace(
                "The previous capture was rejected:", "Quoted historical example:", 1
            )
        )
        self.refuses("section boundaries")

    def test_native_history_moved_into_diagnostic_is_not_current_carrier(self):
        def move(text):
            start = text.rfind("<repair-history>")
            block = text[start:]
            return text[:start].replace(
                "\nRebuild the topology", block + "\nRebuild the topology", 1
            )

        self.text(move)
        self.refuses("current structured native history")

    def test_foreign_native_observation_cannot_replace_current_history(self):
        self.text(lambda text: text.replace('"channel":"PLAN"', '"channel":"PATCH"'))
        self.refuses()

    def test_duplicate_corrected_calls_are_not_a_unique_native_commit(self):
        calls = self.evidence["providerBoundary"][3]["value"]["message"][
            "toolExecutionRequests"
        ]
        calls.append(copy.deepcopy(calls[0]))
        self.refuses("Corrected native tool call")

    def test_unchanged_corrected_capture_does_not_prove_correction(self):
        self.evidence["providerBoundary"][3]["value"]["message"][
            "toolExecutionRequests"
        ][0]["arguments"] = self.evidence["providerBoundary"][1]["value"]["message"][
            "toolExecutionRequests"
        ][
            0
        ][
            "arguments"
        ]
        self.refuses("unchanged")

    def test_provider_error_cannot_stand_in_for_capture(self):
        self.evidence["providerBoundary"][3]["kind"] = "error"
        self.refuses("complete normally")

    def test_tool_observations_are_not_extra_provider_completions(self):
        self.evidence["providerBoundary"].append(
            {
                "requestId": "original-request",
                "kind": "tool-observation",
                "at": 2.1,
                "value": {"name": "native observation"},
            }
        )
        self.assertEqual([], self.verify()[0])

    def test_duplicate_terminal_responses_are_still_ambiguous(self):
        self.evidence["providerBoundary"].append(
            copy.deepcopy(self.evidence["providerBoundary"][3])
        )
        self.refuses("Provider completion")

    def test_consistently_changed_current_cause_cannot_replace_immutable_findings(self):
        self.text(
            lambda text: text.replace(
                '"causeCode":"CONTRACT_SHAPE"', '"causeCode":"OTHER"'
            )
        )
        for index in (0, 1):

            def mutate(attrs):
                cause = json.loads(attrs["stageErrorCauseJson"])
                cause["causeCode"] = "OTHER"
                attrs["stageErrorCauseJson"] = encoded(cause)

            self.attributes(index, mutate)
        self.refuses("full immutable native findings")

    def test_native_output_without_matching_invocation_is_not_proof(self):
        self.attributes(
            1,
            lambda attrs: attrs.update(
                recoveryProducerInvocation=encoded(
                    {"attemptId": "foreign", "stageId": "design-input", "inputs": []}
                )
            ),
        )
        self.refuses("settlement")

    def test_saved_malformed_invocation_is_not_absence(self):
        self.attributes(
            1,
            lambda attrs: attrs.update(recoveryProducerInvocation="legacy-unparseable"),
        )
        self.refuses("malformed JSON")

    def test_durable_current_cause_must_match_first_packet(self):
        self.attributes(
            1, lambda attrs: attrs.update(stageErrorCauseJson='{"captureFailure":null}')
        )
        self.refuses("typed evidence differs")

    def test_settlement_reason_must_bind_failed_revision(self):
        self.evidence["runDocument"]["transitions"][0][
            "reason"
        ] = "recovery-dispatched:1"
        self.refuses("settlement reason")

    def test_intervening_stage_revision_breaks_native_transaction(self):
        self.evidence["runDocument"]["attempts"][1]["runRevision"] = 10
        self.refuses("Intervening stage attempt")

    def test_nonadjacent_success_cannot_be_substituted(self):
        self.evidence["runDocument"]["attempts"][2]["runRevision"] = 5
        self.refuses("Adjacent producer commit")

    def test_failed_native_producer_cannot_prove_acceptance(self):
        self.evidence["runDocument"]["attempts"][2]["outcome"] = "FAILED"
        self.refuses("adjacent success")

    def test_missing_native_output_reference_fails(self):
        self.evidence["runDocument"]["attempts"][2]["outputs"] = []
        self.refuses("semantic output")

    def test_full_semantic_sha_covers_every_canonical_field(self):
        semantic = self.row("CHAIN_SEMANTIC_REVISION")
        for key in (
            "schemaVersion",
            "revisionId",
            "chainIdentity",
            "compilerContractVersion",
            "entryPoints",
            "nodes",
            "regions",
            "executionEdges",
            "containment",
            "mappingIntents",
            "constraints",
            "assumptions",
            "citations",
        ):
            with self.subTest(field=key):
                old = semantic["payload"][key]
                semantic["payload"][key] = [{"unapproved": "change"}]
                self.refuses("SHA")
                semantic["payload"][key] = old

    def test_native_semantic_provenance_is_required(self):
        self.row("CHAIN_SEMANTIC_REVISION")["provenance"]["runId"] = "foreign-run"
        self.refuses("provenance")

    def test_other_semantic_input_ref_is_not_equal_approved_input(self):
        self.row("CHAIN_SEMANTIC_REVISION")["inputs"] = []
        self.refuses("other inputs")

    def test_equal_payload_foreign_revision_cannot_replace_committed_identity(self):
        twin = copy.deepcopy(self.row("CHAIN_SEMANTIC_REVISION"))
        twin["artifactId"] = "equal-but-uncommitted"
        self.evidence["pipelineArtifacts"].append(twin)
        self.graph["inputs"][0] = reference(twin)
        self.refuses("exact corrected semantic")

    def test_duplicate_artifact_reference_is_ambiguous(self):
        self.evidence["pipelineArtifacts"].append(
            copy.deepcopy(self.row("CHAIN_SEMANTIC_REVISION"))
        )
        self.refuses("Immutable artifact")

    def test_output_before_corrected_response_cannot_be_its_commit(self):
        self.row("CHAIN_SEMANTIC_REVISION")["createdAt"] = 5.5
        self.refuses("chronology")

    def test_intervening_provider_call_invalidates_unique_commit_link(self):
        extra = copy.deepcopy(self.first())
        extra.update(id="intervening", at=7.5)
        extra["context"]["attemptId"] = "other-attempt"
        self.evidence["providerBoundary"].append(extra)
        self.refuses("Intervening provider")

    def test_graph_must_consume_exact_approval_record(self):
        self.graph["inputs"] = [
            ref for ref in self.graph["inputs"] if ref["kind"] != "APPROVAL_RECORD"
        ]
        self.refuses("Graph approval")

    def test_approval_subject_must_bind_native_revision(self):
        approval = self.row("APPROVAL_RECORD")
        approval["payload"]["subjectRevisionId"] = "unconsumed-revision"
        approval["contentHash"] = hashlib.sha256(
            encoded(approval["payload"]).encode()
        ).hexdigest()
        self.graph["inputs"][1] = reference(approval)
        self.refuses("exact native semantic subject")

    def test_graph_cannot_substitute_equal_unapproved_plan_target(self):
        self.graph["inputs"][2]["artifactId"] = "another-plan"
        self.refuses("Approval target")


if __name__ == "__main__":
    unittest.main()
