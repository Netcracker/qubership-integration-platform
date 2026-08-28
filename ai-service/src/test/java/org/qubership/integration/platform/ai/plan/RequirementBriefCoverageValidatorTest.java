package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class RequirementBriefCoverageValidatorTest {

  private final RequirementBriefCoverageValidator validator = new RequirementBriefCoverageValidator();

  @Test
  void emptyApprovedDraftFactsAreCoverageNoOp() {
    RequirementDraft approved = new RequirementDraft(true, "Proxy Geographic Site GET-by-id");
    RequirementBrief brief =
        new RequirementBrief(
            "Proxy Geographic Site",
            List.of("id path param"),
            List.of("accessControlType NONE"),
            List.of(),
            List.of(),
            "HTTP GET proxy of retrieveGeographicSite",
            "approved-draft",
            approved.planningText(),
            List.of());

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
    assertTrue(v1BriefWithoutMappings.dataMappings().isEmpty());
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
            approved.facts(),
            List.of(
                passThrough(
                    RequirementDataMapping.Stage.INITIALIZATION, "call-1", "trigger-1"),
                passThrough(RequirementDataMapping.Stage.RESPONSE, "call-1", "trigger-1")));

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
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    RequirementFact wfmFact =
        serviceCallFact("fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask");
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
    RequirementServiceCall omCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint);
    RequirementServiceCall wfmCall =
        new RequirementServiceCall(
            "call-wfm-create-task", "fact-wfm", "Salesforce WFM", "createTask", wfmHint);
    RequirementDraft approved = approvedDraft(List.of(omFact, wfmFact), List.of(omCall, wfmCall));
    RequirementBrief brief =
        briefWithCalls(approved, List.of(omFact, wfmFact), List.of(omCall));

    Optional<String> error = validator.validate(approved, brief);

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("call-wfm-create-task"), error.orElseThrow());
    assertTrue(
        error.orElseThrow().contains("serviceCallId=call-wfm-create-task"), error.orElseThrow());
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

  private static RequirementDataMapping passThrough(
      RequirementDataMapping.Stage stage, String from, String to) {
    return new RequirementDataMapping(
        "map-" + stage.name().toLowerCase(),
        stage,
        from,
        to,
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of("mapping-fact"));
  }
}
