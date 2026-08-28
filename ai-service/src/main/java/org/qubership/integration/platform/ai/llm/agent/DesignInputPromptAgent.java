package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors design-input WaitingForInput prompts and classifies short user replies. Display text
 * matches the conversation language; return tokens for classifiers stay English enum labels.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface DesignInputPromptAgent {

  @UserMessage(
      """
Write a short chat question telling the user that some data mappings are still missing before \
design can continue ({pendingMode}). The UI will list these edges and offer Pass through / \
Describe mappings — do not list the edges in your reply, and do not tell the user to type \
PASS_THROUGH or EXPLICIT.
Missing edges (for your context only; do not repeat them):
{missingEdges}
Write in the pinned response locale {responseLocale}. This locale is authoritative; do not infer a
different language from the reference text:
---
{reference}
---
Reply with only one or two short sentences for the card. No markdown fences, no quotes, no \
preamble, no bullet list.\
""")
  String askMappingGap(
      String responseLocale, String reference, String missingEdges, String pendingMode);

  @UserMessage(
      """
Classify whether the user confirms pass-through data mappings for all missing design edges.
Reply with exactly one token: PASS_THROUGH or NONE.
- PASS_THROUGH = confirm pass-through / as-is / no transformation / agree to fill missing mappings
- NONE = unrelated, unclear, or they described explicit field rules instead
User reply:
---
{userText}
---
Reply with only PASS_THROUGH or NONE.\
""")
  String classifyMappingReply(String userText);
}
