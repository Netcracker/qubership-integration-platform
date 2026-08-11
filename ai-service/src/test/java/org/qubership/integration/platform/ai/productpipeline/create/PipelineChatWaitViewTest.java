package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PipelineChatWaitViewTest {

  @Test
  void suppressesReadyForPlanStatusSentence() {
    String leaked = "Requirement draft is not READY_FOR_PLAN yet";
    assertEquals("", PipelineChatWaitView.forChatWait(leaked));
  }

  @Test
  void suppressesOtherPipelineStatusTokens() {
    assertEquals("", PipelineChatWaitView.forChatWait("Still WAITING_FOR_INPUT"));
    assertEquals("", PipelineChatWaitView.forChatWait("Run reached CHAIN_MATERIALIZED"));
    assertEquals("", PipelineChatWaitView.forChatWait("Draft stored as NEEDS_INPUT"));
  }

  @Test
  void suppressesAgentCaptureJargon() {
    assertEquals(
        "",
        PipelineChatWaitView.forChatWait(
            "Gather did not capture a requirement draft. The agent must call"
                + " captureRequirementDraft with the accumulated vision text before finishing."));
  }

  @Test
  void userFacingPromptGetsLeadingBlankLine() {
    String chat = PipelineChatWaitView.forChatWait("Reply **Agree** to approve.");
    assertTrue(chat.startsWith("\n\n"));
    assertTrue(chat.contains("Reply **Agree** to approve."));
    assertFalse(chat.contains("READY_FOR_"));
  }

  @Test
  void blankStaysBlank() {
    assertEquals("", PipelineChatWaitView.forChatWait(null));
    assertEquals("", PipelineChatWaitView.forChatWait("   "));
  }

  @Test
  void alreadySeparatedPromptIsNotDoublePrefixed() {
    assertEquals("\n\nKeep me", PipelineChatWaitView.forChatWait("\n\nKeep me"));
  }
}
