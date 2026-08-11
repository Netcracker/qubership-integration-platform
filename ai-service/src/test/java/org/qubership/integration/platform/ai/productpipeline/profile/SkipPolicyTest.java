package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;

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
            null,
            false,
            List.of(),
            false);
    assertTrue(policy.matches(draft));
  }

  @Test
  void skipsWhenCatalogBindingPresent() {
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
            new ResolvedCatalogBinding("sys", "spec", "group", "op"),
            false,
            List.of(),
            false);
    assertTrue(policy.matches(draft));
  }

  @Test
  void doesNotSkipWhenPendingImport() {
    RequirementDraft draft =
        new RequirementDraft(
            false,
            "pending",
            DraftDecision.NEEDS_INPUT,
            List.of(RequirementDraft.IMPORT_CONFIRM_OPEN_QUESTION),
            "brainstorming",
            "1",
            null,
            candidate(),
            null,
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
            null,
            false,
            List.of(),
            true);
    assertFalse(
        policy.matches(draft),
        "importIntent without candidate must not skip (ADR fail / cold soft-gather)");
  }

  @Test
  void acceptsProvidedDesignRouteAsKnownCondition() {
    assertDoesNotThrow(
        () ->
            SkipPolicy.requireKnownConditions(
                new SkipPolicy(List.of(SkipPolicy.PROVIDED_DESIGN_ROUTE))));
  }

  @Test
  void providedDesignRouteReturnsNoOutputWithoutDraftPassthrough() {
    SkipPolicy routeSkip = new SkipPolicy(List.of(SkipPolicy.PROVIDED_DESIGN_ROUTE));
    Optional<SkipPolicy.SkipAction> action =
        routeSkip.evaluate(
            new SkipPolicy.SkipEvaluationContext(null, DesignEntryRoute.PROVIDE));
    assertEquals(Optional.of(SkipPolicy.SkipAction.NO_OUTPUT), action);

    assertTrue(
        routeSkip
            .evaluate(new SkipPolicy.SkipEvaluationContext(null, DesignEntryRoute.STANDARD))
            .isEmpty());
  }

  @Test
  void importStageWhenAnyPrefersProvidedRouteOverNullDraftNoApihub() {
    // Mirrors create-chain@2 import-stage skip order: no-apihub before provided-design-route.
    SkipPolicy importSkip =
        new SkipPolicy(
            List.of(
                SkipPolicy.NO_APIHUB_CANDIDATE,
                SkipPolicy.CATALOG_BINDING_PRESENT,
                SkipPolicy.PROVIDED_DESIGN_ROUTE));
    assertEquals(
        Optional.of(SkipPolicy.SkipAction.NO_OUTPUT),
        importSkip.evaluate(
            new SkipPolicy.SkipEvaluationContext(null, DesignEntryRoute.PROVIDE)));
    assertTrue(
        importSkip
            .evaluate(new SkipPolicy.SkipEvaluationContext(null, DesignEntryRoute.STANDARD))
            .isEmpty());
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
            null,
            false,
            List.of(),
            false);
    assertEquals(
        Optional.of(SkipPolicy.SkipAction.REQUIREMENT_DRAFT_PASSTHROUGH),
        skip.evaluate(new SkipPolicy.SkipEvaluationContext(draft, DesignEntryRoute.STANDARD)));
  }

  private static ApiHubRequirementRefs candidate() {
    return new ApiHubRequirementRefs(
        "pkg", "2024.4", "op-1", null, "rest", "Pkg", "Spec");
  }
}
