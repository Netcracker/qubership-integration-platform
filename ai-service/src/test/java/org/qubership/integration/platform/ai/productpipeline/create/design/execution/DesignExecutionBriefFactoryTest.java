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
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

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
    MappingIntent mapping =
        new MappingIntent(
            "map-init",
            "step-trigger",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.request.id", "$.headers.X-Request-Id", null)));
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
                List.of())
            .withMappingIntents(List.of(mapping));

    RequirementBrief brief = DesignExecutionBriefFactory.build(stored, sampleRevision());

    assertTrue(
        brief.mappingIntents().stream().anyMatch(item -> item.mappingIntentId().equals("map-init")));
    assertTrue(
        brief.mappingIntents().getFirst().rules().stream()
            .anyMatch(rule -> rule.targetPath().equals("$.headers.X-Request-Id")));
  }

  @Test
  void preservesStoredMappingIntents() {
    MappingIntent intent =
        new MappingIntent(
            "map-request",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("name", "Subject", null)));
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
                List.of())
            .withMappingIntents(List.of(intent));

    RequirementBrief brief = DesignExecutionBriefFactory.build(stored, sampleRevision());

    assertEquals(1, brief.mappingIntents().size());
    assertEquals("map-request", brief.mappingIntents().getFirst().mappingIntentId());
    assertEquals("$.name", brief.mappingIntents().getFirst().rules().getFirst().sourcePath());
  }

  @Test
  void doesNotCopyRevisionMappingBodiesWhenStoredBriefHasNone() {
    RequirementBrief stored =
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary");
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(stored, SemanticFixtures.linearOrdersWithMapping());

    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void firstTurnCarriesNoRepairEvidence() {
    RequirementBrief withoutRepair =
        DesignExecutionBriefFactory.build(null, sampleRevision(), null, null);
    RequirementBrief plain = DesignExecutionBriefFactory.build(null, sampleRevision());

    assertEquals(plain.approvedDraftText(), withoutRepair.approvedDraftText());
  }

  @Test
  void executionKeepsTheApprovedFolderOnFirstAttemptAndRepair() {
    RequirementBrief stored = new RequirementBrief("Folder test", List.of(), List.of(), List.of(), List.of(), "Folder test")
        .withApprovedDraftText("Create the chain in the approved folder.")
        .withTargetFolderPath("/Elements autotest chains/Campaign");
    StageRepairEvidence repair = new StageRepairEvidence("CONTRACT_FAILURE", "design-execution",
        "INVALID_JSON", "rejected capture", "");

    RequirementBrief first = DesignExecutionBriefFactory.build(stored, sampleRevision());
    RequirementBrief repaired = DesignExecutionBriefFactory.build(stored, sampleRevision(), repair, null);

    assertEquals(stored.targetFolderPath(), first.targetFolderPath());
    assertEquals(first, repaired);
    assertEquals(stored.approvedDraftText(), repaired.approvedDraftText());
  }

  @Test
  void repairTurnPreservesApprovedTextWithoutRawErrorsOrPriorGraph() {
    StageRepairEvidence repairEvidence =
        new StageRepairEvidence(
            "VALIDATION_FAILURE",
            "design-execution",
            "http-trigger-1: schema violation",
            "MALFORMED_ARGUMENT " + "CIP:GEN-000005 ".repeat(4000),
            "use RBAC");
    ChainPlanGraph priorGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    RequirementBrief stored =
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary")
            .withApprovedDraftText("Approved task");
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(stored, sampleRevision(), repairEvidence, priorGraph);

    assertEquals("Approved task", brief.approvedDraftText());
    assertEquals(DesignExecutionBriefFactory.build(stored, sampleRevision()), brief);
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
