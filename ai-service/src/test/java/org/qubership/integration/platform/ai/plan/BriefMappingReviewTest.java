package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class BriefMappingReviewTest {

  @Test
  void editingProposedRuleMakesItUserDefinedAndApprovalConfirmsRemainingProposed() {
    RequirementBrief brief =
        briefWith(
            new MappingIntent(
                "map-init",
                "trigger-1",
                MappingPort.OUTPUT,
                "call-1",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.userId", "$.personId", null, MappingRuleStatus.PROPOSED),
                    new MappingIntentRule(
                        "$.name", "$.fullName", null, MappingRuleStatus.PROPOSED))));

    RequirementBrief edited =
        BriefMappingReview.editRule(brief, "map-init", "$.personId", "$.accountId", null);

    assertEquals(
        MappingRuleStatus.USER_DEFINED, edited.mappingIntents().getFirst().rules().getFirst().status());
    assertEquals("$.accountId", edited.mappingIntents().getFirst().rules().getFirst().sourcePath());
    assertEquals(
        MappingRuleStatus.PROPOSED, edited.mappingIntents().getFirst().rules().get(1).status());

    RequirementBrief confirmed = BriefMappingReview.confirmProposedOnApproval(edited);
    assertEquals(
        MappingRuleStatus.PROPOSED, confirmed.mappingIntents().getFirst().rules().get(1).status());
  }

  @Test
  void unresolvedRequiredTargetBlocksBriefApproval() {
    RequirementBrief blocked =
        briefWith(
            new MappingIntent(
                "map-init",
                "trigger-1",
                MappingPort.OUTPUT,
                "call-1",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "", "$.personId", null, MappingRuleStatus.UNRESOLVED))));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> BriefMappingReview.confirmProposedOnApproval(blocked));
    assertTrue(thrown.getMessage().contains("$.personId"));
  }

  @Test
  void changingApprovedMappingReopensBriefAndInvalidatesDependentPlanSteps() {
    RequirementBrief approved =
        briefWith(
            new MappingIntent(
                "map-init",
                "trigger-1",
                MappingPort.OUTPUT,
                "call-1",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.userId", "$.personId", null, MappingRuleStatus.PROPOSED))));
    RequirementBrief updated =
        BriefMappingReview.editRule(approved, "map-init", "$.personId", "$.accountId", null);
    DesignExecutionPlan plan = planDependingOn("map-init");

    BriefMappingReview.MappingChangeImpact impact =
        BriefMappingReview.afterApprovedMappingChange(approved, updated, plan);

    assertTrue(impact.briefReopened());
    assertEquals(Set.of("map-init"), impact.changedMappingIntentIds());
    assertTrue(impact.invalidatedPlanStepIds().contains("step-transform-map-init"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-script"));
    assertFalse(impact.invalidatedPlanStepIds().contains("step-trigger"));
  }

  private static RequirementBrief briefWith(MappingIntent intent) {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map OM output to Salesforce request",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(List.of(intent));
  }

  private static DesignExecutionPlan planDependingOn(String mappingIntentId) {
    return new DesignExecutionPlan(
        "1",
        "flow-1",
        "cip-design-planner",
        "normalized-design-flow/flow-1",
        "design-input-hash",
        "2024.4",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY,
        List.of(
            new DesignExecutionPlan.Step(
                "step-trigger",
                1,
                "Generate HTTP trigger",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-transform-" + mappingIntentId,
                2,
                "Configure mapper for " + mappingIntentId,
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-transformation-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT")),
            new DesignExecutionPlan.Step(
                "step-script",
                3,
                "Generate mapping script",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-script-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-hash",
        Map.of(
            "cip-trigger-generator",
            "h1",
            "cip-transformation-generator",
            "h2",
            "cip-script-generator",
            "h3"),
        Map.of(),
        "catalog-hash",
        DesignPlanProjector.BINDING_RESOLUTION_POLICY_HASH);
  }
}
