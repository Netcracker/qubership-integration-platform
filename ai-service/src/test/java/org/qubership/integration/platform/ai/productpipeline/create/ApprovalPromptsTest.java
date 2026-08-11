package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApprovalPromptsTest {

  @Test
  void englishFallbackUsedWithoutAgent() {
    ApprovalPrompts prompts = new ApprovalPrompts();
    String stage = prompts.stageApprovalPrompt("requirement-analysis", "Create an integration chain");
    assertFalse(stage.isBlank());
    assertTrue(stage.toLowerCase().contains("agree"), stage);
    String implement = prompts.implementContinuationPrompt("Create an integration chain");
    assertTrue(implement.toLowerCase().contains("agree"), implement);
  }

  @Test
  void llmStubAuthorsStageApprovalWithoutLocaleCatalog() {
    ApprovalPrompts prompts =
        ApprovalPrompts.withFixedPrompts(
            (stageId, ref) -> "LLM approve " + stageId + " / " + ref,
            ref -> "LLM implement / " + ref);
    assertEquals(
        "LLM approve requirement-analysis / Crea una cadena de integracion",
        prompts.stageApprovalPrompt("requirement-analysis", "Crea una cadena de integracion"));
    assertEquals(
        "LLM implement / Crea una cadena de integracion",
        prompts.implementContinuationPrompt("Crea una cadena de integracion"));
  }
}
