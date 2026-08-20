package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;

class ApprovalPromptsTest {

  @Test
  void englishFallbackAsksTheQuestionWithoutReplyToken() {
    ApprovalPrompts prompts = new ApprovalPrompts();
    String stage = prompts.stageApprovalPrompt("requirement-analysis", "Create an integration chain");
    assertFalse(stage.isBlank());
    assertFalse(stage.toLowerCase().contains("agree"), stage);
    assertFalse(stage.toLowerCase().contains("reply"), stage);
    String implement = prompts.implementContinuationPrompt("Create an integration chain");
    assertFalse(implement.toLowerCase().contains("agree"), implement);
    assertFalse(implement.toLowerCase().contains("reply"), implement);
    assertTrue(implement.toLowerCase().contains("create"), implement);
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

  @Test
  void passesPinnedResponseLocaleToThePromptAgent() {
    AtomicReference<String> receivedLocale = new AtomicReference<>();
    ApprovalPromptAgent agent =
        new ApprovalPromptAgent() {
          @Override
          public String askStageApproval(
              String stageId, String responseLocale, String reference) {
            receivedLocale.set(responseLocale);
            return "Approve?";
          }

          @Override
          public String askImplementContinuation(String responseLocale, String reference) {
            return "Create?";
          }

          @Override
          public String askImportConfirmation(
              String specification, String responseLocale, String reference) {
            return "Import?";
          }
        };

    new ApprovalPrompts(agent)
        .stageApprovalPrompt("requirement-analysis", "en", "Create an integration chain");

    assertEquals("en", receivedLocale.get());
  }
}
