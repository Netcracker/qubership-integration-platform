package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classifies a reply to a create-chain@2 approval question.
 *
 * <p>Exists because the literal check it backs cannot survive the two ways a real reply arrives.
 * {@link ApprovalPromptAgent} authors the question in the language of the conversation, so a reader
 * answers in that language rather than with the English token; and an agent relaying a person's
 * approval writes a sentence about it instead of the bare word.
 *
 * <p>The verdict is three-way on purpose. A yes-or-no classifier has to choose on a reply that
 * settles nothing, and choosing "yes" advances a pipeline stage nobody approved. {@code UNCLEAR}
 * lets the caller be asked again, which costs one turn and decides nothing on the reader's behalf.
 *
 * <p>The line the prompt draws is narrow on purpose, and it was drawn in the wrong place once. A
 * relaying agent almost always writes "approved, now do the next thing", so treating any trailing
 * request as a change request refuses every real approval it sends. Only a request to change the
 * candidate under review counts; instructions about later stages do not.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ApprovalIntentAgent {

  @UserMessage(
      """
An automated pipeline asked a user to approve the candidate it just produced for the current \
stage. Classify the reply below. It may be in any language, and it may come from an agent \
relaying a person's decision rather than from the person.

Answer with exactly one word:
APPROVED — the reply accepts the candidate as it stands.
CHANGES_REQUESTED — the reply asks for the candidate itself to be different: a requirement \
added, removed, corrected, or challenged.
UNCLEAR — the reply does neither.

Judge only whether the candidate is accepted. A reply that accepts and then asks to continue, \
to start the next stage, or that describes what the pipeline should produce later is APPROVED: \
instructions about subsequent work are not changes to the candidate under review.

Reply:
---
{reply}
---
Answer with one word and nothing else.\
""")
  String classifyApproval(String reply);
}
