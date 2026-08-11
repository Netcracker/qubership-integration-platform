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
for their approved requirements. Match the language of this reference text (do not mistranslate \
product terms; just match the language):
---
{reference}
---
Offer exactly two reply options: yes, write the document, or no, carry on without one.

Say nothing about what happens when the answer is no. The pipeline continues either way, and \
describing the internal shortcut turns a plain choice into a comparison the reader cannot make. \
Do not mention a minimal, derived, short, or partial document, and do not invent a third option.

Reply with only the user-facing question text. No markdown fences, no quotes, no preamble.\
""")
  String askIdsPathChoice(String reference);

  @UserMessage(
      """
Write a short chat question asking the user to supply missing data-mapping intent so design can \
continue ({pendingMode}). List these missing edges exactly (keep the technical labels):
{missingEdges}
Match the language of this reference text:
---
{reference}
---
Tell the user they can reply PASS_THROUGH to apply pass-through for every missing edge, or \
describe EXPLICIT field mappings (sourcePath to targetPath). Reply with only the user-facing \
question text. No markdown fences, no quotes, no preamble.\
""")
  String askMappingGap(String reference, String missingEdges, String pendingMode);

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
