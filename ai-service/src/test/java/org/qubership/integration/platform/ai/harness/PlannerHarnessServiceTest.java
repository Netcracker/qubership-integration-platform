package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignProcessSkillRunner;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class PlannerHarnessServiceTest {

  private static final String INPUT = "Binding resolution policy: CATALOG_ONLY\nFlow: GET /hello";
  private static final String VALID_REPORT =
      """
      1. Generate HTTP Trigger element (cip-trigger-generator)
      2. Generate Script element (cip-script-generator)
      3. Generate execution structure (cip-structure-generator)
      4. Assemble generated-chain.cip.yaml (cip-chain-assembler)
      5. Validate the assembled chain (cip-chain-validator)
      If you agree, reply **Agree** or **Execute plan** to proceed.
      """
          .trim();

  private CompilerSkillDocumentService documentService;
  private DesignProcessSkillRunner runner;
  private PlannerHarnessService service;

  @BeforeEach
  void setUp() {
    documentService = mock(CompilerSkillDocumentService.class);
    runner = mock(DesignProcessSkillRunner.class);
    service = new PlannerHarnessService(documentService, runner, "test-model");
    when(documentService.loadByCapabilityId("cip-design-planner"))
        .thenReturn(
            new CompilerSkillDocument(
                "cip-design-planner",
                "cip-design-planner",
                "skills/cip-design-planner/SKILL.md",
                "planner",
                QipKnowledgeCapabilityPhase.DECISION,
                true,
                new QipKnowledgePackVersion("test", "test"),
                "Planner skill"));
  }

  @Test
  void recordsSuccessfulModelCall() {
    when(runner.runOnce(any(), any(), any(), any(), any(), any())).thenReturn(VALID_REPORT);

    PlannerHarnessResponse response =
        service.run(new PlannerHarnessRequest(null, INPUT, null));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals("test-model", response.modelName());
    assertEquals(64, response.skillHash().length());
    assertNotNull(response.conversationId());
    assertEquals(1, response.attempts().size());
    assertEquals(VALID_REPORT, response.attempts().getFirst().response());
    assertEquals(1, response.attempts().getFirst().number());
  }

  @Test
  void recordsRejectedResponseAndFormatRetry() {
    when(runner.runOnce(any(), any(), any(), any(), any(), any()))
        .thenReturn("1. Missing approval sentence (cip-trigger-generator)")
        .thenReturn(VALID_REPORT);

    PlannerHarnessResponse response =
        service.run(new PlannerHarnessRequest(" planner-run ", INPUT, "prior halt"));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    assertEquals("planner-run", response.conversationId());
    assertEquals(2, response.attempts().size());
    assertTrue(response.attempts().get(1).formatFailure().contains("approval sentence"));
    ArgumentCaptor<Optional<String>> repairEvidence = ArgumentCaptor.forClass(Optional.class);
    verify(runner, org.mockito.Mockito.times(2))
        .runOnce(any(), any(), any(), any(), repairEvidence.capture(), any());
    assertEquals(Optional.of("prior halt"), repairEvidence.getAllValues().getFirst());
  }

  @Test
  void recordsTerminalPlannerFailure() {
    when(runner.runOnce(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("provider timeout"));

    PlannerHarnessResponse response =
        service.run(new PlannerHarnessRequest("run-1", INPUT, null));

    assertEquals(SkillHarnessStatus.FAILED, response.status());
    assertEquals("provider timeout", response.message());
    assertEquals("provider timeout", response.attempts().getFirst().error());
  }
}
