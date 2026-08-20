package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Authors design-input WaitingForInput prompts and classifies short user replies into pipeline
 * modes. Display text matches the conversation language; return tokens for classifiers stay English
 * enum labels.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface DesignInputPromptAgent {

  @UserMessage(
      """
Write a short chat question asking whether the user wants an integration design document (IDS) \
for their approved requirements. Write in the pinned response locale {responseLocale}. This locale
is authoritative; do not infer a different language from the reference text. Preserve product terms:
---
{reference}
---
The UI shows Yes / No buttons below the question — do not tell the reader which word to type or \
reply with, and do not list the two options yourself.

Say nothing about what happens when the answer is no. The pipeline continues either way, and \
describing the internal shortcut turns a plain choice into a comparison the reader cannot make. \
Do not mention a minimal, derived, short, or partial document, and do not invent a third option.

Reply with only the user-facing question text. No markdown fences, no quotes, no preamble.\
""")
  String askIdsPathChoice(String responseLocale, String reference);

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
Classify the user's reply about generating an integration design document (IDS).
Reply with exactly one token: GENERATE, DERIVE, or NONE.
- GENERATE = yes / generate the full IDS / create the design document
- DERIVE = no / skip full generation / derive a minimal IDS from the brief
- NONE = unrelated or unclear
User reply:
---
{userText}
---
Reply with only GENERATE, DERIVE, or NONE.\
""")
  String classifyIdsPathChoice(String userText);

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
