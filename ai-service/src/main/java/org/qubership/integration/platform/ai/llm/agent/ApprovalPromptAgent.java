package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors create-chain@2 approval / implement questions in the conversation language. English
 * system instructions only; the reader answers from the decision card, not by typing a token.
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
Ask the approval question only. Do not tell the reader which word to type or reply with. Reply with \
only the user-facing question text. No markdown fences, no quotes, no preamble.\
""")
  String askStageApproval(String stageId, String reference);

  @UserMessage(
      """
Write a short chat question asking the user to confirm creating the chain now that the plan is \
approved. Match the language of this reference text:
---
{reference}
---
Ask the confirmation question only. Do not tell the reader which word to type or reply with. Reply \
with only the user-facing question text. No markdown fences, no quotes, no preamble.\
""")
  String askImplementContinuation(String reference);

  @UserMessage(
      """
Write a short chat question asking the user to confirm importing an API Hub specification into \
the runtime catalog before planning continues. Name the specification: {specification}.
Match the language of this reference text:
---
{reference}
---
Ask the confirmation question only. Do not tell the reader which word to type or reply with. Reply \
with only the user-facing question text. No markdown fences, no quotes, no preamble.\
""")
  String askImportConfirmation(String specification, String reference);
}
