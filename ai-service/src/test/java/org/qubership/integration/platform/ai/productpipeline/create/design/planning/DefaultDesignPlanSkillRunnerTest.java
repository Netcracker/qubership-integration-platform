package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

class DefaultDesignPlanSkillRunnerTest {

  @Test
  void buildsAllApprovedTargetsWithoutOptionalModelDescriptions() {
    var documents = documents();
    var revision = DesignPlanTestFixtures.revision();
    var result = new DefaultDesignPlanSkillRunner(documents).runOnce(
        "conversation", "approved targets", Optional.empty(), Optional.empty(),
        CanonicalPayloadHash.sha256Hex("planner skill"), "2026.1", revision,
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision));

    assertTrue(result.contract().steps().stream().anyMatch(step -> step.claims().stream()
        .anyMatch(claim -> claim.targetKind() == TargetKind.ENTRY_POINT && claim.targetId().equals("entry-1"))));
    assertTrue(result.contract().steps().stream().anyMatch(step -> step.claims().stream()
        .anyMatch(claim -> claim.targetKind() == TargetKind.SERVICE_CALL && claim.targetId().equals("call-1"))));
    assertEquals("2026.1", result.contract().apiRelease());
    var report = new DesignPlanReportRenderer().render(result.contract());
    assertTrue(report.markdown().contains(DesignPlanReportRenderer.APPROVAL_SENTENCE));
    assertTrue(DesignPlanCaptureSession.binding("conversation").isEmpty());
  }

  @Test
  void unresolvedOwnersAndInvalidPinsStillRejectThePlan() {
    var revision = DesignPlanTestFixtures.revision();
    var runner = new DefaultDesignPlanSkillRunner(documents());
    assertThrows(PlannerContractException.class, () -> runner.runOnce(
        "conversation", "input", Optional.empty(), Optional.empty(), "wrong-hash", "2026.1",
        revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision)));
    assertThrows(PlannerContractException.class, () -> runner.runOnce(
        "conversation", "input", Optional.empty(), Optional.empty(), CanonicalPayloadHash.sha256Hex("planner skill"),
        "2026.1", revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.pin(revision)));
  }

  private static CompilerSkillDocumentService documents() {
    var documents = mock(CompilerSkillDocumentService.class);
    when(documents.loadByCapabilityId(CipDesignPlannerAdapter.SKILL_ID)).thenReturn(
        new CompilerSkillDocument(CipDesignPlannerAdapter.SKILL_ID, "source", "path", "Planner",
            null, true, null, "planner skill"));
    return documents;
  }
}
