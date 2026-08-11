package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

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
}
