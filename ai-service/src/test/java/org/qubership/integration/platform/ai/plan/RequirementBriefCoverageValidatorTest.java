package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class RequirementBriefCoverageValidatorTest {

  private final RequirementBriefCoverageValidator validator = new RequirementBriefCoverageValidator();

  @Test
  void emptyApprovedDraftFactsAreCoverageNoOp() {
    RequirementDraft approved =
        new RequirementDraft(true, "Proxy Geographic Site GET-by-id")
            .withCatalogBinding(
                new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", "op-1", "INTERNAL"));
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
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "service-call",
            "Call retrieveGeographicSite");
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
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "service-call",
            "Petstore Ext: GET /store/inventory");
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

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("INITIALIZATION"), error.orElseThrow());
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
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "service-call",
            "Petstore Ext: GET /store/inventory");
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
