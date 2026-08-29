package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class DesignExecutionBriefFactoryTest {

  @Test
  void doesNotCopyResolvedBindingIdsIntoBriefText() {
    ChainSemanticRevision revision =
        SemanticFixtures.linear(
            "HealthProxy",
            "revision-health",
            "trigger-http",
            "node-call",
            "call-1",
            "GET /store/inventory",
            "HTTP",
            List.of(),
            List.of("RBAC role test-role", "No external route"));
    RequirementBrief brief = DesignExecutionBriefFactory.build(null, revision);

    String promptText =
        String.join("\n", brief.inputs()) + "\n" + brief.approvedDraftText();
    assertEquals("HealthProxy", brief.goal());
    assertFalse(
        List.of("sys-1", "grp-1", "spec-1", "op-1").stream().anyMatch(promptText::contains),
        promptText);
    assertTrue(brief.constraints().stream().anyMatch(c -> c.contains("test-role")));
    assertTrue(brief.facts().isEmpty());
  }

  @Test
  void preservesStoredBriefWithoutCopyingBindingIds() {
    RequirementBrief stored =
        new RequirementBrief(
            "goal",
            List.of("HTTP request to '/health-proxy'"),
            List.of("RBAC role 'test-role'"),
            List.of(),
            List.of(),
            "summary",
            null,
            "approved text",
            List.of());
    RequirementBrief brief = DesignExecutionBriefFactory.build(stored, sampleRevision());

    assertTrue(brief.approvedDraftText().contains("approved text"));
    assertFalse(brief.inputs().stream().anyMatch(i -> i.contains("systemId=sys-9")));
  }

  @Test
  void preservesStoredBriefMappingsInsteadOfInventingThem() {
    RequirementDataMapping mapping =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "step-trigger",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(
                new RequirementDataMapping.Rule(
                    "$.request.id", "$.headers.X-Request-Id", null)),
            List.of("fact-map"));
    RequirementBrief stored =
        new RequirementBrief(
            "goal",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "approved text",
            List.of(),
            List.of(mapping));

    RequirementBrief brief = DesignExecutionBriefFactory.build(stored, sampleRevision());

    assertTrue(
        brief.dataMappings().stream().anyMatch(item -> item.mappingId().equals("map-init")));
    assertTrue(
        brief.dataMappings().getFirst().rules().stream()
            .anyMatch(rule -> rule.targetPath().equals("$.headers.X-Request-Id")));
  }

  @Test
  void firstTurnCarriesNoRepairEvidence() {
    RequirementBrief withoutRepair =
        DesignExecutionBriefFactory.build(null, sampleRevision(), null, null);
    RequirementBrief plain = DesignExecutionBriefFactory.build(null, sampleRevision());

    assertEquals(plain.approvedDraftText(), withoutRepair.approvedDraftText());
  }

  @Test
  void repairTurnFoldsHaltEvidenceAndPriorGraphIntoDraftText() {
    StageRepairEvidence repairEvidence =
        new StageRepairEvidence(
            "VALIDATION_FAILURE",
            "design-execution",
            "http-trigger-1: schema violation",
            "Phase 5 plan validation failed",
            "use RBAC");
    ChainPlanGraph priorGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    RequirementBrief brief =
        DesignExecutionBriefFactory.build(null, sampleRevision(), repairEvidence, priorGraph);

    assertTrue(brief.approvedDraftText().contains("VALIDATION_FAILURE"));
    assertTrue(brief.approvedDraftText().contains("design-execution"));
    assertTrue(brief.approvedDraftText().contains("schema violation"));
    assertTrue(brief.approvedDraftText().contains("Phase 5 plan validation failed"));
    assertTrue(brief.approvedDraftText().contains("use RBAC"));
    assertTrue(brief.approvedDraftText().contains("trigger"));
    assertTrue(brief.approvedDraftText().contains("http-trigger"));
  }

  @Test
  void repairEvidenceWithNoFindingsOrErrorLeavesDraftTextUnchanged() {
    StageRepairEvidence emptyEvidence =
        new StageRepairEvidence("VALIDATION_FAILURE", null, "", "", null);

    RequirementBrief brief =
        DesignExecutionBriefFactory.build(null, sampleRevision(), emptyEvidence, null);
    RequirementBrief plain = DesignExecutionBriefFactory.build(null, sampleRevision());

    assertEquals(plain.approvedDraftText(), brief.approvedDraftText());
  }

  private static ChainSemanticRevision sampleRevision() {
    return SemanticFixtures.linearOrders();
  }
}
