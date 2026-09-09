package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class SkipPolicyTest {

  private final SkipPolicy policy =
      new SkipPolicy(
          List.of(SkipPolicy.NO_APIHUB_CANDIDATE, SkipPolicy.CATALOG_BINDING_PRESENT));

  @Test
  void skipsWhenCandidateMissing() {
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "ready",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(),
            false);
    assertTrue(policy.matches(draft));
  }

  @Test
  void skipsWhenCatalogBindingPresent() {
    RequirementFact call =
        new RequirementFact(
            "call-geosite",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "getGeographicSite",
            "GeoSite",
            "getGeographicSite",
            "",
            "",
            "",
            "call-geosite");
    RequirementDraft draft =
        new RequirementDraft(
                true,
                "bound",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                "brainstorming",
                "1",
                null,
                candidate(),
                false,
                List.of(call),
                false)
            .withApiHubCandidate(candidate(), call.serviceCallId())
            .withBoundServiceCall(
                call.serviceCallId(),
                new CatalogBindingHint(
                    "2",
                    call.serviceCallId(),
                    call.sourceFactId(),
                    "service-call",
                    "sys",
                    "group",
                    "spec",
                    "op",
                    null,
                    null,
                    null,
                    "catalog",
                    Instant.EPOCH,
                    "test"));
    assertTrue(policy.matches(draft));
  }

  @Test
  void doesNotSkipWhenPendingImport() {
    RequirementDraft draft =
        new RequirementDraft(
            false,
            "pending",
            DraftDecision.NEEDS_INPUT,
            List.of(),
            "brainstorming",
            "1",
            null,
            candidate(),
            false,
            List.of(),
            true);
    assertFalse(policy.matches(draft));
  }

  @Test
  void doesNotSkipWhenImportIntentWithoutCandidate() {
    RequirementDraft draft =
        new RequirementDraft(
            false,
            "re-gather after import fail",
            DraftDecision.NEEDS_INPUT,
            List.of("What API Hub package should we import?"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(),
            true);
    assertFalse(
        policy.matches(draft),
        "importIntent without candidate must not skip (ADR fail / cold soft-gather)");
  }

  @Test
  void noApihubCandidateStillReturnsRequirementDraftPassthrough() {
    SkipPolicy skip = new SkipPolicy(List.of(SkipPolicy.NO_APIHUB_CANDIDATE));
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "ready",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(),
            false);
    assertEquals(
        Optional.of(SkipPolicy.SkipAction.REQUIREMENT_DRAFT_PASSTHROUGH),
        skip.evaluate(new SkipPolicy.SkipEvaluationContext(draft)));
  }

  @Test
  void catalogBindingPresentDoesNotSkipWhenFlowStillHasUnboundOutbound() {
    SkipPolicy skip = new SkipPolicy(List.of(SkipPolicy.CATALOG_BINDING_PRESENT));
    RequirementDraft draft =
        omWfmDraft().withBoundInteraction("create-salesforce-task", restHint("create-salesforce-task"));

    assertFalse(skip.matches(draft));
  }

  @Test
  void catalogBindingPresentSkipsWhenEveryOutboundInteractionIsBound() {
    SkipPolicy skip = new SkipPolicy(List.of(SkipPolicy.CATALOG_BINDING_PRESENT));
    RequirementDraft draft =
        omWfmDraft()
            .withBoundInteraction("create-salesforce-task", restHint("create-salesforce-task"))
            .withBoundInteraction("return-task-result", restHint("return-task-result"));

    assertTrue(skip.matches(draft));
  }

  private static RequirementDraft omWfmDraft() {
    return new RequirementDraft(
            true, "OM to Salesforce WFM", DraftDecision.READY_FOR_PLAN, List.of(), "brainstorming", "1")
        .withFlow(
            new RequirementFlow(
                List.of(
                    new Interaction(
                        "on-task-start", Direction.INBOUND, "Caller", "POST /tasks", ""),
                    new Interaction(
                        "create-salesforce-task",
                        Direction.OUTBOUND,
                        "Salesforce WFM",
                        "createTask",
                        ""),
                    new Interaction(
                        "return-task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                List.of(
                    new Transition("on-task-start", "create-salesforce-task"),
                    new Transition("create-salesforce-task", "return-task-result"))));
  }

  private static CatalogBindingHint restHint(String interactionId) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        "POST /ops/" + interactionId,
        "sys",
        "group",
        "spec",
        "op-" + interactionId,
        "rest",
        "POST",
        "/ops/" + interactionId,
        "catalog",
        Instant.EPOCH,
        "test");
  }

  private static ApiHubRequirementRefs candidate() {
    return new ApiHubRequirementRefs(
        "pkg", "2024.4", "op-1", null, "rest", "Pkg", "Spec");
  }
}
