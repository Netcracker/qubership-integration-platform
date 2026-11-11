package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks ADR 0003 P4 migrate / partial-join checklist in source. */
class CaptureFailurePolicyResidualChecklistTest {

  @Test
  void selectedPatternToolRoutesThroughGatewayAndFillPolicy() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/org/qubership/integration/platform/ai/plan/SelectedPatternTool.java"));
    assertTrue(source.contains("CaptureToolOutcomeGateway"));
    assertTrue(source.contains("CaptureFieldFillPolicy"));
    assertTrue(source.contains("outcomeGateway.onFailure"));
    assertFalse(source.contains("recordPlanValidationFailure"));
  }

  @Test
  void requirementBriefToolRemainsOnLegacyPlanValidationRatchet() throws Exception {
    // Residual after capture fill-repair Task 4: do not migrate RequirementBriefTool in this plan.
    String source =
        Files.readString(
            Path.of(
                "src/main/java/org/qubership/integration/platform/ai/plan/RequirementBriefTool.java"));
    assertTrue(source.contains("recordPlanValidationFailure"));
    assertFalse(source.contains("CaptureToolOutcomeGateway"));
    assertFalse(source.contains("CaptureFieldFillPolicy"));
  }

  @Test
  void chainStructureCaptureToolAlreadyRoutesThroughGateway() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/org/qubership/integration/platform/ai/compiler/ChainStructureCaptureTool.java"));
    assertTrue(source.contains("CaptureToolOutcomeGateway"));
    assertTrue(source.contains("outcomeGateway.onFailure"));
  }
}
