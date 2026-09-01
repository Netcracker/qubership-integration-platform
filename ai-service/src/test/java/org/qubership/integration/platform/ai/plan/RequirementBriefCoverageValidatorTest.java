package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class RequirementBriefCoverageValidatorTest {

  private final RequirementBriefCoverageValidator validator = new RequirementBriefCoverageValidator();

  @Test
  void emptyApprovedDraftFactsAreCoverageNoOp() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("geo-site", Direction.INBOUND, "Caller", "GET /geo-site", "")),
            List.of());
    RequirementDraft approved =
        new RequirementDraft(true, "Proxy Geographic Site GET-by-id").withFlow(flow);
    RequirementBrief brief =
        RequirementBriefProjector.project(
            new RequirementBrief(
                    "Proxy Geographic Site",
                    List.of("id path param"),
                    List.of("accessControlType NONE"),
                    List.of(),
                    List.of(),
                    "HTTP GET proxy of retrieveGeographicSite",
                    "approved-draft",
                    approved.planningText(),
                    List.of())
                .withFlow(flow));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isEmpty(), () -> "unexpected: " + error.orElse(""));
    assertTrue(approved.facts().isEmpty());
    assertTrue(approved.readyForPlan());
  }

  @Test
  void nonEmptyDraftRequiresMatchingBriefFacts() {
    RequirementFact fact =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "http-trigger",
            "GET /v1/geo-site/{id}");
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Proxy Geographic Site",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            null,
            false,
            List.of(fact),
            false);
    RequirementBrief brief =
        new RequirementBrief(
            "Proxy Geographic Site",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "ref",
            approved.planningText(),
            List.of());

    Optional<String> error = validator.validate(approved, brief);

    assertEquals(Optional.of("requirement brief has no normalized facts"), error);
  }

  @Test
  void v1BriefWithoutMappingsRemainsCoverageCompatible() {
    RequirementFact fact =
        serviceCallFact(
            "call-geosite", "call-geosite", "GeoSite", "retrieveGeographicSite");
    RequirementDraft approvedV1Draft =
        new RequirementDraft(
            true,
            "Proxy Geographic Site",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            null,
            false,
            List.of(fact),
            false);
    RequirementBrief v1BriefWithoutMappings =
        new RequirementBrief(
            "Proxy Geographic Site",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "ref",
            approvedV1Draft.planningText(),
            List.of(fact));

    Optional<String> error = validator.validate(approvedV1Draft, v1BriefWithoutMappings);

    assertTrue(error.isEmpty(), () -> "unexpected: " + error.orElse(""));
  }

  @Test
  void fieldMappingDraftRequiresCapturedIntents() {
    RequirementFact fact =
        serviceCallFact(
            "call-salesforce-createTask",
            "call-salesforce-createTask",
            "Salesforce WFM",
            "createTask");
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Request mapping from onTaskStart to createTask: Subject = name",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            null,
            false,
            List.of(fact),
            false);
    RequirementBrief brief =
        new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "ref",
            approved.planningText(),
            List.of(fact));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("field mappings"), error.orElse(""));
  }

  @Test
  void rejectsReversedTopologyBeforeBriefApproval() {
    RequirementFact endpoint =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "POST /demo/pet-inventory/check");
    RequirementFact serviceCall =
        serviceCallFact("call-1", "call-1", "Petstore Ext", "GET /store/inventory");
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Pet Inventory Check",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            null,
            false,
            List.of(endpoint, serviceCall),
            false);
    RequirementBrief reversed =
        new RequirementBrief(
            "Pet Inventory Check",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Check inventory",
            "ref",
            approved.planningText(),
            approved.facts());

    Optional<String> error = validator.validate(approved, reversed);

    assertTrue(error.isEmpty(), () -> "unexpected: " + error.orElse(""));
  }

  @Test
  void multipleEndpointsDoNotUseSingleEntryTopologyRules() {
    RequirementFact httpEndpoint =
        new RequirementFact(
            "trigger-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /greetings");
    RequirementFact quartzEndpoint =
        new RequirementFact(
            "trigger-quartz",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "quartz-scheduler",
            "Run hourly");
    RequirementFact serviceCall =
        serviceCallFact("call-1", "call-1", "Petstore Ext", "GET /store/inventory");
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Dual-trigger inventory",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            null,
            null,
            false,
            List.of(httpEndpoint, quartzEndpoint, serviceCall),
            false);
    RequirementBrief brief =
        new RequirementBrief(
            "Dual-trigger inventory",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Run from HTTP or quartz",
            "ref",
            approved.planningText(),
            approved.facts(),
            List.of());

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isEmpty(), () -> "unexpected: " + error.orElse(""));
  }

  @Test
  void rejectsMissingServiceCallId() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief projected = RequirementBriefProjector.project(rockyBrief(approved));
    RequirementBrief brief =
        projected.withServiceCalls(List.of(projected.serviceCalls().getFirst()));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("task-result"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("serviceCallId=task-result"), error.orElseThrow());
  }

  @Test
  void rejectsBindingAttachedToAnotherCallId() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    CatalogBindingHint wfmHint =
        catalogHint(
            "call-wfm-create-task",
            "fact-wfm",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-wfm",
            observedAt);
    RequirementServiceCall approvedCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint);
    RequirementServiceCall mismatchedCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", wfmHint);
    RequirementDraft approved = approvedDraft(List.of(omFact), List.of(approvedCall));
    RequirementBrief brief = briefWithCalls(approved, List.of(omFact), List.of(mismatchedCall));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("call-om-result"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("call-wfm-create-task"), error.orElseThrow());
  }

  @Test
  void rejectsMappingRefAbsentFromFlow() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief brief =
        rockyBrief(approved)
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-unknown",
                        "missing-call",
                        MappingPort.RESPONSE,
                        "task-result",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("", "commandType", "completeTask")))));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("missing-call"), error.orElseThrow());
  }

  @Test
  void rejectsInboundUsedAsOutboundRequestTarget() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief brief =
        rockyBrief(approved)
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-inverted-target",
                        "create-task",
                        MappingPort.RESPONSE,
                        "task-start",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("id", "id", null)))));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("task-start"), error.orElseThrow());
    assertTrue(error.orElseThrow().toLowerCase().contains("inbound"), error.orElseThrow());
  }

  @Test
  void rejectsOutboundUsedWithOutput() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief brief =
        rockyBrief(approved)
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-outbound-output",
                        "create-task",
                        MappingPort.OUTPUT,
                        "task-result",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("id", "id", null)))));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("create-task"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("OUTPUT"), error.orElseThrow());
  }

  @Test
  void rejectsInvertedResponseToRequestRoles() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief brief =
        rockyBrief(approved)
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-inverted",
                        "task-start",
                        MappingPort.RESPONSE,
                        "create-task",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("name", "Subject", null)))));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("RESPONSE"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("REQUEST"), error.orElseThrow());
  }

  @Test
  void rejectsBriefFlowDifferingFromApprovedDraft() {
    RequirementDraft approved = rockyApprovedDraft();
    RequirementBrief brief =
        rockyBrief(approved)
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                        new Interaction(
                            "task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                    List.of(new Transition("task-start", "task-result"))));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().toLowerCase().contains("flow"), error.orElseThrow());
  }

  @Test
  void rejectsAServiceCallWithoutACatalogBinding() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    RequirementServiceCall boundCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint);
    RequirementServiceCall unboundCall =
        new RequirementServiceCall("call-om-result", "fact-om", "Order Management", "onTaskResult");
    RequirementDraft approved = approvedDraft(List.of(omFact), List.of(boundCall));
    RequirementBrief brief = briefWithCalls(approved, List.of(omFact), List.of(unboundCall));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("no catalog binding"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("call-om-result"), error.orElseThrow());
  }

  private static RequirementDraft rockyApprovedDraft() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    return new RequirementDraft(
        true,
        "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        List.of(),
        false,
        null,
        null,
        rockyFlow(),
        List.of(
            catalogHint(
                "task-start",
                "task-start",
                "onTaskStart",
                "sys-om",
                "sg-om",
                "spec-om",
                "op-start",
                observedAt),
            catalogHint(
                "create-task",
                "create-task",
                "createTask",
                "sys-sf",
                "sg-sf",
                "spec-sf",
                "op-create",
                observedAt),
            catalogHint(
                "task-result",
                "task-result",
                "onTaskResult",
                "sys-om",
                "sg-om",
                "spec-om",
                "op-result",
                observedAt)));
  }

  private static RequirementBrief rockyBrief(RequirementDraft approved) {
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "ref",
            approved.planningText(),
            List.of(),
            List.of())
        .withFlow(approved.flow())
        .withCatalogBindings(approved.catalogBindings());
  }

  private static RequirementFlow rockyFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }

  private static RequirementDraft approvedDraft(
      List<RequirementFact> facts, List<RequirementServiceCall> serviceCalls) {
    return new RequirementDraft(
        true,
        "Call OM then Salesforce WFM",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        facts,
        false,
        serviceCalls);
  }

  private static RequirementBrief briefWithCalls(
      RequirementDraft approved,
      List<RequirementFact> facts,
      List<RequirementServiceCall> serviceCalls) {
    return new RequirementBrief(
        "Call OM then Salesforce WFM",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "summary",
        "ref",
        approved.planningText(),
        facts,
        List.of(),
        serviceCalls,
        List.of(),
        List.of());
  }

  private static RequirementFact serviceCallFact(
      String sourceFactId, String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static CatalogBindingHint catalogHint(
      String serviceCallId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      Instant observedAt) {
    return new CatalogBindingHint(
        "2",
        serviceCallId,
        sourceFactId,
        operationQuery,
        systemId,
        specificationGroupId,
        specificationId,
        integrationOperationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        observedAt,
        "evidence-" + serviceCallId);
  }
}
