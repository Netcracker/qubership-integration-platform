package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors create-chain@2 approval / implement CTAs in the conversation language. English system
 * instructions only; reply tokens like Agree stay as classifier targets in code.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ApprovalPromptAgent {

  @UserMessage(
      """
Write a short chat question asking the user to approve the current pipeline stage candidate \
(stage id for context only, do not emphasize it): {stageId}.
Match the language of this reference text (do not mistranslate product terms; just match the language):
---
{reference}
---
Tell them they can reply Agree to approve, or describe what to change. Reply with only the \
user-facing question text. No markdown fences, no quotes, no preamble. You may bold Agree.\
""")
  String askStageApproval(String stageId, String reference);

  @UserMessage(
      """
Write a short chat question asking the user to confirm creating the chain now that the plan is \
approved. Match the language of this reference text:
---
{reference}
---
Tell them they can reply Agree to create the chain, or describe what to change. Reply with only \
the user-facing question text. No markdown fences, no quotes, no preamble. You may bold Agree.\
""")
  String askImplementContinuation(String reference);
}
