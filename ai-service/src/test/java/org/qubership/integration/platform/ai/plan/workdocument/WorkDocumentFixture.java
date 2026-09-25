package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

/** One document that grows from a logical flow, to a resolved binding, to mapping rules. */
final class WorkDocumentFixture {

  static final String SOURCE_ID = "source-request";

  private final String triggerId;
  private final String callId;
  private final String repeatCallId;
  private final String transferId;
  private final String subjectRuleId;
  private final String statusRuleId;
  private final String priorityRuleId;
  private final WorkDocumentState afterFlow;
  private final WorkDocumentState afterBinding;
  private final WorkDocumentState afterMappings;

  private WorkDocumentFixture(
      String triggerId,
      String callId,
      String repeatCallId,
      String transferId,
      String subjectRuleId,
      String statusRuleId,
      String priorityRuleId,
      WorkDocumentState afterFlow,
      WorkDocumentState afterBinding,
      WorkDocumentState afterMappings) {
    this.triggerId = triggerId;
    this.callId = callId;
    this.repeatCallId = repeatCallId;
    this.transferId = transferId;
    this.subjectRuleId = subjectRuleId;
    this.statusRuleId = statusRuleId;
    this.priorityRuleId = priorityRuleId;
    this.afterFlow = afterFlow;
    this.afterBinding = afterBinding;
    this.afterMappings = afterMappings;
  }

  static GrownDocument grow(WorkDocumentService service) {
    WorkDocumentState created =
        service.read(
            WorkDocumentState.create(
                "doc-orders",
                List.of(
                    new WorkSource(
                        SOURCE_ID,
                        "request",
                        "artifact://request",
                        "hash-request",
                        "request.md",
                        "REQ-1",
                        List.of()))));
    WorkCommit flow =
        service.apply(
            created,
            scope(created, "flow", List.of(), true, false, false),
            WorkTaskCapture.prepared(
                List.of(
                    new CapturedRequirement(
                        "", "req", "Create a Salesforce task", List.of(SOURCE_ID), "")),
                List.of(
                    new CapturedStep(
                        "", "trigger", StepKind.TRIGGER, "Order", "Receive the order", List.of(SOURCE_ID), List.of("req")),
                    new CapturedStep(
                        "", "call", StepKind.SERVICE_CALL, "Create task", "Create the WFM task", List.of(SOURCE_ID), List.of("req")),
                    new CapturedStep(
                        "",
                        "call-again",
                        StepKind.SERVICE_CALL,
                        "Create task again",
                        "Create a second WFM task",
                        List.of(SOURCE_ID),
                        List.of("req"))),
                List.of(
                    new CapturedConnection("", "to-call", "trigger", "success", "call", "Then create", List.of(SOURCE_ID)),
                    new CapturedConnection("", "to-again", "call", "success", "call-again", "Then repeat", List.of(SOURCE_ID))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            "cmd-flow");
    String triggerId = flow.aliasToId().get("trigger");
    String callId = flow.aliasToId().get("call");
    String repeatCallId = flow.aliasToId().get("call-again");
    String requirementId = flow.aliasToId().get("req");
    ResolvedWorkBinding binding =
        new ResolvedWorkBinding(
            "catalog-salesforce",
            "1.0.0",
            "createTask",
            List.of("contract-create-task"),
            List.of("request", "success", "failure"));
    WorkDocumentState bound = service.attachResolvedBinding(flow.state(), callId, binding);
    bound = service.attachResolvedBinding(bound, repeatCallId, binding);
    WorkCommit mapped =
        service.apply(
            bound,
            scope(bound, "mapping", List.of(callId), true, false, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedTransfer(
                        "",
                        "transfer",
                        callId,
                        List.of(new PortRef(triggerId, "payload")),
                        new PortRef(callId, "request"),
                        List.of(requirementId),
                        "")),
                List.of(
                    subject(triggerId, callId),
                    status(callId),
                    priority(triggerId, callId)),
                List.of()),
            "cmd-mapping");
    return new GrownDocument(
        triggerId,
        callId,
        repeatCallId,
        mapped.aliasToId().get("transfer"),
        mapped.aliasToId().get("subject"),
        mapped.aliasToId().get("status"),
        mapped.aliasToId().get("priority"),
        flow.state(),
        bound,
        mapped.state());
  }

  private static CapturedRule subject(String triggerId, String callId) {
    return new CapturedRule(
        "",
        "subject",
        "transfer",
        List.of(
            FieldReference.payload(triggerId, PortRole.INBOUND_PAYLOAD, "$.name"),
            FieldReference.payload(triggerId, PortRole.INBOUND_PAYLOAD, "$.subRequestType"),
            FieldReference.payload(triggerId, PortRole.INBOUND_PAYLOAD, "$.orderId")),
        FieldReference.payload(callId, PortRole.OUTBOUND_REQUEST, "$.Subject"),
        List.of(),
        "Join name, subRequestType, and orderId. Fall back to orderId.",
        List.of(SOURCE_ID));
  }

  private static CapturedRule status(String callId) {
    return new CapturedRule(
        "",
        "status",
        "transfer",
        List.of(),
        FieldReference.payload(callId, PortRole.OUTBOUND_REQUEST, "$.Status"),
        List.of(new JsonConstant("status", JsonNodeFactory.instance.textNode("Not Started"))),
        "Set Status to Not Started.",
        List.of(SOURCE_ID));
  }

  private static CapturedRule priority(String triggerId, String callId) {
    return new CapturedRule(
        "",
        "priority",
        "transfer",
        List.of(FieldReference.payload(triggerId, PortRole.INBOUND_PAYLOAD, "$.priority")),
        FieldReference.payload(callId, PortRole.OUTBOUND_REQUEST, "$.Priority"),
        List.of(),
        "Map high to High.",
        List.of(SOURCE_ID));
  }

  private static WorkTaskScope scope(
      WorkDocumentState state, String taskId, List<String> owned, boolean create, boolean replace, boolean delete) {
    return new WorkTaskScope(
        taskId,
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        "skill",
        owned,
        create,
        replace,
        delete,
        List.of(),
        List.of(),
        create ? CreationAllowance.anyParent(WorkRecordKind.values()) : List.of(),
        replace ? owned : List.of(),
        taskId,
        WorkTaskKind.UNSPECIFIED,
        "",
        null);
  }
}

record GrownDocument(
    String triggerId,
    String callId,
    String repeatCallId,
    String transferId,
    String subjectRuleId,
    String statusRuleId,
    String priorityRuleId,
    WorkDocumentState afterFlow,
    WorkDocumentState afterBinding,
    WorkDocumentState afterMappings) {}
